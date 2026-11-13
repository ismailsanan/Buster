package ui;

import core.Threads;

/**
 * owns the background thread of one mode panel, so Stop and extension unload
 * can always reach the running scan, even while it is still starting up
 */
final class ScanRunner {

    private volatile Thread worker;
    private volatile Runnable canceller;

    boolean isRunning() {
        Thread t = worker;
        return t != null && t.isAlive();
    }

    void start(String threadName, Runnable canceller, Runnable body, Runnable onFinish) {
        this.canceller = canceller;
        worker = Threads.startDaemon(threadName, () -> {
            try {
                body.run();
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
