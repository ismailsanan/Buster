package modes;

import burp.api.montoya.MontoyaApi;

import core.EnumResult;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * active subdomain enumeration by DNS resolution
 *
 * this is the one mode that does NOT use HttpEngine, because it is a
 * resolution problem not an HTTP one, InetAddress.getByName either resolves
 * the name or throws, a resolved name means the subdomain exists
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


    public void scan(String domain, List<String> wordlist, int threads, Consumer<EnumResult> onHit) {
        cancelled = false;
        pool = Executors.newFixedThreadPool(threads);

        List<String> names = new java.util.ArrayList<>();
        List<Future<String>> futures = new java.util.ArrayList<>();

        for (String word : wordlist) {
            String host = word + "." + domain;
            names.add(host);
            futures.add(pool.submit(() -> resolve(host)));
        }

        for (int i = 0; i < futures.size(); i++) {
            if (cancelled) break;
            String resolved = awaitResolve(futures.get(i));
            if (resolved != null)
                onHit.accept(EnumResult.dns(names.get(i), resolved));
        }

        pool.shutdownNow();
        api.logging().logToOutput("[DNS] finished");
    }

    // returns the resolved IP(s) as a comma list, or null if the name does not resolve
    private String resolve(String host) {
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            StringBuilder sb = new StringBuilder();
            for (InetAddress a : addrs) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(a.getHostAddress());
            }
            return sb.toString();
        } catch (Exception e) {
            return null; // NXDOMAIN or resolution failure
        }
    }

    private String awaitResolve(Future<String> f) {
        try {
            return f.get(4, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
