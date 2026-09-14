package modes;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;

import core.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * virtual host enumeration, varies the Host header against one target
 * streams the wordlist in bounded batches
 */
public class VhostEnumerator {

    private static final int BATCH = 500;

    private final MontoyaApi api;
    private HttpEngine engine;

    public VhostEnumerator(MontoyaApi api) { this.api = api; }

    public void cancel() { if (engine != null) engine.cancel(); }

    public void scan(String target, String domain, WordlistSource wordlist, int threads,
                     Consumer<EnumResult> onHit, Consumer<String> onStatus) {

        engine = new HttpEngine(api, threads, 6);
        String url = normalize(target);

        Baseline baseline = new BaselineDetector(engine).forVhost(url);
        onStatus.accept("scanning " + url + " (~" + wordlist.estimatedSize() + " hosts) ...");
        api.logging().logToOutput("[VHOST] baseline status=" + baseline.status()
                + " len=" + baseline.length());

        List<String> hosts = new ArrayList<>(BATCH);
        List<Future<HttpRequestResponse>> futures = new ArrayList<>(BATCH);
        int found = 0;

        Iterator<String> it = wordlist.words().iterator();
        while (it.hasNext() && !engine.isCancelled()) {
            String word = it.next();
            String host = domain.isBlank() ? word : word + "." + domain;
            hosts.add(host);
            futures.add(engine.submit(url, host));
            if (hosts.size() >= BATCH || !it.hasNext()) {
                found += drain(hosts, futures, baseline, onHit);
                hosts.clear();
                futures.clear();
            }
        }

        engine.shutdown();
        onStatus.accept(engine.isCancelled() ? "stopped, " + found + " found"
                                             : "done, " + found + " found");
        api.logging().logToOutput("[VHOST] finished, " + found + " found");
    }

    private int drain(List<String> hosts, List<Future<HttpRequestResponse>> futures,
                      Baseline baseline, Consumer<EnumResult> onHit) {
        int found = 0;
        for (int i = 0; i < futures.size(); i++) {
            if (engine.isCancelled()) break;
            HttpRequestResponse r = engine.await(futures.get(i));
            if (r == null || !r.hasResponse()) continue;
            int status = r.response().statusCode();
            long length = r.response().body().length();
            if (!baseline.differsFrom(status, length)) continue;
            onHit.accept(EnumResult.http(hosts.get(i), "", status, length, "vhost", r));
            found++;
        }
        return found;
    }

    private String normalize(String url) {
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
        return u;
    }
}
