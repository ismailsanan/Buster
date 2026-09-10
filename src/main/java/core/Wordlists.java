package core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Intruder style box, paste words in or load them from a local file, and
 * this class just normalises whatever ends up in that box
 *
 * strips blanks, strips # comments, strips a leading slash so a path list
 * like /admin joins cleanly onto a base URL
 */
public class Wordlists {

    // parse the contents of the paste/load text area
    public static List<String> parse(String raw) {
        List<String> words = new ArrayList<>();
        if (raw == null) return words;

        for (String line : raw.split("\\R")) {  // any line ending
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (t.startsWith("/")) t = t.substring(1);
            words.add(t);
        }
        return words;
    }

    public static String readFile(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);

            int offset = (bytes.length >= 3
                    && (bytes[0] & 0xFF) == 0xEF
                    && (bytes[1] & 0xFF) == 0xBB
                    && (bytes[2] & 0xFF) == 0xBF) ? 3 : 0;

            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);

            return decoder.decode(
                    java.nio.ByteBuffer.wrap(bytes, offset, bytes.length - offset)
            ).toString();

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}