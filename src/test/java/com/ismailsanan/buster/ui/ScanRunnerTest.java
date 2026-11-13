package com.ismailsanan.buster.ui;

import com.ismailsanan.buster.core.Threads;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class ScanRunnerTest {

    @Test
    void workerIsNamedDaemonAndFailuresGoToOnError() throws Exception {
        CompletableFuture<Throwable> error = new CompletableFuture<>();
        ScanRunner runner = new ScanRunner(error::complete);
        CompletableFuture<Thread> worker = new CompletableFuture<>();
        CountDownLatch finished = new CountDownLatch(1);
        runner.start("Buster-test-scan", () -> {}, () -> {
            worker.complete(Thread.currentThread());
            throw new IllegalStateException("boom");
        }, finished::countDown);
        Thread t = worker.get(5, TimeUnit.SECONDS);
        assertEquals("Buster-test-scan", t.getName());
        assertTrue(t.isDaemon());
        assertTrue(finished.await(5, TimeUnit.SECONDS), "onFinish must run even when the body throws");
        assertEquals("boom", error.get(5, TimeUnit.SECONDS).getMessage(), "the failure goes to onError, not stderr");
    }

    @Test
    void cancelAndWaitCancelsInterruptsAndJoins() throws Exception {
        ScanRunner runner = new ScanRunner(t -> {});
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch running = new CountDownLatch(1);
        runner.start("Buster-test-scan", () -> cancelled.set(true), () -> {
            running.countDown();
            try { Thread.sleep(60_000); } catch (InterruptedException e) { interrupted.set(true); }
        }, () -> {});
        assertTrue(running.await(5, TimeUnit.SECONDS));
        long t0 = System.nanoTime();
        runner.cancelAndWait(2000);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 1500);
        assertTrue(cancelled.get());
        assertTrue(interrupted.get());
        assertFalse(runner.isRunning());
    }

    @Test
    void cancelAndWaitIsBoundedForAStuckWorker() throws Exception {
        ScanRunner runner = new ScanRunner(t -> {});
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        runner.start("Buster-test-stuck", () -> {}, () -> {
            running.countDown();
            while (true) {   // ignores interrupts
                try { if (release.await(50, TimeUnit.MILLISECONDS)) return; } catch (InterruptedException ignored) {}
            }
        }, () -> {});
        assertTrue(running.await(5, TimeUnit.SECONDS));
        long t0 = System.nanoTime();
        runner.cancelAndWait(300);
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(ms >= 250 && ms < 2000, "waited " + ms + "ms");
        assertTrue(runner.isRunning());
        release.countDown();
    }

    @Test
    void cancelBeforeStartIsHarmless() {
        ScanRunner runner = new ScanRunner(t -> {});
        runner.cancel();
        runner.cancelAndWait(100);
        assertFalse(runner.isRunning());
    }

    @Test
    void poolThreadsAreNamedDaemons() throws Exception {
        ExecutorService pool = Threads.fixedPool("Buster-x", 2);
        try {
            Thread a = pool.submit(Thread::currentThread).get();
            assertTrue(a.isDaemon());
            assertTrue(a.getName().matches("Buster-x-\\d+"), a.getName());
        } finally {
            pool.shutdownNow();
        }
        AtomicInteger ran = new AtomicInteger();
        Thread t = Threads.startDaemon("Buster-y", ran::incrementAndGet);
        t.join(2000);
        assertTrue(t.isDaemon());
        assertEquals("Buster-y", t.getName());
        assertEquals(1, ran.get());
    }
}
