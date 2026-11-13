package com.ismailsanan.buster.scan;

import com.ismailsanan.buster.core.DnsResult;
import com.ismailsanan.buster.core.WordlistSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import com.ismailsanan.buster.testutil.FakeBurp;

import java.io.IOException;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DNS mode against a hosts file: jdk.net.hosts.file makes InetAddress answer
 * from a file instead of the OS resolver, so this runs offline and fast
 * (src/test/resources/dns-hosts.txt, wired up in pom.xml)
 */
@Timeout(60)
class DnsEnumeratorTest {

    private static final String DOMAIN = "buster-test.example";
    private static final int NAMES = 300;
    private static boolean hostsFileActive;

    static {
        // Maven sets jdk.net.hosts.file from pom.xml at JVM start; when run from an IDE, point it at the same
        // resource here (works only if nothing resolved a name earlier in this JVM, otherwise the tests skip)
        if (System.getProperty("jdk.net.hosts.file") == null) {
            java.net.URL res = DnsEnumeratorTest.class.getResource("/dns-hosts.txt");
            if (res != null) System.setProperty("jdk.net.hosts.file", res.getPath());
        }
    }

    @BeforeAll
    static void checkHostsFile() {
        try {
            hostsFileActive = InetAddress.getByName("w0." + DOMAIN).getHostAddress().equals("10.0.0.1");
        } catch (IOException e) {
            hostsFileActive = false;
        }
    }

    /** wordlist that records how many words the scan has pulled */
    private static WordlistSource counting(List<String> words, AtomicInteger pulled) {
        return WordlistSource.ofList(new AbstractList<>() {
            public String get(int i) { return words.get(i); }
            public int size() { return words.size(); }
            public Iterator<String> iterator() {
                Iterator<String> it = words.iterator();
                return new Iterator<>() {
                    public boolean hasNext() { return it.hasNext(); }
                    public String next() { pulled.incrementAndGet(); return it.next(); }
                };
            }
        });
    }

    @Test
    void boundedNumberOfLookupsInFlight() {
        assumeTrue(hostsFileActive, "jdk.net.hosts.file not honoured in this JVM");
        List<String> words = new ArrayList<>();
        for (int i = 0; i < NAMES; i++) words.add("w" + i);
        AtomicInteger pulled = new AtomicInteger();
        int threads = 2, limit = threads * 4;

        List<DnsResult> hits = new CopyOnWriteArrayList<>();
        List<String> overshoot = new CopyOnWriteArrayList<>();
        List<String> status = new CopyOnWriteArrayList<>();
        new DnsEnumerator(new FakeBurp((u, h) -> null).api)
                .scan(new DnsEnumerator.Config(DOMAIN, counting(words, pulled), threads), r -> {
                    hits.add(r);
                    // each hit is one completed lookup (one A record per name), at most `limit` may be outstanding
                    if (pulled.get() > limit + hits.size() - 1) overshoot.add(pulled.get() + "/" + hits.size());
                }, status::add);

        assertEquals(NAMES, hits.size());
        assertTrue(overshoot.isEmpty(), "more than " + limit + " lookups in flight: " + overshoot);
        assertTrue(status.get(status.size() - 1).startsWith("done, " + NAMES + " found"), status.toString());
    }

    @Test
    void missingNamesAreNotReported() {
        assumeTrue(hostsFileActive, "jdk.net.hosts.file not honoured in this JVM");
        List<DnsResult> hits = new CopyOnWriteArrayList<>();
        new DnsEnumerator(new FakeBurp((u, h) -> null).api).scan(new DnsEnumerator.Config(DOMAIN,
                WordlistSource.ofList(List.of("nope1", "www", "nope2", "w7")), 3), hits::add, s -> {});
        Set<String> got = new TreeSet<>();
        for (DnsResult r : hits) { got.add(r.host() + "=" + r.value()); assertEquals("A", r.type()); }
        assertEquals(new TreeSet<>(List.of("www." + DOMAIN + "=10.9.9.9", "w7." + DOMAIN + "=10.0.0.8")), got);
    }

    @Test
    void streamsInsteadOfSubmittingEverything() {
        assumeTrue(hostsFileActive, "jdk.net.hosts.file not honoured in this JVM");
        List<String> words = new ArrayList<>();
        for (int i = 0; i < NAMES; i++) words.add("w" + i);
        AtomicInteger pulled = new AtomicInteger();
        List<Integer> pulledAtFirstHit = new ArrayList<>();
        FakeBurp burp = new FakeBurp((u, h) -> null);
        new DnsEnumerator(burp.api).scan(new DnsEnumerator.Config(DOMAIN, counting(words, pulled), 1),
                r -> { if (pulledAtFirstHit.isEmpty()) pulledAtFirstHit.add(pulled.get()); }, s -> {});
        assertEquals(1, pulledAtFirstHit.size());
        assertTrue(pulledAtFirstHit.get(0) <= 4, "pulled " + pulledAtFirstHit.get(0) + " words before the first result");
    }

    @Test
    void emptyWordlistFinishes() {
        assumeTrue(hostsFileActive, "jdk.net.hosts.file not honoured in this JVM");
        List<String> status = new ArrayList<>();
        new DnsEnumerator(new FakeBurp((u, h) -> null).api)
                .scan(new DnsEnumerator.Config(DOMAIN, WordlistSource.ofList(List.of()), 4), r -> fail(), status::add);
        assertEquals("done, 0 found", status.get(status.size() - 1));
    }

    @Test
    void cancelBeforeScanSticks() {
        assumeTrue(hostsFileActive, "jdk.net.hosts.file not honoured in this JVM");
        AtomicInteger pulled = new AtomicInteger();
        List<String> status = new ArrayList<>();
        DnsEnumerator e = new DnsEnumerator(new FakeBurp((u, h) -> null).api);
        e.cancel();
        e.scan(new DnsEnumerator.Config(DOMAIN, counting(List.of("w1", "w2"), pulled), 2), r -> fail(), status::add);
        assertEquals(0, pulled.get());
        assertTrue(status.get(status.size() - 1).startsWith("stopped"), status.toString());
    }
}
