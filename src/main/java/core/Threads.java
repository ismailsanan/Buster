package core;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** named daemon threads, so a stuck worker can never keep Burp from exiting and shows up clearly in a thread dump */
public final class Threads {

    private Threads() {}

    public static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    public static ExecutorService fixedPool(String prefix, int threads) {
        return Executors.newFixedThreadPool(threads, daemonFactory(prefix));
    }

    public static Thread startDaemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
        return t;
    }
}
