package com.ismailsanan.buster.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ThrottleTest {

    @Test
    void unlimitedNeverWaits() throws InterruptedException {
        Throttle t = Throttle.unlimited();
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) t.acquire();
        assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(200));
        assertEquals(0, t.intervalNanos());
    }

    @Test
    void rateLimitSpacesRequests() throws InterruptedException {
        Throttle t = new Throttle(50);   // 20ms apart
        long start = System.nanoTime();
        for (int i = 0; i < 11; i++) t.acquire();
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(ms >= 180, "10 intervals of 20ms took only " + ms + "ms");
    }

    @Test
    void backoffGrowsCapsAndDecays() {
        Throttle t = Throttle.unlimited();
        assertFalse(t.backingOff());
        t.onThrottled();
        assertEquals(Throttle.INITIAL_PENALTY, t.intervalNanos());
        t.onThrottled();
        assertEquals(Throttle.INITIAL_PENALTY * 2, t.intervalNanos());
        for (int i = 0; i < 20; i++) t.onThrottled();
        assertEquals(Throttle.MAX_PENALTY, t.intervalNanos());
        for (int i = 0; i < 200; i++) t.onSuccess();
        assertFalse(t.backingOff());
        assertEquals(0, t.intervalNanos());
    }

    @Test
    void rateLimitIsFloorDuringBackoffDecay() {
        Throttle t = new Throttle(2);   // 500ms
        t.onThrottled();                // 250ms penalty < 500ms floor
        assertEquals(TimeUnit.MILLISECONDS.toNanos(500), t.intervalNanos());
    }

    @Test
    void pushBackStatuses() {
        assertTrue(Throttle.isPushBack(429, false));
        assertTrue(Throttle.isPushBack(503, true));
        assertFalse(Throttle.isPushBack(503, false));   // a plain 503 catch-all must not stall scans
        assertFalse(Throttle.isPushBack(200, true));
    }
}
