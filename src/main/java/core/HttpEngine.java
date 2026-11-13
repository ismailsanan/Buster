package core;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * one shared thread pool per scan
 *
 * the per-request timeout is enforced by Burp itself (RequestOptions), so a
 * slow target frees the worker thread instead of leaving it blocked inside
 * sendRequest, Future.get keeps a slightly longer backstop on top
 *
 * custom headers (cookies, auth) are applied to every request, which is what
 * makes authenticated content discovery work
 *
 * each pooled task reduces its response to a small Probe right away and only
 * keeps the full exchange (moved to a Burp temp file) when the caller's filter
 * says it is a hit, so a batch of large responses never sits in the heap
 */
public class HttpEngine {

    private static final int MAX_LOGGED_ERRORS = 5;
    private static final int BACKSTOP_SECONDS = 5;
    private static final long AWAIT_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(200);

    private final MontoyaApi api;
    private final ExecutorService pool;
    private final int timeoutSeconds;
    private final List<HeaderKV> headers;
    private final RequestOptions options;
    private final AtomicInteger errors = new AtomicInteger();
    private volatile boolean cancelled = false;

    public record HeaderKV(String name, String value) {}

    /** status, body length and Location of one response, exchange is null unless the filter kept it */
    public record Probe(int status, long length, String location, HttpRequestResponse exchange) {}

    @FunctionalInterface
    public interface Filter { boolean keep(int status, long length); }

    public static final Filter KEEP_NONE = (s, l) -> false;

    public HttpEngine(MontoyaApi api, String name, int threads, int timeoutSeconds, List<HeaderKV> headers) {
        this.api = api;
        this.timeoutSeconds = timeoutSeconds;
        this.headers = mergeCookies(headers == null ? List.of() : headers);
        this.options = RequestOptions.requestOptions().withResponseTimeout(timeoutSeconds * 1000L);
        this.pool = Threads.fixedPool("Buster-" + name + "-http", threads);
    }

    public void cancel()         { cancelled = true; pool.shutdownNow(); }
    public boolean isCancelled() { return cancelled; }
    public void shutdown()       { pool.shutdownNow(); }
    public int errorCount()      { return errors.get(); }

    private HttpRequest build(String url) {
        HttpRequest req = HttpRequest.httpRequestFromUrl(url);
        for (HeaderKV h : headers) req = req.withHeader(h.name(), h.value());
        return req;
    }

    public Future<Probe> submit(String url, Filter keep) {
        return submitTask(() -> reduce(api.http().sendRequest(build(url), options), keep));
    }

    public Future<Probe> submit(String url, String hostHeader, Filter keep) {
        return submitTask(() -> reduce(api.http().sendRequest(build(url).withHeader("Host", hostHeader), options), keep));
    }

    private static Probe reduce(HttpRequestResponse rr, Filter keep) {
        if (rr == null || !rr.hasResponse()) return null;
        int status = rr.response().statusCode();
        long length = rr.response().body().length();
        String location = rr.response().headerValue("Location");
        HttpRequestResponse kept = keep.keep(status, length) ? toTempFile(rr) : null;
        return new Probe(status, length, location, kept);
    }

    private static HttpRequestResponse toTempFile(HttpRequestResponse rr) {
        try {
            return rr.copyToTempFile();
        } catch (RuntimeException e) {
            return rr;   // keep the hit in memory rather than lose it
        }
    }

    /** several Cookie lines would overwrite each other via withHeader, join them into one header */
    static List<HeaderKV> mergeCookies(List<HeaderKV> in) {
        List<HeaderKV> out = new java.util.ArrayList<>();
        StringBuilder cookies = new StringBuilder();
        for (HeaderKV h : in) {
            if (h.name().equalsIgnoreCase("Cookie")) {
                if (cookies.length() > 0) cookies.append("; ");
                cookies.append(h.value());
            } else {
                out.add(h);
            }
        }
        if (cookies.length() > 0) out.add(new HeaderKV("Cookie", cookies.toString()));
        return List.copyOf(out);
    }

    private Future<Probe> submitTask(Callable<Probe> task) {
        try {
            return pool.submit(task);
        } catch (RejectedExecutionException e) {
            // pool was shut down by a cancel racing with the producer loop
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * waits for a response, null on no response, timeout, cancellation or send failure
     *
     * waits in short slices so cancel() is honoured promptly: shutdownNow drops
     * queued tasks without completing their futures, a single long get() would
     * otherwise sit out the whole timeout for every queued request
     */
    public Probe await(Future<Probe> f) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds + BACKSTOP_SECONDS);
        try {
            while (true) {
                long left = deadline - System.nanoTime();
                try {
                    return f.get(Math.min(left, AWAIT_SLICE_NANOS), TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    if (cancelled || System.nanoTime() >= deadline) {
                        f.cancel(true);
                        return null;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            f.cancel(true);
            return null;
        } catch (CancellationException e) {
            return null;
        } catch (ExecutionException e) {
            if (!cancelled && errors.incrementAndGet() <= MAX_LOGGED_ERRORS) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                api.logging().logToError("[HTTP] request failed: " + cause.getClass().getSimpleName()
                        + ": " + cause.getMessage()
                        + (errors.get() == MAX_LOGGED_ERRORS ? " (further errors suppressed)" : ""));
            }
            return null;
        }
    }

    public Probe sendOnce(String url) { return await(submit(url, KEEP_NONE)); }
}
