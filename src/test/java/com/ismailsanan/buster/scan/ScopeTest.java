package com.ismailsanan.buster.scan;

import com.ismailsanan.buster.core.EnumResult;
import com.ismailsanan.buster.core.UrlUtil;
import com.ismailsanan.buster.core.WordlistSource;
import com.ismailsanan.buster.testutil.FakeBurp;
import com.ismailsanan.buster.testutil.FakeBurp.Resp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** "Only in-scope targets" for Dir and Vhost */
@Timeout(60)
class ScopeTest {

    private static final class Run {
        final List<EnumResult> hits = new CopyOnWriteArrayList<>();
        final List<String> status = new CopyOnWriteArrayList<>();
    }

    private static Run dir(FakeBurp burp, List<String> words, int depth, boolean inScopeOnly) {
        Run r = new Run();
        new DirEnumerator(burp.api).scan(new DirEnumerator.Config("https://h", WordlistSource.ofList(words),
                List.of(""), depth, 2, false, false, List.of(), 0, inScopeOnly), r.hits::add, r.status::add);
        return r;
    }

    @Test
    void outOfScopeDirTargetSendsNothing() {
        FakeBurp burp = new FakeBurp((url, host) -> Resp.of(404, 10));
        burp.inScope = url -> false;
        Run r = dir(burp, List.of("a", "b"), 0, true);
        assertTrue(burp.sent.isEmpty(), "requests sent: " + burp.sentUrls());
        assertEquals(DirEnumerator.OUT_OF_SCOPE, r.status.get(r.status.size() - 1));
    }

    @Test
    void scopeCheckOffScansAnyway() {
        FakeBurp burp = new FakeBurp((url, host) -> Resp.of(404, 10));
        burp.inScope = url -> false;
        dir(burp, List.of("a"), 0, false);
        assertFalse(burp.sent.isEmpty());
    }

    @Test
    void recursionStopsAtOutOfScopeDirectories() {
        // /in/ and /out/ are both directories, scope only covers /in/
        FakeBurp burp = new FakeBurp((url, host) -> switch (UrlUtil.pathOf(url)) {
            case "/in", "/out" -> Resp.redirect(301, UrlUtil.pathOf(url) + "/");
            case "/in/x", "/out/x" -> Resp.of(200, 99);
            default -> Resp.of(404, 10);
        });
        burp.inScope = url -> !UrlUtil.pathOf(url).startsWith("/out");
        Run r = dir(burp, List.of("in", "out", "x"), 1, true);
        List<String> names = r.hits.stream().map(EnumResult::name).toList();
        assertTrue(names.contains("https://h/in/x"), names.toString());
        assertTrue(names.contains("https://h/out"), "the out-of-scope dir itself is still reported: " + names);
        assertFalse(burp.sentUrls().stream().anyMatch(u -> u.startsWith("https://h/out/")),
                "requests sent inside out-of-scope dir: " + burp.sentUrls());
    }

    @Test
    void outOfScopeVhostTargetSendsNothing() {
        FakeBurp burp = new FakeBurp((url, host) -> Resp.of(200, 10));
        burp.inScope = url -> false;
        List<String> status = new CopyOnWriteArrayList<>();
        new VhostEnumerator(burp.api).scan(new VhostEnumerator.Config("https://10.0.0.1", "t.com",
                WordlistSource.ofList(List.of("dev")), 2, List.of(), 0, true), h -> {}, status::add);
        assertTrue(burp.sent.isEmpty());
        assertEquals(DirEnumerator.OUT_OF_SCOPE, status.get(status.size() - 1));
    }
}
