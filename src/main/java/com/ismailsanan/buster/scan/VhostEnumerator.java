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

    public record Config(
            String target,
            String domain,
            WordlistSource wordlist,
            int threads,
            List<HttpEngine.HeaderKV> headers,
            int requestsPerSecond) {

        /** no rate limit */
        public Config(String target, String domain, WordlistSource wordlist, int threads,
                      List<HttpEngine.HeaderKV> headers) {
            this(target, domain, wordlist, threads, headers, 0);
        }
    }

    private final MontoyaApi api;
    private volatile HttpEngine engine;
    private volatile boolean cancelled = false;

    public VhostEnumerator(MontoyaApi api) { this.api = api; }

    /** safe to call from any thread, before or during scan() */
    public void cancel() {
        cancelled = true;
        HttpEngine e = engine;
        if (e != null) e.cancel();
    }

    private boolean stopped() { return cancelled || Thread.currentThread().isInterrupted(); }

    public void scan(Config cfg, Consumer<EnumResult> onHit, Consumer<String> onStatus) {
        String url = UrlUtil.normalizeTarget(cfg.target(), false);
        String domain = cfg.domain() == null ? "" : cfg.domain().trim();
        // a user supplied Host header would fight the one being enumerated
        List<HttpEngine.HeaderKV> headers = cfg.headers().stream()
                .filter(h -> !h.name().equalsIgnoreCase("Host")).toList();

        HttpEngine eng = new HttpEngine(api, "vhost", cfg.threads(), TIMEOUT_SECONDS, headers, cfg.requestsPerSecond());
        engine = eng;
        if (cancelled) eng.cancel();

        int found = 0;
        try {
            onStatus.accept("calibrating " + url + " ...");
            Optional<Baseline> calibrated = new BaselineDetector(eng).forVhost(url);
            if (calibrated.isEmpty()) {
                if (!stopped()) {
                    onStatus.accept("target not responding (or rate limiting), calibration failed for " + url);
                    api.logging().logToError("[VHOST] calibration failed for " + url + ", scan aborted");
                } else {
                    onStatus.accept("stopped, 0 found");
                }
                return;
            }
            Baseline baseline = calibrated.get();
            api.logging().logToOutput("[VHOST] baseline " + baseline.describe());
            onStatus.accept("scanning " + url + " (~" + cfg.wordlist().estimatedSize() + " hosts) ...");

            List<String> hosts = new ArrayList<>(BATCH);
            List<Future<HttpEngine.Probe>> futures = new ArrayList<>(BATCH);

            Iterator<String> it = cfg.wordlist().words().iterator();
            try {
                while (it.hasNext() && !stopped()) {
                    String word = it.next();
                    String host = domain.isBlank() ? word : word + "." + domain;
                    hosts.add(host);
                    futures.add(eng.submit(url, host, baseline::differsFrom));
                    if (hosts.size() >= BATCH || !it.hasNext()) {
                        found += drain(hosts, futures, baseline, onHit);
                        hosts.clear();
                        futures.clear();
                    }
                }
            } finally {
                WordlistSource.close(it);
            }
            onStatus.accept((stopped() ? "stopped, " : "done, ") + found + " found");
            api.logging().logToOutput("[VHOST] finished, " + found + " found"
                    + (eng.errorCount() > 0 ? ", " + eng.errorCount() + " request error(s)" : "")
                    + (eng.pushBackCount() > 0 ? ", target rate limited " + eng.pushBackCount() + " time(s)" : ""));
        } finally {
            eng.shutdown();
        }
    }

    private int drain(List<String> hosts, List<Future<HttpEngine.Probe>> futures,
                      Baseline baseline, Consumer<EnumResult> onHit) {
        int found = 0;
        for (int i = 0; i < futures.size(); i++) {
            if (stopped()) break;
            HttpEngine.Probe p = engine.await(futures.get(i));
            if (p == null || p.exchange() == null) continue;   // no response, or same as baseline
            onHit.accept(EnumResult.http(hosts.get(i), "", p.status(), p.length(), "vhost", p.exchange()));
            found++;
        }
        return found;
    }
}
