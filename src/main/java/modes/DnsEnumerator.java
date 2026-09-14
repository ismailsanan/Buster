package modes;

import burp.api.montoya.MontoyaApi;

import core.DnsResolver;
import core.DnsResult;
import core.WordlistSource;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * active subdomain enumeration via DNS resolution
 *
 * submits every lookup, then gathers them, the pool is shut down with
 * awaitTermination AFTER all results are collected so nothing is killed
 * mid-flight, which was dropping results and desyncing the count
 */
public class DnsEnumerator {

    private final MontoyaApi api;
    private ExecutorService pool;
    private volatile boolean cancelled = false;

    public DnsEnumerator(MontoyaApi api) { this.api = api; }

    public void cancel() {
        cancelled = true;
        if (pool != null) pool.shutdownNow();
    }

    public void scan(String domain, WordlistSource wordlist, int threads,
                     Consumer<DnsResult> onHit, Consumer<String> onStatus) {
        cancelled = false;
        pool = Executors.newFixedThreadPool(threads);
        DnsResolver.debug = m -> api.logging().logToOutput(m);

        // wildcard probe: only treat as wildcard if a clearly bogus name resolves
        boolean wildcard = probeWildcard(domain);
        if (wildcard)
            onStatus.accept("WARNING wildcard DNS, results may be false positives");

        // sanity probe: resolve the base domain itself, it must work
        java.util.List<DnsResult> probe = DnsResolver.resolve(domain);
        api.logging().logToOutput("[DNS] probe " + domain + " -> "
                + probe.size() + " record(s)");

        onStatus.accept("resolving names under " + domain + " ...");

        // submit all lookups, keep name alongside each future
        List<String> names = new ArrayList<>();
        List<Future<List<DnsResult>>> futures = new ArrayList<>();

        for (String word : wordlist.words()) {
            if (cancelled) break;
            String host = word + "." + domain;
            names.add(host);
            futures.add(pool.submit(() -> DnsResolver.resolve(host)));
        }

        api.logging().logToOutput("[DNS] submitted " + futures.size() + " lookups");

        // gather every result, this blocks until each future is done or times out
        int found = 0;
        for (int i = 0; i < futures.size() && !cancelled; i++) {
            List<DnsResult> results = await(futures.get(i));
            for (DnsResult r : results) {
                if (wildcard) r = new DnsResult(r.host(), r.type(), r.value(), "wildcard, likely false");
                onHit.accept(r);
                found++;
            }
        }

        pool.shutdownNow();
        onStatus.accept(cancelled ? "stopped, " + found + " found"
                : "done, " + found + " found");
        api.logging().logToOutput("[DNS] finished, " + found + " found");
    }

    // a wildcard domain resolves EVERYTHING, test a couple of random names,
    // both must resolve to call it wildcard, avoids a single fluke tripping it
    private boolean probeWildcard(String domain) {
        boolean a = !DnsResolver.resolve("zzz-nope-a-" + System.nanoTime() + "." + domain).isEmpty();
        boolean b = !DnsResolver.resolve("zzz-nope-b-" + System.nanoTime() + "." + domain).isEmpty();
        return a && b;
    }

    private List<DnsResult> await(Future<List<DnsResult>> f) {
        try {
            return f.get(6, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            return List.of();
        } catch (Exception e) {
            return List.of();
        }
    }
}