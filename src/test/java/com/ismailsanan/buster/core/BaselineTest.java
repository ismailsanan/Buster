package com.ismailsanan.buster.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BaselineTest {

    @Test
    void cleanServerTrustsStatusOnly() {
        Baseline b = Baseline.of(404, 120);
        assertFalse(b.isInteresting(404, 9999));
        assertTrue(b.isInteresting(200, 120));
        assertTrue(b.isInteresting(403, 120));
    }

    @Test
    void catchAll403DoesNotFloodResults() {
        // server answers 403 for every path: only a different status or size is interesting
        Baseline b = Baseline.of(403, 300);
        assertFalse(b.isInteresting(403, 300));
        assertFalse(b.isInteresting(403, 310));   // within tolerance
        assertTrue(b.isInteresting(403, 900));
        assertTrue(b.isInteresting(200, 300));
        assertTrue(b.isInteresting(404, 300));
    }

    @Test
    void soft404ComparesLength() {
        Baseline b = Baseline.of(200, 1000);
        assertFalse(b.isInteresting(200, 1000));
        assertFalse(b.isInteresting(200, 1049));
        assertTrue(b.isInteresting(200, 1100));
        assertTrue(b.isInteresting(301, 1000));
    }

    @Test
    void lengthRangeAbsorbsPathEcho() {
        Baseline b = new Baseline(200, 1000, 1012);
        assertFalse(b.isInteresting(200, 1006));
        assertFalse(b.isInteresting(200, 1060));
        assertTrue(b.isInteresting(200, 1200));
    }

    @Test
    void emptyBaselineBody() {
        Baseline b = Baseline.of(200, 0);
        assertFalse(b.differsFrom(200, 0));
        assertTrue(b.differsFrom(200, 5));
    }

    @Test
    void vhostAlwaysComparesLengthEvenFor404() {
        Baseline b = Baseline.of(404, 500);
        assertFalse(b.differsFrom(404, 500));
        assertTrue(b.differsFrom(404, 5000));
    }
}
