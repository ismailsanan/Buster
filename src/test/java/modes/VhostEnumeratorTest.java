package modes;

import core.EnumResult;
import core.HttpEngine;
import core.WordlistSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import testutil.FakeBurp;
import testutil.FakeBurp.Resp;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class VhostEnumeratorTest {

    private final List<EnumResult> hits = new CopyOnWriteArrayList<>();
    private final List<String> status = new CopyOnWriteArrayList<>();

    private String last() { return status.isEmpty() ? "" : status.get(status.size() - 1); }

    private void scan(VhostEnumerator e, String domain, List<String> words, List<HttpEngine.HeaderKV> headers) {
        e.scan(new VhostEnumerator.Config("https://10.0.0.1:8443", domain, WordlistSource.ofList(words), 4, headers),
                hits::add, status::add);
    }

    @Test
    void wildcardVhostOnlyReportsDifferentBodies() {
        FakeBurp burp = new FakeBurp((url, host) -> "dev.example.com".equals(host) ? Resp.of(200, 500) : Resp.of(200, 100));
        scan(new VhostEnumerator(burp.api), "example.com", List.of("www", "dev", "test"), List.of());
        assertEquals(1, hits.size());
        assertEquals("dev.example.com", hits.get(0).name());
        assertNotNull(hits.get(0).exchange());
        assertTrue(last().startsWith("done, 1 found"), last());
        // calibration probes carry an .invalid Host
        assertEquals(3, burp.sent.stream().filter(s -> s.header("Host").endsWith(".invalid")).count());
    }

    @Test
    void userHostHeaderIsDroppedOtherHeadersKept() {
        FakeBurp burp = new FakeBurp((url, host) -> Resp.of(404, 10));
        scan(new VhostEnumerator(burp.api), "", List.of("intranet"),
                List.of(new HttpEngine.HeaderKV("host", "evil"), new HttpEngine.HeaderKV("Cookie", "s=1")));
        FakeBurp.Sent word = burp.sent.stream().filter(s -> "intranet".equals(s.header("Host"))).findFirst().orElseThrow();
        assertEquals("s=1", word.header("Cookie"));
        assertTrue(burp.sent.stream().noneMatch(s -> "evil".equals(s.header("Host"))));
        assertEquals("https://10.0.0.1:8443/", word.url());
    }

    @Test
    void calibrationFailureAborts() {
        FakeBurp burp = new FakeBurp((url, host) -> FakeBurp.fail());
        scan(new VhostEnumerator(burp.api), "example.com", List.of("a", "b"), List.of());
        assertTrue(hits.isEmpty());
        assertTrue(last().contains("calibration failed"), last());
        assertEquals(3, burp.sent.size());
    }

    @Test
    void cancelBeforeScanSendsNothing() {
        FakeBurp burp = new FakeBurp((url, host) -> Resp.of(200, 1));
        VhostEnumerator e = new VhostEnumerator(burp.api);
        e.cancel();
        scan(e, "example.com", List.of("a"), List.of());
        assertTrue(burp.sent.isEmpty());
        assertEquals("stopped, 0 found", last());
    }

    @Test
    void emptyWordlist() {
        FakeBurp burp = new FakeBurp((url, host) -> Resp.of(200, 1));
        scan(new VhostEnumerator(burp.api), "example.com", new ArrayList<>(), List.of());
        assertTrue(hits.isEmpty());
        assertTrue(last().startsWith("done, 0 found"), last());
    }
}
