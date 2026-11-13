package core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** regression tests for review findings */
class RegressionTest {

    @Test
    void baselineStatusIsMajorityAndIgnoresTransient() {
        assertEquals(404, BaselineDetector.dominantStatus(List.of(429, 404, 404)));
        assertEquals(404, BaselineDetector.dominantStatus(List.of(503, 404)));
        assertEquals(200, BaselineDetector.dominantStatus(List.of(200, 200, 404)));
        assertEquals(503, BaselineDetector.dominantStatus(List.of(503, 503, 503)));
    }

    @Test
    void cookieLinesAreMergedIntoOneHeader() {
        List<HttpEngine.HeaderKV> out = HttpEngine.mergeCookies(List.of(
                new HttpEngine.HeaderKV("Cookie", "a=1"),
                new HttpEngine.HeaderKV("Authorization", "Bearer x"),
                new HttpEngine.HeaderKV("cookie", "b=2")));
        assertEquals(List.of(new HttpEngine.HeaderKV("Authorization", "Bearer x"),
                new HttpEngine.HeaderKV("Cookie", "a=1; b=2")), out);
    }

    @Test
    void fileIteratorCanBeClosedEarly(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("w.txt");
        Files.writeString(f, "a\nb\nc\n");
        Iterator<String> it = WordlistSource.ofFile(f, 3).words().iterator();
        assertEquals("a", it.next());
        assertInstanceOf(AutoCloseable.class, it);
        WordlistSource.close(it);
        assertFalse(it.hasNext());
        WordlistSource.close(it);   // idempotent
        WordlistSource.close(List.of("x").iterator());   // no-op for in-memory sources
    }
}
