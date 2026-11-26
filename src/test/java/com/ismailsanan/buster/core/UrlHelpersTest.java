package com.ismailsanan.buster.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UrlHelpersTest {

    @Test
    void directoryOfRequest() {
        assertEquals("https://h:8443/a/b/", UrlUtil.directoryOf("https://h:8443/a/b/c.php?x=1#f"));
        assertEquals("https://h/a/", UrlUtil.directoryOf("https://h/a/"));
        assertEquals("https://h/", UrlUtil.directoryOf("https://h"));
        assertEquals("http://[::1]:8080/x/", UrlUtil.directoryOf("http://[::1]:8080/x/y"));
    }

    @Test
    void originAndHost() {
        assertEquals("https://h:8443", UrlUtil.originOf("https://h:8443/a/b?q"));
        assertEquals("h", UrlUtil.hostOf("https://h:8443/a"));
        assertEquals("[::1]", UrlUtil.hostOf("http://[::1]:8080/x"));
        assertEquals("web_app", UrlUtil.hostOf("http://web_app:8080/"));
    }

    @Test
    void registrableDomainIsTheLastLevel() {
        assertEquals("target.com", UrlUtil.registrableDomain("www.app.target.com"));
        assertEquals("a.com", UrlUtil.registrableDomain("a.a.a.com"));
        assertEquals("target.com", UrlUtil.registrableDomain("www.target.com"));
        assertEquals("target.com", UrlUtil.registrableDomain("target.com"));
        assertEquals("target.com", UrlUtil.registrableDomain("WWW.Target.COM."));
        assertEquals("shop.co.uk", UrlUtil.registrableDomain("a.b.shop.co.uk"));
        assertEquals("site.com.au", UrlUtil.registrableDomain("www.site.com.au"));
        assertEquals("target.io", UrlUtil.registrableDomain("x.target.io"));
        assertEquals("10.0.0.5", UrlUtil.registrableDomain("10.0.0.5"));
        assertEquals("[::1]", UrlUtil.registrableDomain("[::1]"));
        assertEquals("localhost", UrlUtil.registrableDomain("localhost"));
    }

    @Test
    void domainLevelsDeepestFirstDownToTheRegisteredDomain() {
        assertEquals(java.util.List.of("a.a.a.com", "a.a.com", "a.com"), UrlUtil.domainLevels("a.a.a.com"));
        assertEquals(java.util.List.of("www.app.target.com", "app.target.com", "target.com"),
                UrlUtil.domainLevels("www.app.target.com"));
        assertEquals(java.util.List.of("target.com"), UrlUtil.domainLevels("target.com"));
        assertEquals(java.util.List.of("x.shop.co.uk", "shop.co.uk"), UrlUtil.domainLevels("x.shop.co.uk"));
        assertEquals(java.util.List.of("10.0.0.5"), UrlUtil.domainLevels("10.0.0.5"));
    }

    @Test
    void targetWithoutScheme() {
        assertEquals("10.0.0.5", UrlUtil.hostPortOf("https://10.0.0.5/x"));
        assertEquals("10.0.0.5", UrlUtil.hostPortOf("https://10.0.0.5:443/"));
        assertEquals("10.0.0.5:8443", UrlUtil.hostPortOf("https://10.0.0.5:8443/"));
        assertEquals("h:443", UrlUtil.hostPortOf("http://h:443/"));
        assertEquals("h", UrlUtil.hostPortOf("http://h:80"));
        assertTrue(UrlUtil.isHttps("https://h/"));
        assertFalse(UrlUtil.isHttps("http://h/"));

        assertEquals("https://10.0.0.5/", UrlUtil.withScheme("10.0.0.5", true));
        assertEquals("http://10.0.0.5:8080/", UrlUtil.withScheme(" 10.0.0.5:8080 ", false));
        assertEquals("http://h/", UrlUtil.withScheme("http://h", true), "a typed scheme wins");
        assertThrows(IllegalArgumentException.class, () -> UrlUtil.withScheme("  ", true));
    }
}
