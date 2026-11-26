package com.ismailsanan.buster.scan;

import burp.api.montoya.MontoyaApi;

import com.ismailsanan.buster.core.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * virtual host enumeration, varies the Host header against one target
 * streams the wordlist in bounded batches
 *
 * limitation: over HTTPS the TLS SNI is taken from the target URL, not the
 * Host header, so servers that route purely on SNI will not reveal vhosts
 */
public class VhostEnumerator {

    private static final int BATCH = 500;
    private static final int TIMEOUT_SECONDS = 6;
    private static final long PROGRESS_EVERY_NANOS = 250_000_000L;

    public record Config(
            String target,
            String domain,
            WordlistSource wordlist,
            int threads,
            List<HttpEngine.HeaderKV> headers,
            int requestsPerSecond,
            boolean showAll) {

        /** auto-filter on */
        public Config(String target, String domain, WordlistSource wordlist, int threads,
                      List<HttpEngine.HeaderKV> headers, int requestsPerSecond) {
            this(target, domain, wordlist, threads, headers, requestsPerSecond, false);
        }

        /** no rate limit, auto-filter on */
        public Config(String target, String domain, WordlistSource wordlist, int threads,
                      List<HttpEngine.HeaderKV> headers) {
            this(target, domain, wordlist, threads, headers, 0);
        }
    }

    private final MontoyaApi api;
    private volatile HttpEngine engine;
    private volatile boolean cancelled = false;
    private volatile boolean finishedCleanly = false;
    private volatile String aborted = null;
    private volatile ScanEvents events = ScanEvents.NONE;

    public VhostEnumerator(MontoyaApi api) { this.api = api; }

    public VhostEnumerator events(ScanEvents e) { this.events = e == null ? ScanEvents.NONE : e; return this; }

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
        String url = UrlUtil.normalizeTarget(cfg.target(), false);
        String domain = cfg.domain() == null ? "" : cfg.domain().trim();
        // a user supplied Host header would fight the one being enumerated
        List<HttpEngine.HeaderKV> headers = cfg.headers().stream()
                .filter(h -> !h.name().equalsIgnoreCase("Host")).toList();

        HttpEngine eng = new HttpEngine(api, "vhost", cfg.threads(), TIMEOUT_SECONDS, headers, cfg.requestsPerSecond());
        engine = eng;
        if (cancelled) eng.cancel();

        long start = System.nanoTime();
        int found = 0;
        try {
            String banner = "target " + url + " | Host = word" + (domain.isBlank() ? "" : "." + domain)
                    + " | ~" + cfg.wordlist().estimatedSize() + " words | " + cfg.threads() + " threads"
                    + (cfg.requestsPerSecond() > 0 ? " | " + cfg.requestsPerSecond() + " req/s max" : "")
                    + " | " + (cfg.showAll() ? "showing ALL responses" : "auto-filter on");
            events.log(banner);
            onStatus.accept("calibrating " + url + " ...");
            Optional<Baseline> calibrated = new BaselineDetector(eng).forVhost(url);
            if (calibrated.isEmpty()) {
                if (!stopped()) {
                    onStatus.accept("target not responding (or rate limiting), calibration failed for " + url);
                    api.logging().logToError("[VHOST] calibration failed for " + url + ", scan aborted");
                    events.log("calibration failed for " + url + ", scan aborted");
                } else {
                    onStatus.accept("stopped, 0 found");
                }
                return;
            }
            Baseline baseline = calibrated.get();
            api.logging().logToOutput("[VHOST] baseline " + baseline.describe());
            events.autoFilter(url + " (unknown Host)", baseline);
            onStatus.accept("scanning " + url + " (unknown host = " + baseline.describe() + ")");

            HttpEngine.Filter keep = cfg.showAll() ? HttpEngine.KEEP_ALL : baseline::differsFrom;
            long total = Math.max(1, cfg.wordlist().estimatedSize());
            long done = 0, lastProgress = 0;

            List<String> hosts = new ArrayList<>(BATCH);
            List<Future<HttpEngine.Probe>> futures = new ArrayList<>(BATCH);

            Iterator<String> it = cfg.wordlist().words().iterator();
            try {
                while (it.hasNext() && !stopped()) {
                    String word = it.next();
                    String host = domain.isBlank() ? word : word + "." + domain;
                    hosts.add(host);
                    futures.add(eng.submit(url, host, keep));
                    if (hosts.size() >= BATCH || !it.hasNext()) {
                        for (int i = 0; i < futures.size() && !stopped(); i++) {
                            HttpEngine.Probe p = eng.await(futures.get(i));
                            done++;
                            long now = System.nanoTime();
                            if (now - lastProgress >= PROGRESS_EVERY_NANOS) {
                                lastProgress = now;
                                events.progress(progress(done, total, url, eng, start));
                            }
                            if (p == null) {
                                if (eng.stalled() && !cancelled) {
                                    aborted = "target stopped answering (nothing back for "
                                            + (3 * TIMEOUT_SECONDS + 5) + "s), scan aborted";
                                    events.log(aborted);
                                }
                                continue;
                            }
                            if (p.exchange() == null) continue;   // same as baseline
                            String type = baseline.differsFrom(p.status(), p.length()) ? "vhost" : "baseline";
                            onHit.accept(EnumResult.http(hosts.get(i), "", p.status(), p.length(), type, p.exchange()));
                            found++;
                        }
                        hosts.clear();
                        futures.clear();
                    }
                }
            } finally {
                WordlistSource.close(it);
            }
            events.progress(progress(done, total, url, eng, start));
            String end = (aborted != null ? aborted + ", " : stopped() ? "stopped, " : "done, ") + found + " found";
            String extra = (eng.timeoutCount() > 0 ? ", " + eng.timeoutCount() + " timed out" : "")
                    + (eng.errorCount() > 0 ? ", " + eng.errorCount() + " request error(s)" : "")
                    + (eng.pushBackCount() > 0 ? ", target rate limited " + eng.pushBackCount() + " time(s)" : "");
            String took = " in " + Progress.duration((System.nanoTime() - start) / 1_000_000_000L);
            finishedCleanly = !stopped();
            onStatus.accept(end + extra + took);
            events.log(end + extra + took);
            api.logging().logToOutput("[VHOST] finished, " + found + " found" + extra);
        } finally {
            eng.shutdown();
        }
    }

    private static Progress progress(long done, long total, String url, HttpEngine eng, long start) {
        double secs = Math.max(0.001, (System.nanoTime() - start) / 1e9);
        return new Progress(done, total, "Host header against " + url
                + (eng.backingOff() ? " (backing off, target rate limiting)" : ""), eng.completedCount() / secs);
    }
}
