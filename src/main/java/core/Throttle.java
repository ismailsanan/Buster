package core;

import java.util.concurrent.TimeUnit;

/**
 * shared pacing for one scan
 *
 *   rate limit  optional fixed requests per second (0 = unlimited)
 *   backoff     when the target answers 429/503 the spacing between requests
 *               grows (250ms, doubling up to 5s), and it decays again as
 *               normal responses come back
 *
 * the effective spacing is the larger of the two, applied across all threads
 */
public final class Throttle {

    static final long INITIAL_PENALTY = TimeUnit.MILLISECONDS.toNanos(250);
    static final long MAX_PENALTY = TimeUnit.SECONDS.toNanos(5);
    static final long MIN_PENALTY = TimeUnit.MILLISECONDS.toNanos(10);

    private final long minIntervalNanos;
    private long nextSlot = 0;
    private long penaltyNanos = 0;

    public Throttle(int requestsPerSecond) {
        this.minIntervalNanos = requestsPerSecond <= 0 ? 0 : TimeUnit.SECONDS.toNanos(1) / requestsPerSecond;
    }

    public static Throttle unlimited() { return new Throttle(0); }

    /** blocks until this caller may send, interruptible so cancel still works */
    public void acquire() throws InterruptedException {
        long wait;
        synchronized (this) {
            long interval = intervalNanos();
            if (interval == 0) return;
            long now = System.nanoTime();
            long slot = Math.max(now, nextSlot);
            nextSlot = slot + interval;
            wait = slot - now;
        }
        if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
    }

    /** target pushed back (429/503), slow everyone down */
    public synchronized void onThrottled() {
        penaltyNanos = penaltyNanos == 0 ? INITIAL_PENALTY : Math.min(penaltyNanos * 2, MAX_PENALTY);
    }

    /** normal response, ease the backoff off gradually */
    public synchronized void onSuccess() {
        if (penaltyNanos == 0) return;
        penaltyNanos = penaltyNanos * 9 / 10;
        if (penaltyNanos < MIN_PENALTY) penaltyNanos = 0;
    }

    public synchronized long intervalNanos() {
        return Math.max(minIntervalNanos, penaltyNanos);
    }

    public synchronized boolean backingOff() { return penaltyNanos > 0; }

    /**
     * 429 always means slow down, 503 only when it carries Retry-After, so a
     * site whose catch-all page is a plain 503 does not stall the whole scan
     */
    public static boolean isPushBack(int status, boolean hasRetryAfter) {
        return status == 429 || (status == 503 && hasRetryAfter);
    }
}
