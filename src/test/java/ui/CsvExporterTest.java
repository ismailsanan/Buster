package ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CsvExporterTest {

    @Test
    void plainValuesUnchanged() {
        assertEquals("https://h/admin", CsvExporter.escape("https://h/admin"));
        assertEquals("200", CsvExporter.escape(200));
        assertEquals("", CsvExporter.escape(null));
    }

    @Test
    void rfc4180Quoting() {
        assertEquals("\"a,b\"", CsvExporter.escape("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", CsvExporter.escape("say \"hi\""));
        assertEquals("\"line1\nline2\"", CsvExporter.escape("line1\nline2"));
    }

    @Test
    void formulaInjectionNeutralised() {
        assertEquals("'=1+1", CsvExporter.escape("=1+1"));
        assertEquals("\"'=HYPERLINK(\"\"x\"\")\"", CsvExporter.escape("=HYPERLINK(\"x\")"));
        assertEquals("'+cmd", CsvExporter.escape("+cmd"));
        assertEquals("'-1+1", CsvExporter.escape("-1+1"));
        assertEquals("'@SUM(A1)", CsvExporter.escape("@SUM(A1)"));
    }
}
