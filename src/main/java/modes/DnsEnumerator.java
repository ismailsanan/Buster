package modes;

import burp.api.montoya.MontoyaApi;

import core.DnsResolver;
import core.DnsResult;
import core.Threads;
import core.WordlistSource;

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

    public record Config(String domain, WordlistSource wordlist, int threads) {}

    private final MontoyaApi api;
    private volatile ExecutorService pool;
    private volatile boolean cancelled = false;

    public DnsEnumerator(MontoyaApi api) { this.api = api; }

    /** safe to call from any thread, before or during scan() */
    public void cancel() {
        cancelled = true;
        ExecutorService p = pool;
        if (p != null) p.shutdownNow();
    }

    private boolean stopped() { return cancelled || Thread.currentThread().isInterrupted(); }

    public void scan(Config cfg, Consumer<DnsResult> onHit, Consumer<String> onStatus) {
        String domain = normalizeDomain(cfg.domain());
        DnsResolver resolver = new DnsResolver(m -> api.logging().logToError(m));

        ExecutorService p = Threads.fixedPool("Buster-dns", cfg.threads());
        pool = p;
        if (cancelled) p.shutdownNow();

        int found = 0, filtered = 0;
        int[] failures = {0};
        try {
            onStatus.accept("checking " + domain + " for wildcard DNS ...");
            Set<String> wildcardIps = probeWildcard(resolver, domain);
            if (!wildcardIps.isEmpty()) {
                api.logging().logToOutput("[DNS] wildcard detected for " + domain + " -> " + wildcardIps
                        + ", names resolving only to these IPs are filtered");
            }
            onStatus.accept("resolving names under " + domain
                    + (wildcardIps.isEmpty() ? "" : " (wildcard DNS, filtering " + wildcardIps.size() + " IPs)")
                    + " ...");

            CompletionService<List<DnsResult>> cs = new ExecutorCompletionService<>(p);
            int limit = Math.max(1, cfg.threads() * IN_FLIGHT_PER_THREAD);
            int inFlight = 0;
            Iterator<String> it = cfg.wordlist().words().iterator();

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

                List<DnsResult> records = result(f, failures);
                if (records.isEmpty()) continue;
                if (!wildcardIps.isEmpty()
                        && records.stream().allMatch(r -> wildcardIps.contains(r.value()))) {
                    filtered++;
                    continue;
                }
                for (DnsResult r : records) { onHit.accept(r); found++; }
            }

            String tail = filtered > 0 ? ", " + filtered + " wildcard filtered" : "";
            onStatus.accept((stopped() ? "stopped, " : "done, ") + found + " found" + tail);
            api.logging().logToOutput("[DNS] finished, " + found + " found" + tail);
        } finally {
            p.shutdownNow();
        }
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
