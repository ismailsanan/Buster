package com.ismailsanan.buster.scan;

import com.ismailsanan.buster.core.EnumResult;
import com.ismailsanan.buster.core.UrlUtil;
import com.ismailsanan.buster.core.HttpEngine;
import com.ismailsanan.buster.core.WordlistSource;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import com.ismailsanan.buster.testutil.FakeBurp;
import com.ismailsanan.buster.testutil.FakeBurp.Resp;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** drives DirEnumerator end to end against an in-memory fake target */
@Timeout(60)
class DirEnumeratorTest {

    private static final String H = "https://h";

    /** path -> response, anything unmapped answers the given default */
    private static FakeBurp.Server site(Resp dflt, Map<String, Resp> routes) {
        return (url, host) -> routes.getOrDefault(UrlUtil.pathOf(url), dflt);
    }

    private static final class Run {
        final List<EnumResult> hits = new CopyOnWriteArrayList<>();
        final List<String> status = new CopyOnWriteArrayList<>();
        String lastStatus() { return status.isEmpty() ? "" : status.get(status.size() - 1); }
        Map<String, String> byName() {
            Map<String, String> m = new TreeMap<>();
            for (EnumResult r : hits) m.put(r.name(), r.extra());
            return m;
        }
        EnumResult hit(String name) {
            return hits.stream().filter(h -> h.name().equals(name)).findFirst().orElseThrow();
        }
    }

    private static DirEnumerator.Config cfg(String target, List<String> words, int depth, boolean collapse) {
        return new DirEnumerator.Config(target, WordlistSource.ofList(words), List.of(""), depth, 4,
                false, collapse, List.of());
    }

    private static Run run(DirEnumerator e, DirEnumerator.Config c) {
        Run r = new Run();
        e.scan(c, r.hits::add, r.status::add);
        return r;
    }

    // ---- fix 1: collapse duplicates -----------------------------------------------------

    @Test
    void collapseNeverHidesDirectoriesAndOnlyCollapsesWithinOneDirectory() {
        Map<String, Resp> routes = new HashMap<>();
        routes.put("/admin", Resp.redirect(301, "/admin/"));
        routes.put("/backup", Resp.redirect(301, "/backup/"));   // same status+length as /admin
        routes.put("/a.txt", Resp.of(200, 50));
        routes.put("/b.txt", Resp.of(200, 50));                   // duplicate of a.txt in the same dir
        routes.put("/admin/secret.txt", Resp.of(200, 50));        // same fingerprint, other dir
        routes.put("/backup/secret.txt", Resp.of(200, 50));
        FakeBurp burp = new FakeBurp(site(Resp.of(404, 10), routes));

        Run r = run(new DirEnumerator(burp.api),
                cfg(H, List.of("admin", "backup", "a.txt", "b.txt", "secret.txt"), 1, true));

        Map<String, String> got = r.byName();
        assertEquals("dir", got.get(H + "/admin"));
        assertEquals("dir", got.get(H + "/backup"));
        assertEquals("file", got.get(H + "/a.txt"));
        assertFalse(got.containsKey(H + "/b.txt"), "same-dir duplicate should collapse");
        assertEquals("file", got.get(H + "/admin/secret.txt"));
        assertEquals("file", got.get(H + "/backup/secret.txt"), "collapse must not cross directories");
        assertEquals(5, r.hits.size(), got.toString());
        assertTrue(r.lastStatus().startsWith("done, 5 found"), r.lastStatus());
    }

    @Test
    void collapseOffReportsEveryDuplicate() {
        Map<String, Resp> routes = Map.of("/a.txt", Resp.of(200, 50), "/b.txt", Resp.of(200, 50));
        FakeBurp burp = new FakeBurp(site(Resp.of(404, 10), routes));
        Run r = run(new DirEnumerator(burp.api), cfg(H, List.of("a.txt", "b.txt"), 0, false));
        assertEquals(2, r.hits.size());
    }

    // ---- fix 2: catch-all 401/403 -------------------------------------------------------

    @Test
    void catchAll403DoesNotFlood() {
        Map<String, Resp> routes = Map.of("/admin", Resp.of(200, 1234), "/admin/", Resp.of(200, 1234),
                "/private", Resp.of(403, 2000));
        FakeBurp burp = new FakeBurp(site(Resp.of(403, 300), routes));
        Run r = run(new DirEnumerator(burp.api),
                cfg(H, List.of("a", "b", "c", "admin", "private", "zzz"), 0, true));
        assertEquals(Set.of(H + "/admin", H + "/private"), r.byName().keySet());
    }

    @Test
    void catchAll401DoesNotFlood() {
        FakeBurp burp = new FakeBurp(site(Resp.of(401, 0), Map.of("/api", Resp.of(200, 20))));
        Run r = run(new DirEnumerator(burp.api), cfg(H, List.of("a", "b", "api"), 0, true));
        assertEquals(Set.of(H + "/api"), r.byName().keySet());
    }

    // ---- fix 3: calibration failure -----------------------------------------------------

    @Test
    void rootCalibrationFailureAbortsTheScan() {
        FakeBurp burp = new FakeBurp((url, host) -> FakeBurp.fail());
        Run r = run(new DirEnumerator(burp.api), cfg(H, List.of("a", "b", "c"), 2, true));
        assertTrue(r.hits.isEmpty());
        assertTrue(r.lastStatus().contains("calibration failed"), r.lastStatus());
        assertEquals(3, burp.sent.size(), "only the calibration probes should be sent: " + burp.sentUrls());
        assertTrue(burp.err.stream().anyMatch(m -> m.contains("scan aborted")));
    }

    @Test
    void rootWithNoResponseAtAllAlsoAborts() {
        FakeBurp burp = new FakeBurp((url, host) -> null);   // hasResponse() == false
        Run r = run(new DirEnumerator(burp.api), cfg(H, List.of("a"), 0, true));
        assertTrue(r.hits.isEmpty());
        assertTrue(r.lastStatus().contains("calibration failed"), r.lastStatus());
    }

    @Test
    void subdirectoryCalibrationFailureSkipsOnlyThatDirectory() {
        FakeBurp burp = new FakeBurp((url, host) -> {
            String p = UrlUtil.pathOf(url);
            if (p.startsWith("/broken/")) return FakeBurp.fail();
            return switch (p) {
                case "/broken" -> Resp.redirect(301, "/broken/");
                case "/ok" -> Resp.redirect(301, "/ok/");
                case "/ok/x" -> Resp.of(200, 7);
                default -> Resp.of(404, 10);
            };
        });
        Run r = run(new DirEnumerator(burp.api), cfg(H, List.of("broken", "ok", "x"), 1, true));
        assertEquals(Set.of(H + "/broken", H + "/ok", H + "/ok/x"), r.byName().keySet());
        assertTrue(burp.err.stream().anyMatch(m -> m.contains("calibration failed for " + H + "/broken/")), burp.err.toString());
        assertTrue(r.lastStatus().startsWith("done, 3 found"), r.lastStatus());
        assertFalse(burp.sentUrls().stream().anyMatch(u -> u.equals(H + "/broken/x")),
                "a skipped directory must not be brute forced");
    }

    /**
     * regression: the baseline used to take the FIRST responding probe's status,
     * so one transient 429/503 during calibration flooded the scan; it is now
     * the dominant non-transient status
     */
    @Test
    void transientErrorOnFirstCalibrationProbeDoesNotFlood() {
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
        FakeBurp burp = new FakeBurp((url, host) ->
                first.getAndSet(false) ? Resp.of(429, 20) : Resp.of(404, 10));
        Run r = run(new DirEnumerator(burp.api), new DirEnumerator.Config(H, WordlistSource.ofList(
                List.of("a", "b", "c")), List.of(""), 0, 1, false, false, List.of()));
        assertTrue(r.hits.isEmpty(), "404s reported as hits: " + r.byName());
    }

    // ---- fix 4 + 5: directory detection and Location resolution -------------------------

    @Test
    void directoryDetection() {
        Map<String, Resp> routes = new HashMap<>();
        routes.put("/sub/login", Resp.of(200, 10));                    // route, /login/ is 404
        routes.put("/sub/panel", Resp.of(200, 11));
        routes.put("/sub/panel/", Resp.of(200, 11));                   // confirmed by probe
        routes.put("/sub/secret", Resp.of(403, 5));
        routes.put("/sub/secret/", Resp.of(403, 5));                   // 403 dir
        routes.put("/sub/members", Resp.of(401, 6));
        routes.put("/sub/members/", Resp.of(401, 6));                  // 401 dir
        routes.put("/sub/go", Resp.redirect(302, "/login"));           // redirect elsewhere
        routes.put("/sub/old", Resp.redirect(301, "old/"));            // relative Location
        routes.put("/sub/config.php", Resp.of(200, 99));
        FakeBurp burp = new FakeBurp(site(Resp.of(404, 10), routes));

        Run r = run(new DirEnumerator(burp.api), cfg(H + "/sub",
                List.of("login", "panel", "secret", "members", "go", "old", "config.php"), 0, false));

        Map<String, String> got = r.byName();
        String b = H + "/sub/";
        assertEquals("file", got.get(b + "login"));
        assertEquals("dir", got.get(b + "panel"));
        assertEquals("dir", got.get(b + "secret"));
        assertEquals("dir", got.get(b + "members"));
        assertEquals("file", got.get(b + "go"));
        assertEquals("dir", got.get(b + "old"));
        assertEquals("file", got.get(b + "config.php"));

        assertEquals(H + "/login", r.hit(b + "go").redirect());
        assertEquals(H + "/sub/old/", r.hit(b + "old").redirect(), "relative Location resolves against the current dir");
        assertEquals("", r.hit(b + "panel").redirect());
        assertFalse(burp.sentUrls().contains(b + "config.php/"), "files with an extension are not probed");
    }

    @Test
    void trailingSlashWordIsADirectoryWithoutProbe() {
        FakeBurp burp = new FakeBurp(site(Resp.of(404, 10), Map.of("/static/", Resp.of(403, 1))));
        Run r = run(new DirEnumerator(burp.api), cfg(H, List.of("static/"), 1, true));
        assertEquals("dir", r.byName().get(H + "/static/"));
        // recursion goes to /static/ exactly once, not /static//
        assertFalse(burp.sentUrls().stream().anyMatch(u -> u.contains("//static") || u.contains("static//")),
                burp.sentUrls().toString());
    }

    @Test
    void recursionRespectsDepthAndVisitsEachDirOnce() {
        // every /x... path with a trailing x redirects into itself: infinite tree
        FakeBurp burp = new FakeBurp((url, host) -> {
            String p = UrlUtil.pathOf(url);
            return p.endsWith("/x") ? Resp.redirect(301, p + "/") : Resp.of(404, 10);
        });
        Run r = run(new DirEnumerator(burp.api), cfg(H, List.of("x", "x"), 2, true));
        assertEquals(Set.of(H + "/x", H + "/x/x", H + "/x/x/x"), r.byName().keySet());
        // duplicate word in the list: the hit is reported twice in the root but recursion is deduplicated
        long calibrationsOfX = burp.sentUrls().stream().filter(u -> u.startsWith(H + "/x/zz-nope-")).count();
        assertEquals(3, calibrationsOfX);
    }

    // ---- fix 6: encoding ----------------------------------------------------------------

    @Test
    void wordsArePercentEncoded() {
        FakeBurp burp = new FakeBurp(site(Resp.of(404, 10), Map.of()));
        run(new DirEnumerator(burp.api), new DirEnumerator.Config(H, WordlistSource.ofList(
                List.of("a b", "x#y", "q?z", "café")), List.of("", ".b k"), 0, 2, false, true, List.of()));
        List<String> urls = burp.sentUrls();
        assertTrue(urls.contains(H + "/a%20b"), urls.toString());
        assertTrue(urls.contains(H + "/x%23y"), urls.toString());
        assertTrue(urls.contains(H + "/q%3Fz"), urls.toString());
        assertTrue(urls.contains(H + "/caf%C3%A9"), urls.toString());
        assertTrue(urls.contains(H + "/a%20b.b%20k"), urls.toString());
    }

    // ---- edge cases ---------------------------------------------------------------------

    @Test
    void emptyWordlistOnlyCalibrates() {
        FakeBurp burp = new FakeBurp(site(Resp.of(404, 10), Map.of()));
        Run r = run(new DirEnumerator(burp.api), cfg(H, List.of(), 3, true));
        assertTrue(r.hits.isEmpty());
        assertEquals(3, burp.sent.size());
        assertTrue(r.lastStatus().startsWith("done, 0 found"), r.lastStatus());
    }

    @Test
    void ipv6TargetWithPort() {
        FakeBurp burp = new FakeBurp(site(Resp.of(404, 10), Map.of("/admin", Resp.redirect(301, "/admin/"))));
        Run r = run(new DirEnumerator(burp.api), cfg("[::1]:8443", List.of("admin"), 0, true));
        assertEquals(Set.of("https://[::1]:8443/admin"), r.byName().keySet());
        assertEquals("https://[::1]:8443/admin/", r.hits.get(0).redirect());
    }

    @Test
    void customHeadersAreSentOnEveryRequest() {
        FakeBurp burp = new FakeBurp(site(Resp.of(404, 10), Map.of()));
        run(new DirEnumerator(burp.api), new DirEnumerator.Config(H, WordlistSource.ofList(List.of("a")),
                List.of(""), 0, 1, false, true,
                List.of(new HttpEngine.HeaderKV("Authorization", "Bearer t"))));
        assertFalse(burp.sent.isEmpty());
        for (FakeBurp.Sent s : burp.sent) assertEquals("Bearer t", s.header("Authorization"));
    }

    // ---- fix 8: cancellation ------------------------------------------------------------

    @Test
    void cancelBeforeScanSticks() {
        FakeBurp burp = new FakeBurp(site(Resp.of(404, 10), Map.of("/a", Resp.of(200, 1))));
        DirEnumerator e = new DirEnumerator(burp.api);
        e.cancel();
        Run r = run(e, cfg(H, List.of("a", "b"), 2, true));
        assertTrue(r.hits.isEmpty());
        assertTrue(burp.sent.isEmpty(), "no request may leave after cancel(): " + burp.sentUrls());
        assertFalse(r.lastStatus().contains("calibration failed"), "a cancel is not a calibration failure: " + r.status);
        assertTrue(r.lastStatus().startsWith("stopped"), r.status.toString());
    }

    @Test
    void cancelDuringScanStopsPromptly() throws Exception {
        CountDownLatch started = new CountDownLatch(20);
        FakeBurp burp = new FakeBurp((url, host) -> {
            started.countDown();
            Thread.sleep(30);
            return Resp.of(404, 10);
        });
        List<String> words = new ArrayList<>();
        for (int i = 0; i < 5000; i++) words.add("w" + i);
        DirEnumerator e = new DirEnumerator(burp.api);
        Run r = new Run();
        Thread t = new Thread(() -> e.scan(cfg(H, words, 0, true), r.hits::add, r.status::add));
        t.start();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        long t0 = System.nanoTime();
        e.cancel();
        t.join(5000);
        assertFalse(t.isAlive(), "scan did not stop within 5s of cancel()");
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 5000);
        assertTrue(r.lastStatus().startsWith("stopped"), r.status.toString());
        assertTrue(burp.sent.size() < 5000);
    }

    /**
     * regression: shutdownNow() drops queued tasks without completing their
     * futures, await() used to block the full timeout + backstop (13s) on each,
     * so Stop during calibration with 1 thread took ~26s. await() now polls in
     * short slices and returns once the engine is cancelled.
     */
    @Test
    void cancelDuringCalibrationWithQueuedProbesStopsPromptly() throws Exception {
        CountDownLatch first = new CountDownLatch(1);
        FakeBurp burp = new FakeBurp((url, host) -> {
            first.countDown();
            Thread.sleep(500);
            return Resp.of(404, 10);
        });
        DirEnumerator e = new DirEnumerator(burp.api);
        Run r = new Run();
        Thread t = new Thread(() -> e.scan(new DirEnumerator.Config(H, WordlistSource.ofList(List.of("a")),
                List.of(""), 0, 1, false, true, List.of()), r.hits::add, r.status::add));
        t.start();
        assertTrue(first.await(10, TimeUnit.SECONDS));
        e.cancel();
        t.join(3000);
        assertFalse(t.isAlive(), "scan still blocked 3s after cancel()");
    }

    @Test
    void interruptingTheScanThreadStopsIt() throws Exception {
        CountDownLatch started = new CountDownLatch(10);
        FakeBurp burp = new FakeBurp((url, host) -> {
            started.countDown();
            Thread.sleep(20);
            return Resp.of(404, 10);
        });
        List<String> words = new ArrayList<>();
        for (int i = 0; i < 5000; i++) words.add("w" + i);
        Run r = new Run();
        Thread t = new Thread(() -> new DirEnumerator(burp.api).scan(cfg(H, words, 0, true), r.hits::add, r.status::add));
        t.start();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        t.interrupt();
        t.join(5000);
        assertFalse(t.isAlive());
        assertTrue(r.lastStatus().startsWith("stopped"), r.status.toString());
    }

    @Test
    void httpWorkerThreadsAreNamedDaemons() throws Exception {
        List<Thread> seen = new CopyOnWriteArrayList<>();
        FakeBurp burp = new FakeBurp((url, host) -> { seen.add(Thread.currentThread()); return Resp.of(404, 10); });
        run(new DirEnumerator(burp.api), cfg(H, List.of("a"), 0, true));
        assertFalse(seen.isEmpty());
        for (Thread t : seen) {
            assertTrue(t.isDaemon(), t.getName());
            assertTrue(t.getName().startsWith("Buster-dir-http-"), t.getName());
        }
    }

    // ---- show all, progress, auto-filter events, dead target ---------------------------

    /** records what the UI would be told */
    private static final class Events implements com.ismailsanan.buster.core.ScanEvents {
        final List<com.ismailsanan.buster.core.Progress> progress = new CopyOnWriteArrayList<>();
        final Map<String, com.ismailsanan.buster.core.Baseline> filters = Collections.synchronizedMap(new LinkedHashMap<>());
        final List<String> log = new CopyOnWriteArrayList<>();
        @Override public void progress(com.ismailsanan.buster.core.Progress p) { progress.add(p); }
        @Override public void autoFilter(String where, com.ismailsanan.buster.core.Baseline b) { filters.put(where, b); }
        @Override public void log(String line) { log.add(line); }
    }

    @Test
    void showAllListsBaselineResponsesMarkedAsBaseline() {
        Map<String, Resp> routes = Map.of("/admin", Resp.redirect(301, "/admin/"), "/x.txt", Resp.of(200, 5));
        FakeBurp burp = new FakeBurp(site(Resp.of(404, 10), routes));
        DirEnumerator.Config c = new DirEnumerator.Config(H, WordlistSource.ofList(List.of("a", "b", "admin", "x.txt")),
                List.of(""), 0, 4, false, true, List.of(), 0, true);
        Run r = run(new DirEnumerator(burp.api), c);
        Map<String, String> got = r.byName();
        assertEquals("baseline", got.get(H + "/a"));
        assertEquals("baseline", got.get(H + "/b"), "show all must not collapse duplicates");
        assertEquals("dir", got.get(H + "/admin"));
        assertEquals("file", got.get(H + "/x.txt"));
        assertEquals(404, r.hit(H + "/a").status());
    }

    @Test
    void showAllDoesNotConfirmBaselineLookingWordsAsDirectories() {
        // catch-all 200: every word looks like a directory candidate, only the real one may cost a / probe
        Map<String, Resp> routes = Map.of("/real", Resp.of(200, 999), "/real/", Resp.of(200, 999));
        FakeBurp burp = new FakeBurp(site(Resp.of(200, 50), routes));
        DirEnumerator.Config c = new DirEnumerator.Config(H, WordlistSource.ofList(List.of("a", "b", "c", "real")),
                List.of(""), 0, 4, false, true, List.of(), 0, true);
        run(new DirEnumerator(burp.api), c);
        long slashProbes = burp.sentUrls().stream().filter(u -> u.endsWith("/") && !u.equals(H + "/")).count();
        assertEquals(1, slashProbes, burp.sentUrls().toString());
    }

    @Test
    void reportsAutoFilterAndProgressPerDirectory() {
        Map<String, Resp> routes = Map.of("/admin", Resp.redirect(301, "/admin/"));
        FakeBurp burp = new FakeBurp((url, host) -> {
            String p = UrlUtil.pathOf(url);
            if (routes.containsKey(p)) return routes.get(p);
            return p.startsWith("/admin/") ? Resp.of(403, 77) : Resp.of(404, 10);
        });
        Events ev = new Events();
        List<String> words = List.of("admin", "a", "b", "c");
        Run r = new Run();
        new DirEnumerator(burp.api).events(ev).scan(cfg(H, words, 1, true), r.hits::add, r.status::add);

        assertEquals(404, ev.filters.get(H + "/").status());
        assertEquals(403, ev.filters.get(H + "/admin/").status());
        assertFalse(ev.progress.isEmpty());
        com.ismailsanan.buster.core.Progress last = ev.progress.get(ev.progress.size() - 1);
        assertEquals(words.size(), last.done());
        assertEquals(words.size(), last.total());
        assertTrue(last.label().startsWith("dir 2 of 2"), last.label());
        assertTrue(ev.log.get(0).contains("auto-filter on"), ev.log.toString());
    }

    /**
     * regression: a target that stops answering mid-scan (blackholed, VPN dropped)
     * used to cost timeout + backstop per queued request, hours per batch; the
     * scan must notice the stall and stop with a clear status
     */
    @Test
    void deadTargetAbortsInsteadOfWaitingOutEveryRequest() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        FakeBurp burp = new FakeBurp((url, host) -> {
            if (url.contains("zz-nope-")) return Resp.of(404, 10);   // calibration answers
            calls.incrementAndGet();
            Thread.sleep(60_000);                                   // then the target goes silent
            return Resp.of(404, 10);
        });
        List<String> words = new ArrayList<>();
        for (int i = 0; i < 200; i++) words.add("w" + i);
        long t0 = System.nanoTime();
        Run r = run(new DirEnumerator(burp.api).timeoutSeconds(1).stallSeconds(2), cfg(H, words, 0, true));
        long secs = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0);
        assertTrue(secs < 15, "took " + secs + "s");
        assertTrue(r.lastStatus().startsWith("target stopped answering"), r.status.toString());
        assertTrue(calls.get() < 50, "kept sending into a dead target: " + calls.get());
    }

    @SuppressWarnings("unused")
    private static <T> T unused(Function<T, T> f) { return null; }
}
