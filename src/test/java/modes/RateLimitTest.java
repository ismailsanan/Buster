package modes;

import core.EnumResult;
import core.UrlUtil;
import core.WordlistSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import testutil.FakeBurp;
import testutil.FakeBurp.Resp;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** rate limiting and 429 backoff, end to end through DirEnumerator */
@Timeout(60)
class RateLimitTest {

    private static List<EnumResult> scan(FakeBurp burp, List<String> words, int rps) {
        List<EnumResult> hits = new CopyOnWriteArrayList<>();
        new DirEnumerator(burp.api).scan(new DirEnumerator.Config("https://h", WordlistSource.ofList(words),
                List.of(""), 0, 4, false, false, List.of(), rps), hits::add, s -> {});
        return hits;
    }

    @Test
    void transient429IsRetriedAndTheRealResponseReported() {
        Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        FakeBurp burp = new FakeBurp((url, host) -> {
            String path = UrlUtil.pathOf(url);
            int n = calls.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            if (path.equals("/admin")) return n <= 2 ? Resp.of(429, 5) : Resp.of(200, 50);
            return Resp.of(404, 10);
        });
        List<EnumResult> hits = scan(burp, List.of("admin", "nothing"), 0);
        assertEquals(1, hits.size(), hits.toString());
        assertEquals(200, hits.get(0).status());
        assertEquals(3, calls.get("/admin").get());
    }

    @Test
    void persistent429IsNeverReportedAsAHit() {
        FakeBurp burp = new FakeBurp((url, host) ->
                UrlUtil.pathOf(url).equals("/limited") ? Resp.of(429, 5) : Resp.of(404, 10));
        List<EnumResult> hits = scan(burp, List.of("limited", "other"), 0);
        assertTrue(hits.isEmpty(), "rate limit response reported: " + hits);
    }

    @Test
    void requestsPerSecondIsHonoured() {
        FakeBurp burp = new FakeBurp((url, host) -> Resp.of(404, 10));
        long start = System.nanoTime();
        scan(burp, List.of("a", "b", "c", "d", "e", "f", "g", "h"), 20);   // 3 probes + 8 words, 50ms apart
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ms >= 450, "11 requests at 20/s finished in " + ms + "ms");
    }
}
