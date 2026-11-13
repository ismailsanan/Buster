package core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** edge cases on top of UrlUtilTest: IPv6, ports, trailing slashes, odd escapes */
class UrlUtilEdgeTest {

    @Test
    void ipv6AndPorts() {
        assertEquals("https://[::1]:8443/", UrlUtil.normalizeTarget("[::1]:8443", true));
        assertEquals("http://[2001:db8::1]/app/", UrlUtil.normalizeTarget("http://[2001:db8::1]/app", true));
        assertEquals("https://h:1/", UrlUtil.normalizeTarget("HTTPS://h:1", false));
        assertEquals("https://10.0.0.1:65535/x", UrlUtil.normalizeTarget("10.0.0.1:65535/x", false));
    }

    @Test
    void trailingSlashHandling() {
        assertEquals("https://h/a/", UrlUtil.normalizeTarget("https://h/a/", true));
        assertEquals("https://h/a/", UrlUtil.normalizeTarget("https://h/a/", false));
        assertEquals("https://h/a", UrlUtil.normalizeTarget("https://h/a", false));
        assertEquals("https://h/", UrlUtil.normalizeTarget("https://h/?q=1", true));
    }

    @Test
    void encodePathEdgeEscapes() {
        assertEquals("", UrlUtil.encodePath(""));
        assertEquals("%25", UrlUtil.encodePath("%"));
        assertEquals("a%254", UrlUtil.encodePath("a%4"));            // truncated escape at the end
        assertEquals("%41", UrlUtil.encodePath("%41"));              // escape exactly at the end
        assertEquals("%25%41", UrlUtil.encodePath("%%41"));
        assertEquals("%25zz", UrlUtil.encodePath("%zz"));
        assertEquals("a%5Cb%22%3C%3E%60%7B%7D%7C%5E", UrlUtil.encodePath("a\\b\"<>`{}|^"));
        assertEquals("%09%0A", UrlUtil.encodePath("\t\n"));
        assertEquals("~user;v=1:@!$&'()*+,", UrlUtil.encodePath("~user;v=1:@!$&'()*+,"));
        assertEquals("%F0%9F%98%80", UrlUtil.encodePath("😀"));
    }

    @Test
    void resolveLocationEdges() {
        assertEquals("https://h/a/admin/", UrlUtil.resolveLocation("https://h/a/admin", "admin/"));
        assertEquals("https://h/x", UrlUtil.resolveLocation("https://h/a/b", "../x"));
        assertEquals("https://other/p", UrlUtil.resolveLocation("https://h/a", "//other/p"));
        assertEquals("https://[::1]:8443/admin/", UrlUtil.resolveLocation("https://[::1]:8443/admin", "/admin/"));
        assertEquals("https://h:8443/admin/", UrlUtil.resolveLocation("https://h:8443/admin", "  admin/ "));
        assertEquals("https://h/a", UrlUtil.resolveLocation("https://h/a", null));
    }

    @Test
    void redirectIntoDirectoryEdges() {
        assertTrue(UrlUtil.redirectsIntoDirectory("https://h/sub/admin", "admin/"));        // relative
        assertTrue(UrlUtil.redirectsIntoDirectory("https://h/admin", "/admin/?next=1"));    // query ignored
        assertTrue(UrlUtil.redirectsIntoDirectory("https://[::1]:8443/admin", "https://[::1]:8443/admin/"));
        assertFalse(UrlUtil.redirectsIntoDirectory("https://h/admin/", "/admin/"));         // already a dir
        assertFalse(UrlUtil.redirectsIntoDirectory("https://h/admin", "/admin"));           // self redirect
        assertFalse(UrlUtil.redirectsIntoDirectory("https://h/admin", "  "));
        assertTrue(UrlUtil.redirectsIntoDirectory("https://h/a%20b", "/a%20b/"));
    }

    @Test
    void extensionlessEdges() {
        assertTrue(UrlUtil.looksExtensionless("https://h/"));
        assertTrue(UrlUtil.looksExtensionless("https://h"));
        assertTrue(UrlUtil.looksExtensionless("https://h/.git"));      // dot-dirs are directories
        assertTrue(UrlUtil.looksExtensionless("https://h/.well-known"));
        assertFalse(UrlUtil.looksExtensionless("https://h/.env.bak"));
        assertTrue(UrlUtil.looksExtensionless("https://[::1]:8443/admin"));
    }

    // ---- URL parsing edge cases ---------------------------------------------------------------

    /** java.net.URI treats hosts with '_' as registry authority, normalizeTarget parses those by hand */
    @Test
    void underscoreHostIsAccepted() {
        assertEquals("http://web_app:8080/", UrlUtil.normalizeTarget("http://web_app:8080", true));
        assertEquals("https://my_svc/x/", UrlUtil.normalizeTarget("my_svc/x", true));
        assertThrows(IllegalArgumentException.class, () -> UrlUtil.normalizeTarget("http://we<b_app/", true));
    }

    /** java.net.URI follows RFC 2396: a query-only reference drops the last segment (RFC 3986 keeps it) */
    @Test
    void queryOnlyLocationFollowsRfc2396() {
        assertEquals("https://h/?x=1", UrlUtil.resolveLocation("https://h/a", "?x=1"));
    }

    /** an unparseable Location (raw space) is shown as the raw header value */
    @Test
    void unparseableLocationShowsRawHeader() {
        assertEquals("/my dir/", UrlUtil.resolveLocation("https://h/a", "/my dir/"));   // unparseable: raw header
        assertTrue(UrlUtil.redirectsIntoDirectory("https://h/a", "/a/my file"));   // still inside /a/
    }
}
