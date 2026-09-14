package core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * a source of words that can be iterated WITHOUT loading everything into
 * memory, this is what makes huge wordlists
 *
 *   ofList  small pasted lists, already in memory
 *   ofFile  a file streamed line by line, re-opened each iteration so the
 *   recursion in dir mode can iterate it again without holding a previous pass in memory
 */
public interface WordlistSource {

    Iterable<String> words();
    long estimatedSize();
    String describe();

    static WordlistSource ofList(List<String> words) {
        return new WordlistSource() {
            public Iterable<String> words() { return words; }
            public long estimatedSize() { return words.size(); }
            public String describe() { return words.size() + " words"; }
        };
    }

    static WordlistSource ofFile(Path path, long lineCount) {
        return new WordlistSource() {
            public Iterable<String> words() {
                return () -> new Iterator<>() {
                    private final BufferedReader reader = open(path);
                    private String next = advance();
                    public boolean hasNext() { return next != null; }
                    public String next() {
                        if (next == null) throw new NoSuchElementException();
                        String cur = next;
                        next = advance();
                        return cur;
                    }
                    private String advance() {
                        try {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                String t = line.trim();
                                if (t.isEmpty() || t.startsWith("#")) continue;
                                if (t.startsWith("/")) t = t.substring(1);
                                return t;
                            }
                            reader.close();
                            return null;
                        } catch (IOException e) {
                            try { reader.close(); } catch (IOException ignored) {}
                            throw new UncheckedIOException(e);
                        }
                    }
                };
            }
            public long estimatedSize() { return lineCount; }
            public String describe() { return path.getFileName() + " (" + lineCount + " lines)"; }
        };
    }

    private static BufferedReader open(Path path) {
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            return new BufferedReader(new InputStreamReader(Files.newInputStream(path), decoder));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static long countLines(Path path) {
        long count = 0;
        try (BufferedReader r = open(path)) {
            while (r.readLine() != null) count++;
        } catch (IOException e) {
            return -1;
        }
        return count;
    }
}
