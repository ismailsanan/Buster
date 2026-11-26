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
    private static final long PROGRESS_EVERY_NANOS = 250_000_000L;

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
            boolean showAll) {

        /** auto-filter on */
        public Config(String target, WordlistSource wordlist, List<String> extensions, int maxDepth, int threads,
                      boolean extensionsOnRecursion, boolean collapseDuplicates, List<HttpEngine.HeaderKV> headers,
                      int requestsPerSecond) {
            this(target, wordlist, extensions, maxDepth, threads, extensionsOnRecursion, collapseDuplicates, headers,
                    requestsPerSecond, false);
        }

        /** no rate limit, auto-filter on */
        public Config(String target, WordlistSource wordlist, List<String> extensions, int maxDepth, int threads,
                      boolean extensionsOnRecursion, boolean collapseDuplicates, List<HttpEngine.HeaderKV> headers) {
            this(target, wordlist, extensions, maxDepth, threads, extensionsOnRecursion, collapseDuplicates, headers, 0);
        }
    }

    /** Type column value for a response that matches the directory's not-found baseline (show-all only) */
    public static final String TYPE_BASELINE = "baseline";

    private final MontoyaApi api;
    private volatile HttpEngine engine;
    private volatile boolean cancelled = false;
    private volatile boolean finishedCleanly = false;
    private volatile String aborted = null;
    private volatile ScanEvents events = ScanEvents.NONE;
    private int stallSeconds = 0;
    private int timeoutSeconds = TIMEOUT_SECONDS;

    // progress bookkeeping, scan thread only
    private long scanStart;
    private long lastProgress;
    private int dirsDone, dirsKnown;

    public DirEnumerator(MontoyaApi api) { this.api = api; }

    public DirEnumerator events(ScanEvents e) { this.events = e == null ? ScanEvents.NONE : e; return this; }

    /** tests only: how long without any response counts as a dead target */
    DirEnumerator stallSeconds(int s) { this.stallSeconds = s; return this; }

    /** tests only: per-request timeout */
    DirEnumerator timeoutSeconds(int s) { this.timeoutSeconds = s; return this; }

    /** true once scan() ran through the whole wordlist without being stopped or aborted */
    public boolean finishedCleanly() { return finishedCleanly; }

    /** safe to call from any thread, before or during scan() */
    public void cancel() {
        cancelled = true;
        HttpEngine e = engine;
        if (e != null) e.cancel();
    }

    private boolean stopped() { return cancelled || aborted != null || Thread.currentThread().isInterrupted(); }

    public void scan(Config cfg, Consumer<EnumResult> onHit, Consumer<String> onStatus) {
        String root = UrlUtil.normalizeTarget(cfg.target(), true);
        HttpEngine eng = new HttpEngine(api, "dir", cfg.threads(), timeoutSeconds, cfg.headers(),
                cfg.requestsPerSecond(), stallSeconds);
        engine = eng;
        if (cancelled) eng.cancel();   // cancel() raced ahead of engine creation

        int totalHits = 0;
        scanStart = System.nanoTime();
        try {
            String banner = "target " + root + " | ~" + cfg.wordlist().estimatedSize() + " words"
                    + " | extensions " + describeExts(cfg.extensions())
                    + " | depth " + cfg.maxDepth() + " | " + cfg.threads() + " threads"
                    + (cfg.requestsPerSecond() > 0 ? " | " + cfg.requestsPerSecond() + " req/s max" : "")
                    + " | " + (cfg.showAll() ? "showing ALL responses" : "auto-filter on");
            api.logging().logToOutput("[DIR] " + banner);
            events.log(banner);
            onStatus.accept("calibrating " + root + " ...");

            BaselineDetector detector = new BaselineDetector(eng);
            Set<String> visitedDirs = new HashSet<>();
            Deque<String> frontier = new ArrayDeque<>();
            frontier.add(root);
            visitedDirs.add(root);
            dirsKnown = 1;

            for (int depth = 0; depth <= cfg.maxDepth() && !frontier.isEmpty() && !stopped(); depth++) {
                List<String> level = new ArrayList<>(frontier);
                frontier.clear();
                List<String> exts = depth == 0 || cfg.extensionsOnRecursion() ? cfg.extensions() : List.of("");
                final int d = depth;

                for (String dir : level) {
                    if (stopped()) break;
                    Optional<Baseline> baseline = detector.forDirectory(dir);
                    if (baseline.isEmpty()) {
                        if (stopped()) break;
                        dirsDone++;
                        if (dir.equals(root)) {
                            onStatus.accept("target not responding (or rate limiting), calibration failed for " + root);
                            api.logging().logToError("[DIR] calibration failed for " + root + ", scan aborted");
                            events.log("calibration failed for " + root + ", scan aborted");
                            return;
                        }
                        api.logging().logToError("[DIR] calibration failed for " + dir + ", skipped");
                        events.log("calibration failed for " + dir + ", skipped");
                        continue;
                    }
                    Baseline b = baseline.get();
                    api.logging().logToOutput("[DIR] baseline " + dir + " " + b.describe());
                    events.autoFilter(dir, b);
                    onStatus.accept("scanning " + dir + " (not found = " + b.describe() + ")"
                            + (eng.backingOff() ? " (backing off, target rate limiting)" : ""));

                    totalHits += scanDir(dir, cfg, exts, b, hit -> {
                        onHit.accept(hit);
                        if ("dir".equals(hit.extra()) && d < cfg.maxDepth()) {
                            String next = hit.name().endsWith("/") ? hit.name() : hit.name() + "/";
                            if (visitedDirs.add(next)) { frontier.add(next); dirsKnown++; }
                        }
                    });
                    dirsDone++;
                }
            }

            String end;
            if (aborted != null) end = aborted + ", " + totalHits + " found";
            else end = (stopped() ? "stopped, " : "done, ") + totalHits + " found";
            String extra = (eng.timeoutCount() > 0 ? ", " + eng.timeoutCount() + " timed out" : "")
                    + (eng.errorCount() > 0 ? ", " + eng.errorCount() + " request error(s)" : "")
                    + (eng.pushBackCount() > 0 ? ", target rate limited " + eng.pushBackCount() + " time(s)" : "");
            finishedCleanly = !stopped();
            onStatus.accept(end + extra + " in " + Progress.duration(elapsedSeconds()));
            events.log(end + extra + ", " + eng.completedCount() + " requests in " + Progress.duration(elapsedSeconds()));
            api.logging().logToOutput("[DIR] finished, " + totalHits + " result(s)" + extra);
        } finally {
            eng.shutdown();
        }
    }

    private static final class Stats { int filtered, noResponse, collapsed; long done; }

    private int scanDir(String dirUrl, Config cfg, List<String> exts, Baseline baseline,
                        Consumer<EnumResult> onHit) {
        List<String> urls = new ArrayList<>(BATCH);
        List<Future<HttpEngine.Probe>> futures = new ArrayList<>(BATCH);
        // duplicate fingerprints are only meaningful inside one directory
        Set<String> seen = new HashSet<>();
        Stats stats = new Stats();
        long total = Math.max(1, cfg.wordlist().estimatedSize()) * exts.size();
        int hits = 0;

        HttpEngine.Filter keep = cfg.showAll() ? HttpEngine.KEEP_ALL : baseline::isInteresting;
        lastProgress = 0;
        progress(dirUrl, stats.done, total);

        Iterator<String> it = cfg.wordlist().words().iterator();
        try {
            while (it.hasNext() && !stopped()) {
                String word = UrlUtil.encodePath(it.next());
                for (String ext : exts) {
                    String url = dirUrl + word + UrlUtil.encodePath(ext);
                    urls.add(url);
                    futures.add(engine.submit(url, keep, p -> confirmUrl(url, p, baseline)));
                }
                if (urls.size() >= BATCH || !it.hasNext()) {
                    hits += drain(dirUrl, total, urls, futures, baseline, seen, cfg, onHit, stats);
                    urls.clear();
                    futures.clear();
                }
            }
        } finally {
            WordlistSource.close(it);
        }
        progress(dirUrl, stats.done, total);
        String line = dirUrl + " -> " + hits + " hit(s), " + stats.filtered + " auto-filtered, "
                + stats.collapsed + " collapsed, " + stats.noResponse + " no response";
        api.logging().logToOutput("[DIR] " + line);
        events.log(line);
        return hits;
    }

    private int drain(String dirUrl, long total, List<String> urls, List<Future<HttpEngine.Probe>> futures,
                      Baseline baseline, Set<String> seen, Config cfg, Consumer<EnumResult> onHit, Stats stats) {
        int hits = 0;
        for (int i = 0; i < futures.size(); i++) {
            if (stopped()) break;
            HttpEngine.Probe p = engine.await(futures.get(i));
            stats.done++;
            maybeProgress(dirUrl, stats.done, total);
            if (p == null) {
                stats.noResponse++;
                if (engine.stalled() && !cancelled) {
                    aborted = "target stopped answering (nothing back for "
                            + (stallSeconds > 0 ? stallSeconds : 3 * timeoutSeconds + 5) + "s), scan aborted";
                    api.logging().logToError("[DIR] " + aborted + " in " + dirUrl);
                    events.log(aborted + " in " + dirUrl);
                }
                continue;
            }

            String url = urls.get(i);
            int status = p.status();
            long length = p.length();
            if (p.exchange() == null) { stats.filtered++; continue; }   // filter rejected it in the pool

            boolean interesting = baseline.isInteresting(status, length);
            if (!interesting) {
                // show-all mode: a response that looks like "not found" here, reported but marked
                stats.filtered++;
                onHit.accept(EnumResult.http(url, redirectOf(url, status, p.location()), status, length,
                        TYPE_BASELINE, p.exchange()));
                hits++;
                continue;
            }

            boolean isDir = isDirectory(url, status, p, baseline);
            // never collapse directories, two dirs often share an identical redirect body
            if (!isDir && cfg.collapseDuplicates() && !cfg.showAll() && !seen.add(status + ":" + length)) {
                stats.collapsed++;
                continue;
            }

            onHit.accept(EnumResult.http(url, redirectOf(url, status, p.location()), status, length,
                    isDir ? "dir" : "file", p.exchange()));
            hits++;
        }
        return hits;
    }

    private static String redirectOf(String url, int status, String location) {
        return isRedirect(status) ? UrlUtil.resolveLocation(url, location) : "";
    }

    /**
     * url + "/" when an extensionless hit with a directory-like status needs
     * confirming, so plain routes like /login are not recursed into; runs in the
     * pool, right after the first response
     */
    static String confirmUrl(String url, HttpEngine.Probe p, Baseline baseline) {
        int status = p.status();
        if (isRedirect(status) || !isDirStatus(status) || url.endsWith("/")) return null;
        if (!baseline.isInteresting(status, p.length())) return null;
        if (!UrlUtil.looksExtensionless(url)) return null;
        return url + "/";
    }

    /** a redirect to url + "/" is a directory, otherwise the pooled url + "/" probe decides */
    private static boolean isDirectory(String url, int status, HttpEngine.Probe p, Baseline baseline) {
        if (isRedirect(status)) return UrlUtil.redirectsIntoDirectory(url, p.location());
        if (!isDirStatus(status)) return false;
        if (url.endsWith("/")) return true;
        HttpEngine.Probe confirm = p.followUp();
        return confirm != null && isDirStatus(confirm.status())
                && baseline.isInteresting(confirm.status(), confirm.length());
    }

    private void maybeProgress(String dirUrl, long done, long total) {
        long now = System.nanoTime();
        if (now - lastProgress < PROGRESS_EVERY_NANOS) return;
        progress(dirUrl, done, total);
    }

    private void progress(String dirUrl, long done, long total) {
        lastProgress = System.nanoTime();
        double secs = Math.max(0.001, (lastProgress - scanStart) / 1e9);
        HttpEngine e = engine;
        double rate = e == null ? 0 : e.completedCount() / secs;
        int current = Math.min(dirsDone + 1, dirsKnown);
        String label = "dir " + current + " of " + dirsKnown + ": " + dirUrl
                + (e != null && e.backingOff() ? " (backing off, target rate limiting)" : "");
        events.progress(new Progress(done, total, label, rate));
    }

    private long elapsedSeconds() { return (System.nanoTime() - scanStart) / 1_000_000_000L; }

    private static String describeExts(List<String> exts) {
        List<String> real = exts.stream().filter(e -> !e.isEmpty()).toList();
        return real.isEmpty() ? "none" : String.join(",", real);
    }

    static boolean isRedirect(int status)  { return status >= 300 && status < 400; }
    static boolean isDirStatus(int status) { return (status >= 200 && status < 300) || status == 401 || status == 403; }
}
