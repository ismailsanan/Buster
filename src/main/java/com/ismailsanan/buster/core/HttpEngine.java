package com.ismailsanan.buster.core;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * one shared thread pool per scan
 *
 * the per-request timeout is enforced by Burp itself (RequestOptions), so a
 * slow target frees the worker thread instead of leaving it blocked inside
 * sendRequest, await() keeps a slightly longer backstop on top
 *
 * the backstop is measured from the moment the request is actually handed to
 * Burp, NOT from when the caller starts waiting: a request that is still queued
 * behind other work, or held back by the rate limit / 429 backoff, is not
 * "late" and must not be dropped. A request still queued is only given up on
 * when the whole engine has stalled (no response at all for STALL_SECONDS),
 * which is what a dead or blackholing target looks like
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
    private static final int MAX_PUSHBACK_RETRIES = 2;

    private final MontoyaApi api;
    private final ExecutorService pool;
    private final int timeoutSeconds;
    private final long backstopNanos;
    private final long stallNanos;
    private final List<HeaderKV> headers;
    private final RequestOptions options;
    private final AtomicInteger errors = new AtomicInteger();
    private final AtomicInteger pushBacks = new AtomicInteger();
    private final AtomicInteger timeouts = new AtomicInteger();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong lastProgress = new AtomicLong(System.nanoTime());
    private final Throttle throttle;
    private volatile boolean cancelled = false;

    public record HeaderKV(String name, String value) {}

    /**
     * status, body length and Location of one response, exchange is null unless
     * the filter kept it; followUp is the answer to the optional follow-up request
     */
    public record Probe(int status, long length, String location, HttpRequestResponse exchange, Probe followUp) {
        public Probe(int status, long length, String location, HttpRequestResponse exchange) {
            this(status, length, location, exchange, null);
        }
    }

    @FunctionalInterface
    public interface Filter { boolean keep(int status, long length); }

    public static final Filter KEEP_NONE = (s, l) -> false;
    public static final Filter KEEP_ALL = (s, l) -> true;

    public HttpEngine(MontoyaApi api, String name, int threads, int timeoutSeconds, List<HeaderKV> headers) {
        this(api, name, threads, timeoutSeconds, headers, 0);
    }

    /** requestsPerSecond 0 = unlimited, 429/503 backoff applies either way */
    public HttpEngine(MontoyaApi api, String name, int threads, int timeoutSeconds, List<HeaderKV> headers,
                      int requestsPerSecond) {
        this(api, name, threads, timeoutSeconds, headers, requestsPerSecond, 0);
    }

    /** stallSeconds 0 = default (three times the request timeout plus the backstop) */
    public HttpEngine(MontoyaApi api, String name, int threads, int timeoutSeconds, List<HeaderKV> headers,
                      int requestsPerSecond, int stallSeconds) {
        this.api = api;
        this.throttle = new Throttle(requestsPerSecond);
        this.timeoutSeconds = timeoutSeconds;
        this.backstopNanos = TimeUnit.SECONDS.toNanos(timeoutSeconds + BACKSTOP_SECONDS);
        this.stallNanos = stallSeconds > 0 ? TimeUnit.SECONDS.toNanos(stallSeconds)
                : TimeUnit.SECONDS.toNanos(3L * timeoutSeconds + BACKSTOP_SECONDS);
        this.headers = mergeCookies(headers == null ? List.of() : headers);
        this.options = RequestOptions.requestOptions().withResponseTimeout(timeoutSeconds * 1000L);
        this.pool = Threads.fixedPool("Buster-" + name + "-http", threads);
    }

    public void cancel()         { cancelled = true; pool.shutdownNow(); }
    public boolean isCancelled() { return cancelled; }
    public void shutdown()       { pool.shutdownNow(); }
    public int errorCount()      { return errors.get(); }
    /** responses where the target pushed back (429, or 503 with Retry-After) */
    public int pushBackCount()   { return pushBacks.get(); }
    /** requests given up on because Burp did not return them in time */
    public int timeoutCount()    { return timeouts.get(); }
    /** requests that came back from Burp (answered or not), drives the progress display */
    public long completedCount() { return completed.get(); }
    public boolean backingOff()  { return throttle.backingOff(); }

    /** no request has come back from Burp for the stall window: the target (or the route to it) is dead */
    public boolean stalled() {
        return System.nanoTime() - lastProgress.get() > stallNanos;
    }

    private HttpRequest build(String url) {
        HttpRequest req = HttpRequest.httpRequestFromUrl(url);
        for (HeaderKV h : headers) req = req.withHeader(h.name(), h.value());
        return req;
    }

    public Future<Probe> submit(String url, Filter keep) {
        return submitTask(t -> reduce(send(build(url), t), keep));
    }

    public Future<Probe> submit(String url, String hostHeader, Filter keep) {
        return submitTask(t -> reduce(send(build(url).withHeader("Host", hostHeader), t), keep));
    }

    /**
     * like submit, then, inside the same pooled task, sends followUp(answer) when it
     * returns a URL: the second request runs in parallel with the rest of the batch
     * instead of serially on the thread draining the results
     */
    public Future<Probe> submit(String url, Filter keep, Function<Probe, String> followUp) {
        return submitTask(t -> {
            Probe first = reduce(send(build(url), t), keep);
            if (first == null || cancelled) return first;
            String next = followUp.apply(first);
            if (next == null) return first;
            Probe second = reduce(send(build(next), t), KEEP_NONE);
            return new Probe(first.status(), first.length(), first.location(), first.exchange(), second);
        });
    }

    /**
     * paced send, retried with growing backoff when the target pushes back;
     * null if it is still pushing back after the retries, so a rate-limit
     * response is never mistaken for a hit
     */
    private HttpRequestResponse send(HttpRequest req, Task task) throws InterruptedException {
        HttpRequestResponse rr;
        for (int attempt = 0; attempt <= MAX_PUSHBACK_RETRIES && !cancelled; attempt++) {
            throttle.acquire();
            task.sendingSince = System.nanoTime();
            try {
                rr = api.http().sendRequest(req, options);
            } finally {
                task.sendingSince = 0;
                completed.incrementAndGet();
            }
            // only a request that came back counts as life; an interrupted or failed send does not
            lastProgress.set(System.nanoTime());
            if (rr == null || !rr.hasResponse()) return rr;
            boolean retryAfter = rr.response().headerValue("Retry-After") != null;
            if (!Throttle.isPushBack(rr.response().statusCode(), retryAfter)) {
                throttle.onSuccess();
                return rr;
            }
            pushBacks.incrementAndGet();
            throttle.onThrottled();
        }
        return null;
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

    @FunctionalInterface
    private interface Body { Probe run(Task self) throws Exception; }

    /** a pooled request that knows when it was handed to Burp, so await can time it fairly */
    private static final class Task extends FutureTask<Probe> {
        /** System.nanoTime() when the current attempt went to Burp, 0 while queued / throttled */
        volatile long sendingSince;

        Task(Body body, Task[] self) {
            super(() -> body.run(self[0]));
        }
    }

    private Future<Probe> submitTask(Body body) {
        Task[] self = new Task[1];
        Task task = new Task(body, self);
        self[0] = task;
        try {
            pool.execute(task);
            return task;
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
        Task task = f instanceof Task t ? t : null;
        long fallbackDeadline = System.nanoTime() + backstopNanos;
        try {
            while (true) {
                try {
                    return f.get(AWAIT_SLICE_NANOS, TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    if (cancelled || overdue(task, fallbackDeadline)) {
                        f.cancel(true);
                        if (!cancelled) timeouts.incrementAndGet();
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

    private boolean overdue(Task task, long fallbackDeadline) {
        long now = System.nanoTime();
        if (task == null) return now >= fallbackDeadline;
        long since = task.sendingSince;
        if (since != 0) return now - since >= backstopNanos;   // Burp has it and is too slow
        return stalled();                                         // queued or paced: only a dead engine
    }

    public Probe sendOnce(String url) { return await(submit(url, KEEP_NONE)); }

    /** the request timeout this engine was built with, for status messages */
    public int timeoutSeconds() { return timeoutSeconds; }
}
