package modes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ParsingTest {

    @Test
    void domainNormalisation() {
        assertEquals("target.com", DnsEnumerator.normalizeDomain(" Target.com "));
        assertEquals("target.com", DnsEnumerator.normalizeDomain("https://target.com/path"));
        assertEquals("target.com", DnsEnumerator.normalizeDomain("*.target.com."));
        assertEquals("target.com", DnsEnumerator.normalizeDomain("target.com:443"));
        assertEquals("xn--bcher-kva.de", DnsEnumerator.normalizeDomain("bücher.de"));
        assertThrows(IllegalArgumentException.class, () -> DnsEnumerator.normalizeDomain(""));
        assertThrows(IllegalArgumentException.class, () -> DnsEnumerator.normalizeDomain("a b.com"));
    }

    @Test
    void directoryStatuses() {
        assertTrue(DirEnumerator.isDirStatus(200));
        assertTrue(DirEnumerator.isDirStatus(403));
        assertTrue(DirEnumerator.isDirStatus(401));
        assertFalse(DirEnumerator.isDirStatus(404));
        assertFalse(DirEnumerator.isDirStatus(500));
        assertTrue(DirEnumerator.isRedirect(301));
        assertFalse(DirEnumerator.isRedirect(200));
    }
}
