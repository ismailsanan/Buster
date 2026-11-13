package core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
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
 * a source of words that can be iterated WITHOUT loading everything into memory
 *
 *   ofList  small lists, already in memory
 *   ofText  pasted text, parsed lazily on first use so it happens on the scan
 *           thread rather than the Swing EDT
 *   ofFile  a file streamed line by line, re-opened each iteration so the
 *           recursion in dir mode can iterate it again without holding a previous pass in memory
 */
public interface WordlistSource {

    /** lines longer than this are skipped, guards against a binary or newline-less file */
    int MAX_LINE = 2048;

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

    static WordlistSource ofText(String raw) {
        return new WordlistSource() {
            private List<String> parsed;
            private synchronized List<String> list() {
                if (parsed == null) parsed = Wordlists.parse(raw);
                return parsed;
            }
            public Iterable<String> words() { return list(); }
            public long estimatedSize() { return list().size(); }
            public String describe() { return list().size() + " words"; }
        };
    }

    static WordlistSource ofFile(Path path, long lineCount) {
        return new WordlistSource() {
            public Iterable<String> words() { return () -> new FileIterator(open(path)); }
            public long estimatedSize() { return lineCount; }
            public String describe() { return path.getFileName() + " (" + lineCount + " lines)"; }
        };
    }

    /**
     * close an iterator obtained from words() when a scan stops early, a no-op
     * for in-memory sources, a file iterator otherwise only closes at end of file
     */
    static void close(Iterator<?> it) {
        if (it instanceof AutoCloseable c) {
            try { c.close(); } catch (Exception ignored) {}
        }
    }

    final class FileIterator implements Iterator<String>, AutoCloseable {
        private final BufferedReader reader;
        private String next;
        private boolean closed;

        FileIterator(BufferedReader reader) {
            this.reader = reader;
            this.next = advance();
        }

        public boolean hasNext() { return next != null; }

        public String next() {
            if (next == null) throw new NoSuchElementException();
            String cur = next;
            next = advance();
            return cur;
        }

        private String advance() {
            if (closed) return null;
            try {
                String line;
                while ((line = readBoundedLine(reader)) != null) {
                    String w = Wordlists.clean(line);
                    if (w != null) return w;
                }
                close();
                return null;
            } catch (IOException e) {
                close();
                throw new UncheckedIOException(e);
            }
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            next = null;
            try { reader.close(); } catch (IOException ignored) {}
        }
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

    /**
     * like readLine but never buffers more than MAX_LINE chars, an over-long
     * line is consumed and returned as "" (which the cleaner then skips)
     */
    static String readBoundedLine(Reader r) throws IOException {
        StringBuilder sb = new StringBuilder();
        boolean overflow = false;
        int c;
        while ((c = r.read()) != -1) {
            if (c == '\n') break;
            if (c == '\r') {
                r.mark(1);
                if (r.read() != '\n') r.reset();
                break;
            }
            if (sb.length() < MAX_LINE) sb.append((char) c);
            else overflow = true;
        }
        if (c == -1 && sb.length() == 0 && !overflow) return null;
        return overflow ? "" : sb.toString();
    }

    /** counts newlines in fixed size chunks, -1 when the file cannot be read */
    static long countLines(Path path) {
        long count = 0;
        boolean pending = false;
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(path)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                for (int i = 0; i < n; i++) {
                    if (buf[i] == '\n') { count++; pending = false; }
                    else pending = true;
                }
            }
        } catch (IOException e) {
            return -1;
        }
        return pending ? count + 1 : count;
    }
}
