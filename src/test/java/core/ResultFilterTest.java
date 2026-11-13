package core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResultFilterTest {

    @Test
    void emptyFilterShowsEverything() {
        ResultFilter f = ResultFilter.parse("", " ", null);
        assertTrue(f.isEmpty());
        assertTrue(f.shows("https://h/a", 404, 0L, ""));
    }

    @Test
    void hidesStatusesAndRanges() {
        ResultFilter f = ResultFilter.parse("404, 500-599", "", "");
        assertFalse(f.shows("x", 404, 10L, ""));
        assertFalse(f.shows("x", 503, 10L, ""));
        assertTrue(f.shows("x", 200, 10L, ""));
        assertTrue(f.shows("x", "", 10L, ""));   // vhost/blank status is never hidden by status
    }

    @Test
    void hidesSizesIncludingBlankAsZero() {
        ResultFilter f = ResultFilter.parse("", "0 1234", "");
        assertFalse(f.shows("x", 200, 1234L, ""));
        assertFalse(f.shows("x", 200, "", ""));
        assertTrue(f.shows("x", 200, 99L, ""));
    }

    @Test
    void regexMatchesNameOrRedirect() {
        ResultFilter f = ResultFilter.parse("", "", "admin|\\.bak$");
        assertTrue(f.shows("https://h/ADMIN", 200, 1L, ""));
        assertTrue(f.shows("https://h/x", 301, 1L, "https://h/admin/"));
        assertTrue(f.shows("https://h/db.bak", 200, 1L, ""));
        assertFalse(f.shows("https://h/login", 200, 1L, ""));
    }

    @Test
    void invalidInputNamesTheField() {
        assertEquals("status", assertThrows(ResultFilter.InvalidFilter.class,
                () -> ResultFilter.parse("40x", "", "")).field);
        assertEquals("size", assertThrows(ResultFilter.InvalidFilter.class,
                () -> ResultFilter.parse("", "big", "")).field);
        assertEquals("regex", assertThrows(ResultFilter.InvalidFilter.class,
                () -> ResultFilter.parse("", "", "(")).field);
    }
}
