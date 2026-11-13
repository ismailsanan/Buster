package core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WordlistBoundsTest {

    @Test
    void boundedLineHandlesAllLineEndings() throws IOException {
        BufferedReader r = new BufferedReader(new StringReader("a\nb\r\nc\rd"));
        assertEquals("a", WordlistSource.readBoundedLine(r));
        assertEquals("b", WordlistSource.readBoundedLine(r));
        assertEquals("c", WordlistSource.readBoundedLine(r));
        assertEquals("d", WordlistSource.readBoundedLine(r));
        assertNull(WordlistSource.readBoundedLine(r));
    }

    @Test
    void overlongLineIsSkippedNotBuffered() throws IOException {
        String huge = "x".repeat(WordlistSource.MAX_LINE * 4);
        BufferedReader r = new BufferedReader(new StringReader(huge + "\nok\n"));
        assertEquals("", WordlistSource.readBoundedLine(r));
        assertEquals("ok", WordlistSource.readBoundedLine(r));
    }

    @Test
    void fileStreamSkipsCommentsBlanksAndHugeLines(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("w.txt");
        Files.writeString(f, "# comment\n\n/admin\n" + "y".repeat(5000) + "\nlogin\n/\n");
        List<String> out = new ArrayList<>();
        WordlistSource.ofFile(f, 6).words().forEach(out::add);
        assertEquals(List.of("admin", "login"), out);
    }

    @Test
    void countLinesWithAndWithoutTrailingNewline(@TempDir Path dir) throws IOException {
        Path a = dir.resolve("a.txt");
        Files.writeString(a, "1\n2\n3\n");
        assertEquals(3, WordlistSource.countLines(a));
        Path b = dir.resolve("b.txt");
        Files.writeString(b, "1\n2\n3");
        assertEquals(3, WordlistSource.countLines(b));
        assertEquals(-1, WordlistSource.countLines(dir.resolve("missing.txt")));
    }

    @Test
    void textSourceParsesLazily() {
        WordlistSource src = WordlistSource.ofText("# note\nadmin\n\n/backup\n");
        assertEquals(2, src.estimatedSize());
        List<String> out = new ArrayList<>();
        src.words().forEach(out::add);
        assertEquals(List.of("admin", "backup"), out);
        assertTrue(Wordlists.hasAnyWord("# x\n\nword"));
        assertFalse(Wordlists.hasAnyWord("# only\n\n  "));
    }
}
