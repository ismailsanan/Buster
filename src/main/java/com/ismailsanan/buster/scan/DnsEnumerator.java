package com.ismailsanan.buster.scan;

import burp.api.montoya.MontoyaApi;

import com.ismailsanan.buster.core.Baseline;
import com.ismailsanan.buster.core.DnsResolver;
import com.ismailsanan.buster.core.HttpEngine;
import com.ismailsanan.buster.core.DnsResult;
import com.ismailsanan.buster.core.Progress;
import com.ismailsanan.buster.core.ScanEvents;
import com.ismailsanan.buster.core.Threads;
import com.ismailsanan.buster.core.WordlistSource;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * active subdomain enumeration via DNS resolution
 *
 * the wordlist is streamed with a bounded number of lookups in flight, so
 * memory stays flat for huge lists, and every lookup is collected as it
 * completes rather than against a fixed deadline, so slow answers are not lost
 *
 * lookups use the OS resolver and do NOT go through Burp's network stack
 */
public class DnsEnumerator {

    private static final int IN_FLIGHT_PER_THREAD = 4;
    private static final int WILDCARD_PROBES = 3;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final int BURP_TIMEOUT_SECONDS = 6;

    /** how names are looked up */
    public enum Resolver {
        /** the operating system resolver: lists A/AAAA records, bypasses Burp's network settings */
        SYSTEM,
        /** an HTTP(S) request through Burp: honours its upstream proxy / SOCKS settings, finds live hosts */
        BURP
    }

    public record Config(String domain, WordlistSource wordlist, int threads, Resolver resolver) {
        /** system resolver */
        public Config(String domain, WordlistSource wordlist, int threads) {
            this(domain, wordlist, threads, Resolver.SYSTEM);
        }
    }

    private final MontoyaApi api;
    private volatile ExecutorService pool;
    private volatile HttpEngine engine;
    private volatile boolean cancelled = false;
    private volatile boolean finishedCleanly = false;
    private volatile ScanEvents events = ScanEvents.NONE;

    private static final long PROGRESS_EVERY_NANOS = 250_000_000L;

    public DnsEnumerator(MontoyaApi api) { this.api = api; }

    public DnsEnumerator events(ScanEvents e) { this.events = e == null ? ScanEvents.NONE : e; return this; }

    /** true once scan() ran through the whole wordlist without being stopped */
    public boolean finishedCleanly() { return finishedCleanly; }

    /** safe to call from any thread, before or during scan() */
    public void cancel() {
        cancelled = true;
        ExecutorService p = pool;
        if (p != null) p.shutdownNow();
        HttpEngine e = engine;
        if (e != null) e.cancel();
    }

    private boolean stopped() { return cancelled || Thread.currentThread().isInterrupted(); }

    public void scan(Config cfg, Consumer<DnsResult> onHit, Consumer<String> onStatus) {
        String domain = normalizeDomain(cfg.domain());
        if (cfg.resolver() == Resolver.BURP) {
            scanViaBurp(cfg, domain, onHit, onStatus);
            return;
        }
        DnsResolver resolver = new DnsResolver(m -> api.logging().logToError(m));

        ExecutorService p = Threads.fixedPool("Buster-dns", cfg.threads());
        pool = p;
        if (cancelled) p.shutdownNow();

        Iterator<String> it = cfg.wordlist().words().iterator();
        int found = 0, filtered = 0;
        int[] failures = {0};
        long start = System.nanoTime(), lastProgress = 0, done = 0;
        long total = Math.max(1, cfg.wordlist().estimatedSize());
        try {
            events.log("domain " + domain + " | ~" + cfg.wordlist().estimatedSize() + " words | "
                    + cfg.threads() + " threads | system DNS resolver");
            onStatus.accept("checking " + domain + " for wildcard DNS ...");
            Set<String> wildcardIps = probeWildcard(resolver, domain);
            if (!wildcardIps.isEmpty()) {
                api.logging().logToOutput("[DNS] wildcard detected for " + domain + " -> " + wildcardIps
                        + ", names resolving only to these IPs are filtered");
                events.log("auto-filter: wildcard DNS *." + domain + " -> " + wildcardIps
                        + ", names resolving only to these IPs are hidden");
                events.autoFilterNote("*." + domain, "names resolving only to wildcard IPs " + wildcardIps);
            } else {
                events.log("auto-filter: no wildcard DNS for " + domain + ", nothing filtered");
                events.autoFilterNote("*." + domain, "nothing (no wildcard DNS)");
            }
            onStatus.accept("resolving names under " + domain
                    + (wildcardIps.isEmpty() ? "" : " (wildcard DNS, filtering " + wildcardIps.size() + " IPs)")
                    + " ...");

            CompletionService<List<DnsResult>> cs = new ExecutorCompletionService<>(p);
            int limit = Math.max(1, cfg.threads() * IN_FLIGHT_PER_THREAD);
            int inFlight = 0;
            while (!stopped() && (it.hasNext() || inFlight > 0)) {
                while (inFlight < limit && it.hasNext() && !stopped()) {
                    String host = it.next() + "." + domain;
                    try {
                        cs.submit(() -> resolver.resolve(host));
                        inFlight++;
                    } catch (RejectedExecutionException e) {
                        break;  // pool shut down by cancel()
                    }
                }
                Future<List<DnsResult>> f;
                try {
                    f = cs.poll(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (f == null) continue;
                inFlight--;
                done++;
                long now = System.nanoTime();
                if (now - lastProgress >= PROGRESS_EVERY_NANOS) {
                    lastProgress = now;
                    events.progress(progress(done, total, "resolving names under " + domain, start));
                }

                List<DnsResult> records = result(f, failures);
                if (records.isEmpty()) continue;
                if (!wildcardIps.isEmpty()
                        && records.stream().allMatch(r -> wildcardIps.contains(r.value()))) {
                    filtered++;
                    continue;
                }
                for (DnsResult r : records) { onHit.accept(r); found++; }
            }

            events.progress(progress(done, total, "resolving names under " + domain, start));
            String tail = filtered > 0 ? ", " + filtered + " wildcard filtered" : "";
            String took = " in " + Progress.duration((System.nanoTime() - start) / 1_000_000_000L);
            finishedCleanly = !stopped();
            onStatus.accept((stopped() ? "stopped, " : "done, ") + found + " found" + tail + took);
            events.log((stopped() ? "stopped, " : "done, ") + found + " found" + tail + took);
            api.logging().logToOutput("[DNS] finished, " + found + " found" + tail);
        } finally {
            WordlistSource.close(it);
            p.shutdownNow();
        }
    }

    private void scanViaBurp(Config cfg, String domain, Consumer<DnsResult> onHit, Consumer<String> onStatus) {
        HttpEngine eng = new HttpEngine(api, "dns", cfg.threads(), BURP_TIMEOUT_SECONDS, List.of());
        engine = eng;
        if (cancelled) eng.cancel();

        Iterator<String> it = cfg.wordlist().words().iterator();
        try {
            long start = System.nanoTime();
            long total = Math.max(1, cfg.wordlist().estimatedSize());
            events.log("domain " + domain + " | ~" + cfg.wordlist().estimatedSize() + " words | "
                    + cfg.threads() + " threads | via Burp (HTTP probe)");
            BurpHostProber prober = new BurpHostProber(eng, this::stopped);
            onStatus.accept("checking how Burp answers unknown names under " + domain + " ...");
            Optional<Baseline> baseline = prober.baseline(domain);
            baseline.ifPresent(b -> api.logging().logToOutput("[DNS/Burp] random names answer with "
                    + b.describe() + ", matching answers are filtered"));
            if (baseline.isPresent()) events.autoFilter("*." + domain + " (unknown names)", baseline.get());
            else {
                events.log("auto-filter: unknown names under " + domain + " do not answer, nothing filtered");
                events.autoFilterNote("*." + domain, "nothing (unknown names do not answer)");
            }
            onStatus.accept("probing names under " + domain + " through Burp ...");

            BurpHostProber.Outcome o = prober.scan(domain, it, baseline, onHit,
                    done -> events.progress(progress(done, total, "probing names under " + domain + " through Burp", start)));
            String tail = o.filtered() > 0 ? ", " + o.filtered() + " filtered" : "";
            String took = " in " + Progress.duration((System.nanoTime() - start) / 1_000_000_000L);
            finishedCleanly = !stopped();
            onStatus.accept((stopped() ? "stopped, " : "done, ") + o.found() + " found" + tail + took);
            events.log((stopped() ? "stopped, " : "done, ") + o.found() + " found" + tail + took);
            api.logging().logToOutput("[DNS/Burp] finished, " + o.found() + " found" + tail);
        } finally {
            WordlistSource.close(it);
            eng.shutdown();
        }
    }

    private static Progress progress(long done, long total, String label, long start) {
        double secs = Math.max(0.001, (System.nanoTime() - start) / 1e9);
        return new Progress(done, total, label, done / secs);
    }

    /**
     * a wildcard domain resolves everything, resolve a few random names and
     * return the IPs they map to, empty when the domain is not wildcarded
     * (needs a majority of probes to resolve, so one fluke does not trip it)
     */
    private Set<String> probeWildcard(DnsResolver resolver, String domain) {
        Set<String> ips = new HashSet<>();
        int resolved = 0;
        for (int i = 0; i < WILDCARD_PROBES && !stopped(); i++) {
            List<DnsResult> rs = resolver.resolve("zz-nope-" + Long.toHexString(RANDOM.nextLong()) + "." + domain);
            if (!rs.isEmpty()) resolved++;
            rs.forEach(r -> ips.add(r.value()));
        }
        return resolved * 2 > WILDCARD_PROBES ? ips : Set.of();
    }

    private List<DnsResult> result(Future<List<DnsResult>> f, int[] failures) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (CancellationException e) {
            return List.of();
        } catch (ExecutionException e) {
            if (++failures[0] <= 5) api.logging().logToError("[DNS] lookup failed", e.getCause());
            return List.of();
        }
    }

    /** accepts "target.com", "https://target.com/x", "*.target.com" and returns "target.com" */
    static String normalizeDomain(String raw) {
        if (raw == null) throw new IllegalArgumentException("domain is empty");
        String d = raw.trim().toLowerCase(Locale.ROOT).replaceFirst("^[a-z]+://", "");
        int slash = d.indexOf('/');
        if (slash >= 0) d = d.substring(0, slash);
        d = d.replaceFirst(":\\d+$", "").replaceFirst("^\\*\\.", "").replaceAll("\\.+$", "");
        try {
            d = java.net.IDN.toASCII(d);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid domain: " + raw);
        }
        if (d.isEmpty() || !d.matches("[a-z0-9._-]+"))
            throw new IllegalArgumentException("invalid domain: " + raw);
        return d;
    }
}
