package modes;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;

import core.*;

import java.util.*;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * virtual host enumeration
 * sends the same request to one target but varies the Host header, a real
 * vhost is one whose response differs from a baseline captured with a
 * garbage Host value
 * wildcard vhost detection is built in, if the server returns the SAME
 * response for the garbage baseline as for real candidates, every candidate
 * would look like a hit, so we only report a candidate whose body differs
 */
public class VhostEnumerator {

    private final MontoyaApi api;
    private HttpEngine engine;

    public VhostEnumerator(MontoyaApi api) { this.api = api; }

    public void cancel() { if (engine != null) engine.cancel(); }

    /**
     * @param target    IP or hostname to connect to, e.g. https://10.0.0.5
     * @param domain    base domain appended to each word, e.g. "target.com"
     *                  each candidate Host becomes word + "." + domain
     * @param wordlist  subdomain words
     */
    public void scan(
            String target,
            String domain,
            List<String> wordlist,
            int threads,
            Consumer<EnumResult> onHit,
            Consumer<String> onStatus) {

        engine = new HttpEngine(api, threads, 6);
        String url = normalize(target);

        // baseline with a Host that cannot exist
        Baseline baseline = new BaselineDetector(engine).forVhost(url);
        onStatus.accept("scanning " + url + " with " + wordlist.size() + " hosts ...");
        api.logging().logToOutput("[VHOST] baseline status=" + baseline.status()
                + " length=" + baseline.length());

        // dispatch every candidate Host at once, then gather
        List<String> hosts = new ArrayList<>();
        List<Future<HttpRequestResponse>> futures = new ArrayList<>();

        for (String word : wordlist) {
            String host = domain.isBlank() ? word : word + "." + domain;
            hosts.add(host);
            futures.add(engine.submit(url, host));
        }

        for (int i = 0; i < futures.size(); i++) {
            if (engine.isCancelled()) break;
            HttpRequestResponse r = engine.await(futures.get(i));
            if (r == null || !r.hasResponse()) continue;

            int status = r.response().statusCode();
            long length = r.response().body().length();

            // a real vhost differs from the garbage baseline
            if (!baseline.differsFrom(status, length)) continue;

            onHit.accept(EnumResult.http(hosts.get(i), "", status, length, "vhost", r));
        }

        engine.shutdown();
        onStatus.accept(engine.isCancelled() ? "stopped" : "done");
        api.logging().logToOutput("[VHOST] finished");
    }

    private String normalize(String url) {
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
        return u;
    }
}