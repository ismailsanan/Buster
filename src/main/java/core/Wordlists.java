package core;

import java.io.IOException;
import java.io.UncheckedIOException;
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

    // read a local file into raw text, used by the Load File button
    // the UI drops the text into the same box the user could paste into,
    // so file and paste both flow through parse()
    public static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
