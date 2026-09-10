package core;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;

import java.util.concurrent.*;

/**
 * one shared thread pool per scan, hard per request timeout
 *
 * dispatch many, gather many, never a fresh executor per request
 * Montoya sendRequest cannot be interrupted so the timeout stops us waiting,
 * the request may still finish in the background
 *
 * submit(url)             normal request (dir)
 * submit(url, hostHeader) Host header differs from the connection target
 *                         (vhost) which external gobuster cannot do cleanly
 */
public class HttpEngine {

    private final MontoyaApi api;
    private final ExecutorService pool;
    private final int timeoutSeconds;
    private volatile boolean cancelled = false;

    public HttpEngine(MontoyaApi api, int threads, int timeoutSeconds) {
        this.api = api;
        this.timeoutSeconds = timeoutSeconds;
        this.pool = Executors.newFixedThreadPool(threads);
    }

    public void cancel()        { cancelled = true; pool.shutdownNow(); }
    public boolean isCancelled(){ return cancelled; }
    public void shutdown()      { pool.shutdownNow(); }

    public Future<HttpRequestResponse> submit(String url) {
        return pool.submit(() -> api.http().sendRequest(HttpRequest.httpRequestFromUrl(url)));
    }

    public Future<HttpRequestResponse> submit(String url, String hostHeader) {
        return pool.submit(() -> {
            HttpRequest req = HttpRequest.httpRequestFromUrl(url).withHeader("Host", hostHeader);
            return api.http().sendRequest(req);
        });
    }

    public HttpRequestResponse await(Future<HttpRequestResponse> f) {
        try {
            return f.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public HttpRequestResponse sendOnce(String url)              { return await(submit(url)); }
    public HttpRequestResponse sendOnce(String url, String host) { return await(submit(url, host)); }
}
