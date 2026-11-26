package com.ismailsanan.buster.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** the live "e.g." lines under the target fields */
class ExampleLineTest {

    @Test
    void vhostShowsTheHostHeaderAndWhereItGoes() {
        assertEquals("e.g. Host: admin.target.com  ->  https://10.0.0.5", VhostPanel.example("10.0.0.5", true, "target.com"));
        assertEquals("e.g. Host: admin.target.com  ->  http://10.0.0.5:8080", VhostPanel.example("10.0.0.5:8080", false, "target.com"));
        assertEquals("e.g. Host: admin  ->  https://10.0.0.5", VhostPanel.example("10.0.0.5", true, " "));
        assertEquals("e.g. Host: admin.t.com  ->  the IP above", VhostPanel.example("", true, "t.com"));
    }

    @Test
    void dnsShowsTheNamesItTries() {
        assertEquals("e.g. tries admin.target.com, dev.target.com, mail.target.com ...", DnsPanel.example("target.com"));
        assertTrue(DnsPanel.example("").contains("admin.target.com"));
    }
}
