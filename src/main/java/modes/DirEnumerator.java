package modes;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;

import core.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * recursive directory and file enumeration
 * streams the wordlist and processes requests in bounded batches, so memory
 * stays flat regardless of wordlist size
 */
public class DirEnumerator {

    private static final int BATCH = 500;

    private final MontoyaApi api;
    private HttpEngine engine;

    public DirEnumerator(MontoyaApi api) { this.api = api; }

    public void cancel() { if (engine != null) engine.cancel(); }

    public void scan(
            String target,
            WordlistSource wordlist,
            List<String> extensions,
            int maxDepth,
            int threads,
            boolean extensionsOnRecursion,
            boolean collapseDuplicates,
            List<HttpEngine.HeaderKV> headers,
            Consumer<EnumResult> onHit,
            Consumer<String> onStatus) {

        engine = new HttpEngine(api, threads, 8, headers);
        api.logging().logToOutput("[DIR] starting, ~" + wordlist.estimatedSize()
                + " words, depth " + maxDepth + ", " + threads + " threads");

        Set<String> seenFingerprints = ConcurrentHashMap.newKeySet();
        Set<String> visitedDirs = ConcurrentHashMap.newKeySet();

        Deque<String> frontier = new ArrayDeque<>();
        String root = normalize(target);
        frontier.add(root);
        visitedDirs.add(root);

        onStatus.accept("calibrating and scanning " + root + " ...");
        int totalHits = 0;

        for (int depth = 0; depth <= maxDepth && !frontier.isEmpty() && !engine.isCancelled(); depth++) {
            List<String> level = new ArrayList<>(frontier);
            frontier.clear();
            boolean useExt = depth == 0 || extensionsOnRecursion;

            for (String dir : level) {
                if (engine.isCancelled()) break;
                onStatus.accept("scanning " + dir + " ...");
                Baseline baseline = calibrate(dir);

                totalHits += scanDir(dir, wordlist, extensions, baseline, useExt,
                        seenFingerprints, collapseDuplicates,
                        hit -> {
                            onHit.accept(hit);
                            if ("dir".equals(hit.extra())) {
                                String next = hit.name().endsWith("/") ? hit.name() : hit.name() + "/";
                                if (visitedDirs.add(next)) frontier.add(next);
                            }
                        });
            }
        }

        engine.shutdown();
        onStatus.accept(engine.isCancelled() ? "stopped, " + totalHits + " found"
                                             : "done, " + totalHits + " found");
        api.logging().logToOutput("[DIR] finished, " + totalHits + " result(s)");
    }

    private Baseline calibrate(String dirUrl) {
        HttpRequestResponse r = engine.sendOnce(dirUrl + "zzz-none-" + System.nanoTime());
        if (r == null || !r.hasResponse()) return new Baseline(404, 0, false);
        int status = r.response().statusCode();
        long length = r.response().body().length();
        boolean soft404 = status >= 200 && status < 400;
        api.logging().logToOutput("[DIR] baseline " + dirUrl + " status=" + status
                + " len=" + length + (soft404 ? " (soft404)" : " (clean)"));
        return new Baseline(status, length, soft404);
    }

    private int scanDir(String dirUrl, WordlistSource wordlist, List<String> extensions,
                        Baseline baseline, boolean useExt,
                        Set<String> seen, boolean collapse, Consumer<EnumResult> onHit) {

        List<String> exts = useExt ? extensions : List.of("");
        List<String> urls = new ArrayList<>(BATCH);
        List<Future<HttpRequestResponse>> futures = new ArrayList<>(BATCH);
        int hits = 0;
        int[] stats = {0, 0};

        Iterator<String> it = wordlist.words().iterator();
        while (it.hasNext() && !engine.isCancelled()) {
            String word = it.next();
            for (String ext : exts) {
                String url = dirUrl + word + ext;
                urls.add(url);
                futures.add(engine.submit(url));
            }
            if (urls.size() >= BATCH || !it.hasNext()) {
                hits += drain(urls, futures, baseline, seen, collapse, onHit, stats);
                urls.clear();
                futures.clear();
            }
        }
        api.logging().logToOutput("[DIR] " + dirUrl + " -> " + hits + " hit(s), "
                + stats[0] + " filtered, " + stats[1] + " no-response");
        return hits;
    }

    private int drain(List<String> urls, List<Future<HttpRequestResponse>> futures,
                      Baseline baseline, Set<String> seen, boolean collapse,
                      Consumer<EnumResult> onHit, int[] stats) {
        int hits = 0;
        for (int i = 0; i < futures.size(); i++) {
            if (engine.isCancelled()) break;
            HttpRequestResponse r = engine.await(futures.get(i));
            if (r == null || !r.hasResponse()) { stats[1]++; continue; }

            String url = urls.get(i);
            int status = r.response().statusCode();
            long length = r.response().body().length();
            if (!baseline.isInteresting(status, length)) { stats[0]++; continue; }

            String fp = status + ":" + length;
            if (collapse && !seen.add(fp)) continue;

            String finalUrl = finalUrlOf(r, url);
            boolean isDir = isDirectory(url, finalUrl, status);
            String redirect = (status >= 300 && status < 400) ? finalUrl : "";
            onHit.accept(EnumResult.http(url, redirect, status, length, isDir ? "dir" : "file", r));
            hits++;
        }
        return hits;
    }

    private boolean isDirectory(String requested, String finalUrl, int status) {
        if (status >= 300 && status < 400) {
            String slashed = requested.endsWith("/") ? requested : requested + "/";
            if (finalUrl.equals(slashed) || finalUrl.startsWith(slashed)) return true;
        }
        String last = requested.substring(requested.lastIndexOf('/') + 1);
        return status == 200 && !last.contains(".");
    }

    private String finalUrlOf(HttpRequestResponse r, String requested) {
        try {
            String loc = r.response().headerValue("Location");
            if (loc != null && !loc.isBlank())
                return loc.startsWith("http") ? loc : resolveRelative(requested, loc);
        } catch (Exception ignored) {}
        return requested;
    }

    private String resolveRelative(String base, String loc) {
        try {
            int schemeEnd = base.indexOf("://") + 3;
            int slash = base.indexOf('/', schemeEnd);
            String origin = slash == -1 ? base : base.substring(0, slash);
            return loc.startsWith("/") ? origin + loc : origin + "/" + loc;
        } catch (Exception e) { return base; }
    }

    private String normalize(String url) {
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
        if (!u.endsWith("/")) u = u + "/";
        return u;
    }
}
