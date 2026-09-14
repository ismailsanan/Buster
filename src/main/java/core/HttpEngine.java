package core;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;

import java.util.List;
import java.util.concurrent.*;

/**
 * one shared thread pool per scan, hard per-request timeout
 *
 * custom headers (cookies, auth) are applied to every request, which is what
 * makes authenticated content discovery work
 */
public class HttpEngine {

    private final MontoyaApi api;
    private final ExecutorService pool;
    private final int timeoutSeconds;
    private final List<HeaderKV> headers;
    private volatile boolean cancelled = false;

    public record HeaderKV(String name, String value) {}

    public HttpEngine(MontoyaApi api, int threads, int timeoutSeconds) {
        this(api, threads, timeoutSeconds, List.of());
    }

    public HttpEngine(MontoyaApi api, int threads, int timeoutSeconds, List<HeaderKV> headers) {
        this.api = api;
        this.timeoutSeconds = timeoutSeconds;
        this.headers = headers == null ? List.of() : headers;
        this.pool = Executors.newFixedThreadPool(threads);
    }

    public void cancel()        { cancelled = true; pool.shutdownNow(); }
    public boolean isCancelled(){ return cancelled; }
    public void shutdown()      { pool.shutdownNow(); }

    private HttpRequest build(String url) {
        HttpRequest req = HttpRequest.httpRequestFromUrl(url);
        for (HeaderKV h : headers) req = req.withHeader(h.name(), h.value());
        return req;
    }

    public Future<HttpRequestResponse> submit(String url) {
        return pool.submit(() -> api.http().sendRequest(build(url)));
    }

    public Future<HttpRequestResponse> submit(String url, String hostHeader) {
        return pool.submit(() -> api.http().sendRequest(build(url).withHeader("Host", hostHeader)));
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
