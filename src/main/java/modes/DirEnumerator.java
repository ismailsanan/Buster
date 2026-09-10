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
 *
 * breadth first, one level at a time, a directory found at level N is
 * scanned at level N+1 until maxDepth
 *
 * every request's fate is logged to the Output tab so it's clear why
 * something did or didn't become a result, the baseline fails OPEN, if
 * calibration is uncertain it reports the hit rather than silently
 * dropping it
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
            List<HttpEngine.HeaderKV> headers,
            Consumer<EnumResult> onHit,
            Consumer<String> onStatus) {

        engine = new HttpEngine(api, threads, 8, headers);

        api.logging().logToOutput("[DIR] starting, " + wordlist.size()
                + " words, " + extensions.size() + " ext(s), depth " + maxDepth
                + ", " + threads + " threads");

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

            api.logging().logToOutput("[DIR] depth " + depth + " scanning " + level.size() + " dir(s)");
            boolean useExt = depth == 0 || extensionsOnRecursion;

            for (String dir : level) {
                if (engine.isCancelled()) break;

                onStatus.accept("scanning " + dir + " (" + wordlist.size() + " words) ...");
                Baseline baseline = calibrate(dir);

                for (EnumResult hit : scanDir(dir, wordlist, extensions, baseline, useExt)) {
                    if (collapseDuplicates && !seenFingerprints.add(hit.fingerprint())) continue;

                    totalHits++;
                    onHit.accept(hit);

                    if ("dir".equals(hit.extra())) {
                        String next = hit.name().endsWith("/") ? hit.name() : hit.name() + "/";
                        if (visitedDirs.add(next)) frontier.add(next);
                    }
                }
            }
        }

        engine.shutdown();
        onStatus.accept(engine.isCancelled() ? "stopped, " + totalHits + " found" : "done, " + totalHits + " found");
        api.logging().logToOutput("[DIR] finished, " + totalHits + " result(s)");
    }

    // probe a random path, log what the server does with it
    // if the probe fails we return a "trust the status code" baseline rather
    // than a soft-404 one, so a failed probe never causes everything to be filtered
    private Baseline calibrate(String dirUrl) {
        String probe = dirUrl + "zzz-does-not-exist-" + System.nanoTime();
        HttpRequestResponse r = engine.sendOnce(probe);

        if (r == null || !r.hasResponse()) {
            api.logging().logToOutput("[DIR] baseline probe got no response for "
                    + dirUrl + ", trusting status codes (404 = miss)");
            return new Baseline(404, 0, false);
        }

        int status = r.response().statusCode();
        long length = r.response().body().length();
        boolean soft404 = status >= 200 && status < 400;

        api.logging().logToOutput("[DIR] baseline for " + dirUrl
                + " -> status=" + status + " length=" + length
                + (soft404 ? " (soft 404, filtering by length)" : " (clean, 404 = miss)"));

        return new Baseline(status, length, soft404);
    }

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
        int noResponse = 0;
        int filtered = 0;

        for (int i = 0; i < futures.size(); i++) {
            if (engine.isCancelled()) break;

            HttpRequestResponse r = engine.await(futures.get(i));
            if (r == null || !r.hasResponse()) { noResponse++; continue; }

            String url = urls.get(i);
            int status = r.response().statusCode();
            long length = r.response().body().length();

            if (!baseline.isInteresting(status, length)) { filtered++; continue; }

            String finalUrl = finalUrlOf(r, url);
            boolean isDir = isDirectory(url, finalUrl, status);
            // show the redirect target whenever the response is a 3xx,
            // regardless of whether we treat it as a directory
            String redirect = (status >= 300 && status < 400) ? finalUrl : "";
            hits.add(EnumResult.http(url, redirect, status, length,
                    isDir ? "dir" : "file", r));
        }

        // one summary line per directory so it's obvious where results went
        api.logging().logToOutput("[DIR] " + dirUrl + " -> " + hits.size()
                + " hit(s), " + filtered + " filtered as baseline, "
                + noResponse + " no response");

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