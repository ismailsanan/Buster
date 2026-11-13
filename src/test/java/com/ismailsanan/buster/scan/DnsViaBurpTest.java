package com.ismailsanan.buster.scan;

import com.ismailsanan.buster.core.DnsResult;
import com.ismailsanan.buster.core.WordlistSource;
import com.ismailsanan.buster.testutil.FakeBurp;
import com.ismailsanan.buster.testutil.FakeBurp.Resp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** DNS mode with "Resolve via: Burp": names are probed over HTTP(S) through Burp's network stack */
@Timeout(60)
class DnsViaBurpTest {

    private static final String D = "target.com";

    private static final class Run {
        final List<DnsResult> hits = new CopyOnWriteArrayList<>();
        final List<String> status = new CopyOnWriteArrayList<>();
        Map<String, String> byHost() {
            Map<String, String> m = new TreeMap<>();
            for (DnsResult r : hits) m.put(r.host(), r.type() + " " + r.value());
            return m;
        }
        String last() { return status.isEmpty() ? "" : status.get(status.size() - 1); }
    }

    private static Run scan(DnsEnumerator e, List<String> words) {
        Run r = new Run();
        e.scan(new DnsEnumerator.Config(D, WordlistSource.ofList(words), 4, DnsEnumerator.Resolver.BURP),
                r.hits::add, r.status::add);
        return r;
    }

    @Test
    void reportsNamesThatAnswerAndFallsBackToHttp() {
        FakeBurp burp = new FakeBurp((url, host) -> switch (url) {
            case "https://www.target.com/" -> Resp.of(200, 512);
            case "http://legacy.target.com/" -> Resp.redirect(301, "https://legacy.target.com/");
            default -> null;   // name does not resolve / nothing listening
        });
        Run r = scan(new DnsEnumerator(burp.api), List.of("www", "legacy", "nothing"));
        assertEquals(Map.of(
                "www.target.com", "HTTPS 200, 512 bytes",
                "legacy.target.com", "HTTP 301, 0 bytes -> https://legacy.target.com/"), r.byHost());
        assertTrue(r.last().startsWith("done, 2 found"), r.last());
        // every request went through Burp: https first, http only for names that did not answer
        List<String> sent = burp.sentUrls();
        assertTrue(sent.contains("https://nothing.target.com/"));
        assertTrue(sent.contains("http://nothing.target.com/"));
        assertFalse(sent.contains("http://www.target.com/"), "http probed although https answered");
    }

    @Test
    void upstreamProxyErrorPagesAreFiltered() {
        // a proxy answering 502 for every unknown host must not make every word a hit
        FakeBurp burp = new FakeBurp((url, host) ->
                url.startsWith("https://real.") ? Resp.of(200, 4000) : Resp.of(502, 120));
        Run r = scan(new DnsEnumerator(burp.api), List.of("a", "b", "real", "c"));
        assertEquals(List.of("real.target.com"), r.hits.stream().map(DnsResult::host).toList());
        assertTrue(r.last().contains("3 filtered"), r.last());
    }

    @Test
    void cancelBeforeScanSendsNothing() {
        FakeBurp burp = new FakeBurp((url, host) -> Resp.of(200, 1));
        DnsEnumerator e = new DnsEnumerator(burp.api);
        e.cancel();
        Run r = scan(e, List.of("a", "b"));
        assertTrue(r.hits.isEmpty());
        assertTrue(r.last().startsWith("stopped"), r.last());
    }

    @Test
    void systemResolverStaysTheDefault() {
        assertEquals(DnsEnumerator.Resolver.SYSTEM,
                new DnsEnumerator.Config(D, WordlistSource.ofList(List.of()), 1).resolver());
    }
}
