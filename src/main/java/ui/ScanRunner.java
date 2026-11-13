package ui;

import core.Threads;

import java.util.function.Consumer;

/**
 * owns the background thread of one mode panel, so Stop and extension unload
 * can always reach the running scan, even while it is still starting up
 */
final class ScanRunner {

    private final Consumer<Throwable> onError;

    private volatile Thread worker;
    private volatile Runnable canceller;

    /** onError receives anything the scan body throws, so a failure is never lost to stderr */
    ScanRunner(Consumer<Throwable> onError) {
        this.onError = onError;
    }

    boolean isRunning() {
        Thread t = worker;
        return t != null && t.isAlive();
    }

    void start(String threadName, Runnable canceller, Runnable body, Runnable onFinish) {
        this.canceller = canceller;
        worker = Threads.startDaemon(threadName, () -> {
            try {
                body.run();
            } catch (Throwable t) {
                try { onError.accept(t); } catch (Throwable ignored) {}
            } finally {
                onFinish.run();
            }
        });
    }

    void cancel() {
        Runnable c = canceller;
        if (c != null) c.run();
    }

    /** cancel and give the worker a bounded time to exit, used on extension unload */
    void cancelAndWait(long millis) {
        cancel();
        Thread t = worker;
        if (t == null) return;
        t.interrupt();
        try {
            t.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
