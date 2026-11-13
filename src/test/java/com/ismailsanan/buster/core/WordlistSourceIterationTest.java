package com.ismailsanan.buster.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/** iterator contract of the streamed file source (complements WordlistBoundsTest) */
class WordlistSourceIterationTest {

    private static List<String> all(Iterable<String> it) {
        List<String> out = new ArrayList<>();
        it.forEach(out::add);
        return out;
    }

    @Test
    void fileCanBeIteratedRepeatedlyAndIndependently(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("w.txt");
        Files.writeString(f, "a\r\n/b\n\n# c\n  d  \n/\ne");
        WordlistSource src = WordlistSource.ofFile(f, 7);
        assertEquals(List.of("a", "b", "d", "e"), all(src.words()));
        assertEquals(List.of("a", "b", "d", "e"), all(src.words()));   // second pass (dir recursion)

        Iterator<String> one = src.words().iterator();
        Iterator<String> two = src.words().iterator();
        assertEquals("a", one.next());
        assertEquals("a", two.next());
        assertEquals("b", one.next());
        assertEquals("b", two.next());
    }

    @Test
    void hasNextIsIdempotentAndNextPastEndThrows(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("w.txt");
        Files.writeString(f, "only\n");
        Iterator<String> it = WordlistSource.ofFile(f, 1).words().iterator();
        assertTrue(it.hasNext());
        assertTrue(it.hasNext());
        assertEquals("only", it.next());
        assertFalse(it.hasNext());
        assertFalse(it.hasNext());   // reader already closed, must not throw
        assertThrows(NoSuchElementException.class, it::next);
    }

    @Test
    void emptyAndCommentOnlyFilesYieldNothing(@TempDir Path dir) throws IOException {
        Path empty = dir.resolve("e.txt");
        Files.writeString(empty, "");
        assertFalse(WordlistSource.ofFile(empty, 0).words().iterator().hasNext());
        Path comments = dir.resolve("c.txt");
        Files.writeString(comments, "# x\n\n   \n#y");
        assertFalse(WordlistSource.ofFile(comments, 4).words().iterator().hasNext());
    }

    @Test
    void malformedUtf8IsReplacedNotFatal(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("bad.txt");
        Files.write(f, new byte[]{'o', 'k', '\n', (byte) 0xC3, (byte) 0x28, '\n', 'z'});
        List<String> words = all(WordlistSource.ofFile(f, 3).words());
        assertEquals(3, words.size());
        assertEquals("ok", words.get(0));
        assertEquals("z", words.get(2));
        assertTrue(words.get(1).contains("�"));
    }

    @Test
    void utf8WordsSurviveAndAreEncodedForUrls(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("u.txt");
        Files.writeString(f, "café\n", StandardCharsets.UTF_8);
        String w = all(WordlistSource.ofFile(f, 1).words()).get(0);
        assertEquals("caf%C3%A9", UrlUtil.encodePath(w));
    }

    @Test
    void listSourceReportsSize() {
        WordlistSource s = WordlistSource.ofList(List.of("a", "b"));
        assertEquals(2, s.estimatedSize());
        assertEquals("2 words", s.describe());
        assertEquals(0, WordlistSource.ofText("# only\n\n").estimatedSize());
    }
}
