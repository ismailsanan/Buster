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
    void parentDomainGuess() {
        assertEquals("app.target.com", UrlUtil.parentDomain("www.app.target.com"));
        assertEquals("target.com", UrlUtil.parentDomain("www.target.com"));
        assertEquals("target.com", UrlUtil.parentDomain("target.com"));
        assertEquals("10.0.0.5", UrlUtil.parentDomain("10.0.0.5"));
        assertEquals("[::1]", UrlUtil.parentDomain("[::1]"));
    }
}
