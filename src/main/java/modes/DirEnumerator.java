package modes;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;

import core.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * recursive directory and file enumeration, feroxbuster style
 *
 * breadth first, one level at a time, a real directory found at level N is
 * scanned at level N+1 until maxDepth or nothing new appears
 *
 * per directory baseline, redirect aware directory
 * detection (301 to slashed path), duplicate collapsing by status+length,
 * one shared thread pool for the whole scan
 */
public class DirEnumerator {

    private final MontoyaApi api;
    private HttpEngine engine;

    public DirEnumerator(MontoyaApi api) { this.api = api; }

    public void cancel() { if (engine != null) engine.cancel(); }

    public void scan(
            String target,
            List<String> wordlist,
            List<String> extensions,
            int maxDepth,
            int threads,
            boolean extensionsOnRecursion,
            boolean collapseDuplicates,
            Consumer<EnumResult> onHit) {

        engine = new HttpEngine(api, threads, 6);
        BaselineDetector detector = new BaselineDetector(engine);

        Set<String> seenFingerprints = ConcurrentHashMap.newKeySet();
        Set<String> visitedDirs = ConcurrentHashMap.newKeySet();

        Deque<String> frontier = new ArrayDeque<>();
        String root = normalize(target);
        frontier.add(root);
        visitedDirs.add(root);

        for (int depth = 0; depth <= maxDepth && !frontier.isEmpty() && !engine.isCancelled(); depth++) {

            List<String> level = new ArrayList<>(frontier);
            frontier.clear();

            api.logging().logToOutput("[DIR] depth " + depth + " scanning " + level.size() + " dir(s)");
            boolean useExt = depth == 0 || extensionsOnRecursion;

            for (String dir : level) {
                if (engine.isCancelled()) break;
                Baseline baseline = detector.forDirectory(dir);

                for (EnumResult hit : scanDir(dir, wordlist, extensions, baseline, useExt)) {
                    if (collapseDuplicates && !seenFingerprints.add(hit.fingerprint())) continue;
                    onHit.accept(hit);

                    if ("dir".equals(hit.extra())) {
                        String next = hit.name().endsWith("/") ? hit.name() : hit.name() + "/";
                        if (visitedDirs.add(next)) frontier.add(next);
                    }
                }
            }
        }

        engine.shutdown();
        api.logging().logToOutput("[DIR] finished");
    }

    // dispatch every word x extension for one directory, then gather
    private List<EnumResult> scanDir(
            String dirUrl, List<String> wordlist, List<String> extensions,
            Baseline baseline, boolean useExt) {

        List<String> exts = useExt ? extensions : List.of("");
        List<String> urls = new ArrayList<>();
        List<Future<HttpRequestResponse>> futures = new ArrayList<>();

        for (String word : wordlist)
            for (String ext : exts) {
                String url = dirUrl + word + ext;
                urls.add(url);
                futures.add(engine.submit(url));
            }

        List<EnumResult> hits = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            if (engine.isCancelled()) break;
            HttpRequestResponse r = engine.await(futures.get(i));
            if (r == null || !r.hasResponse()) continue;

            String url = urls.get(i);
            int status = r.response().statusCode();
            long length = r.response().body().length();
            if (!baseline.isInteresting(status, length)) continue;

            String finalUrl = finalUrlOf(r, url);
            boolean isDir = isDirectory(url, finalUrl, status);
            hits.add(EnumResult.http(url, isDir ? "-> " + finalUrl : "", status, length, isDir ? "dir" : "file"));
        }
        return hits;
    }

    // 301 to the same path with a trailing slash is the canonical dir signal
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
