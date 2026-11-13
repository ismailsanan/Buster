package com.ismailsanan.buster.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UrlUtilTest {

    @Test
    void normalizeAddsSchemeAndSlash() {
        assertEquals("https://example.com/", UrlUtil.normalizeTarget("example.com", true));
        assertEquals("http://example.com:8080/app/", UrlUtil.normalizeTarget(" http://example.com:8080/app ", true));
        assertEquals("https://example.com/", UrlUtil.normalizeTarget("https://example.com", false));
    }

    @Test
    void normalizeDropsQueryFragmentAndCredentials() {
        assertEquals("https://example.com/a/", UrlUtil.normalizeTarget("https://user:pw@example.com/a?x=1#f", true));
    }

    @Test
    void normalizeRejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> UrlUtil.normalizeTarget("", true));
        assertThrows(IllegalArgumentException.class, () -> UrlUtil.normalizeTarget("https://", true));
        assertThrows(IllegalArgumentException.class, () -> UrlUtil.normalizeTarget("http://exa mple.com", true));
    }

    @Test
    void encodePathEscapesUnsafeCharacters() {
        assertEquals("admin", UrlUtil.encodePath("admin"));
        assertEquals("a%20b", UrlUtil.encodePath("a b"));
        assertEquals("x%23y%3Fz", UrlUtil.encodePath("x#y?z"));
        assertEquals("api/v1/users", UrlUtil.encodePath("api/v1/users"));
        assertEquals("%2e%2e", UrlUtil.encodePath("%2e%2e"));       // existing escapes kept
        assertEquals("100%25", UrlUtil.encodePath("100%"));          // lone percent escaped
        assertEquals("caf%C3%A9", UrlUtil.encodePath("café"));
    }

    @Test
    void resolveLocationIsRelativeToCurrentDirectory() {
        assertEquals("https://h/a/b/login", UrlUtil.resolveLocation("https://h/a/b/x", "login"));
        assertEquals("https://h/login", UrlUtil.resolveLocation("https://h/a/b/x", "/login"));
        assertEquals("https://other/z", UrlUtil.resolveLocation("https://h/a", "https://other/z"));
        assertEquals("https://h/a", UrlUtil.resolveLocation("https://h/a", ""));
    }

    @Test
    void redirectIntoDirectoryComparesPathsOnly() {
        assertTrue(UrlUtil.redirectsIntoDirectory("http://h/admin", "https://h/admin/"));
        assertTrue(UrlUtil.redirectsIntoDirectory("https://h/admin", "/admin/"));
        assertTrue(UrlUtil.redirectsIntoDirectory("https://h/admin", "admin/login"));
        assertFalse(UrlUtil.redirectsIntoDirectory("https://h/admin", "/login"));
        assertFalse(UrlUtil.redirectsIntoDirectory("https://h/admin", "/administrator/"));
        assertFalse(UrlUtil.redirectsIntoDirectory("https://h/admin", null));
    }

    @Test
    void extensionlessDetection() {
        assertTrue(UrlUtil.looksExtensionless("https://h/admin"));
        assertTrue(UrlUtil.looksExtensionless("https://h/admin/"));
        assertFalse(UrlUtil.looksExtensionless("https://h/config.php"));
        assertTrue(UrlUtil.looksExtensionless("https://h/v1.2/users"));
    }
}
