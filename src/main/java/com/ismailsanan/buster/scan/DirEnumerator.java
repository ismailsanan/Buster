package com.ismailsanan.buster.scan;

import burp.api.montoya.MontoyaApi;

import com.ismailsanan.buster.core.*;

import java.util.*;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * recursive directory and file enumeration
 * streams the wordlist and processes requests in bounded batches, so memory
 * stays flat regardless of wordlist size
 */
public class DirEnumerator {

    private static final int BATCH = 500;
    private static final int TIMEOUT_SECONDS = 8;

    public record Config(
            String target,
            WordlistSource wordlist,
            List<String> extensions,
            int maxDepth,
            int threads,
            boolean extensionsOnRecursion,
            boolean collapseDuplicates,
            List<HttpEngine.HeaderKV> headers,
            int requestsPerSecond,
            boolean inScopeOnly) {

        /** no rate limit, no scope check */
        public Config(String target, WordlistSource wordlist, List<String> extensions, int maxDepth, int threads,
                      boolean extensionsOnRecursion, boolean collapseDuplicates, List<HttpEngine.HeaderKV> headers) {
            this(target, wordlist, extensions, maxDepth, threads, extensionsOnRecursion, collapseDuplicates, headers, 0, false);
        }

        /** no scope check */
        public Config(String target, WordlistSource wordlist, List<String> extensions, int maxDepth, int threads,
                      boolean extensionsOnRecursion, boolean collapseDuplicates, List<HttpEngine.HeaderKV> headers,
                      int requestsPerSecond) {
            this(target, wordlist, extensions, maxDepth, threads, extensionsOnRecursion, collapseDuplicates, headers,
                    requestsPerSecond, false);
        }
    }

    public static final String OUT_OF_SCOPE =
            "target is not in Burp's scope: add it under Target > Scope, or untick \"Only in-scope targets\"";

    private final MontoyaApi api;
    private volatile HttpEngine engine;
    private volatile boolean cancelled = false;

    public DirEnumerator(MontoyaApi api) { this.api = api; }

    /** safe to call from any thread, before or during scan() */
    public void cancel() {
        cancelled = true;
        HttpEngine e = engine;
        if (e != null) e.cancel();
    }

    private boolean stopped() { return cancelled || Thread.currentThread().isInterrupted(); }

    public void scan(Config cfg, Consumer<EnumResult> onHit, Consumer<String> onStatus) {
        String root = UrlUtil.normalizeTarget(cfg.target(), true);
        if (cfg.inScopeOnly() && !api.scope().isInScope(root)) {
            onStatus.accept(OUT_OF_SCOPE);
            api.logging().logToOutput("[DIR] " + root + " is out of scope, nothing sent");
            return;
        }
        HttpEngine eng = new HttpEngine(api, "dir", cfg.threads(), TIMEOUT_SECONDS, cfg.headers(),
                cfg.requestsPerSecond());
        engine = eng;
        if (cancelled) eng.cancel();   // cancel() raced ahead of engine creation

        int totalHits = 0;
        try {
            api.logging().logToOutput("[DIR] starting " + root + ", ~" + cfg.wordlist().estimatedSize()
                    + " words, depth " + cfg.maxDepth() + ", " + cfg.threads() + " threads");
            onStatus.accept("calibrating " + root + " ...");

            BaselineDetector detector = new BaselineDetector(eng);
            Set<String> visitedDirs = new HashSet<>();
            Deque<String> frontier = new ArrayDeque<>();
            frontier.add(root);
            visitedDirs.add(root);

            for (int depth = 0; depth <= cfg.maxDepth() && !frontier.isEmpty() && !stopped(); depth++) {
                List<String> level = new ArrayList<>(frontier);
                frontier.clear();
                List<String> exts = depth == 0 || cfg.extensionsOnRecursion() ? cfg.extensions() : List.of("");

                for (String dir : level) {
                    if (stopped()) break;
                    Optional<Baseline> baseline = detector.forDirectory(dir);
                    if (baseline.isEmpty()) {
                        if (stopped()) break;
                        if (dir.equals(root)) {
                            onStatus.accept("target not responding (or rate limiting), calibration failed for " + root);
                            api.logging().logToError("[DIR] calibration failed for " + root + ", scan aborted");
                            return;
                        }
                        api.logging().logToError("[DIR] calibration failed for " + dir + ", skipped");
                        continue;
                    }
                    api.logging().logToOutput("[DIR] baseline " + dir + " " + baseline.get().describe());
                    onStatus.accept("scanning " + dir + " ..." + (eng.backingOff() ? " (backing off, target rate limiting)" : ""));

                    totalHits += scanDir(dir, cfg, exts, baseline.get(), hit -> {
                        onHit.accept(hit);
                        if ("dir".equals(hit.extra())) {
                            String next = hit.name().endsWith("/") ? hit.name() : hit.name() + "/";
                            if (!visitedDirs.add(next)) return;
                            if (cfg.inScopeOnly() && !api.scope().isInScope(next)) {
                                api.logging().logToOutput("[DIR] " + next + " is out of scope, not recursed");
                                return;
                            }
                            frontier.add(next);
                        }
                    });
                }
            }
            onStatus.accept((stopped() ? "stopped, " : "done, ") + totalHits + " found");
            api.logging().logToOutput("[DIR] finished, " + totalHits + " result(s)"
                    + (eng.errorCount() > 0 ? ", " + eng.errorCount() + " request error(s)" : "")
                    + (eng.pushBackCount() > 0 ? ", target rate limited " + eng.pushBackCount() + " time(s)" : ""));
        } finally {
            eng.shutdown();
        }
    }

    private static final class Stats { int filtered, noResponse, collapsed; }

    private int scanDir(String dirUrl, Config cfg, List<String> exts, Baseline baseline,
                        Consumer<EnumResult> onHit) {
        List<String> urls = new ArrayList<>(BATCH);
        List<Future<HttpEngine.Probe>> futures = new ArrayList<>(BATCH);
        // duplicate fingerprints are only meaningful inside one directory
        Set<String> seen = new HashSet<>();
        Stats stats = new Stats();
        int hits = 0;

        Iterator<String> it = cfg.wordlist().words().iterator();
        try {
            while (it.hasNext() && !stopped()) {
                String word = UrlUtil.encodePath(it.next());
                for (String ext : exts) {
                    String url = dirUrl + word + UrlUtil.encodePath(ext);
                    urls.add(url);
                    futures.add(engine.submit(url, baseline::isInteresting));
                }
                if (urls.size() >= BATCH || !it.hasNext()) {
                    hits += drain(urls, futures, baseline, seen, cfg.collapseDuplicates(), onHit, stats);
                    urls.clear();
                    futures.clear();
                }
            }
        } finally {
            WordlistSource.close(it);
        }
        api.logging().logToOutput("[DIR] " + dirUrl + " -> " + hits + " hit(s), " + stats.filtered
                + " filtered, " + stats.collapsed + " collapsed, " + stats.noResponse + " no-response");
        return hits;
    }

    private int drain(List<String> urls, List<Future<HttpEngine.Probe>> futures, Baseline baseline,
                      Set<String> seen, boolean collapse, Consumer<EnumResult> onHit, Stats stats) {
        int hits = 0;
        for (int i = 0; i < futures.size(); i++) {
            if (stopped()) break;
            HttpEngine.Probe p = engine.await(futures.get(i));
            if (p == null) { stats.noResponse++; continue; }

            String url = urls.get(i);
            int status = p.status();
            long length = p.length();
            if (p.exchange() == null) { stats.filtered++; continue; }   // filter rejected it in the pool

            String location = p.location();
            boolean isDir = isDirectory(url, status, location, baseline);
            // never collapse directories, two dirs often share an identical redirect body
            if (!isDir && collapse && !seen.add(status + ":" + length)) { stats.collapsed++; continue; }

            String redirect = isRedirect(status) ? UrlUtil.resolveLocation(url, location) : "";
            onHit.accept(EnumResult.http(url, redirect, status, length, isDir ? "dir" : "file", p.exchange()));
            hits++;
        }
        return hits;
    }

    /**
     * a redirect to url + "/" is a directory, otherwise an extensionless hit
     * with a directory-like status is confirmed by requesting url + "/" so plain
     * routes like /login are not recursed into
     */
    private boolean isDirectory(String url, int status, String location, Baseline baseline) {
        if (isRedirect(status)) return UrlUtil.redirectsIntoDirectory(url, location);
        if (!isDirStatus(status)) return false;
        if (url.endsWith("/")) return true;
        if (!UrlUtil.looksExtensionless(url)) return false;

        HttpEngine.Probe probe = engine.sendOnce(url + "/");
        return probe != null && isDirStatus(probe.status()) && baseline.isInteresting(probe.status(), probe.length());
    }

    static boolean isRedirect(int status)  { return status >= 300 && status < 400; }
    static boolean isDirStatus(int status) { return (status >= 200 && status < 300) || status == 401 || status == 403; }
}
