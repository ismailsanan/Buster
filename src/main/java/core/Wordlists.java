package core;

import java.util.ArrayList;
import java.util.List;

/** normalises raw wordlist text into clean words */
public class Wordlists {

    public static List<String> parse(String raw) {
        List<String> words = new ArrayList<>();
        if (raw == null) return words;
        for (String line : raw.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (t.startsWith("/")) t = t.substring(1);
            words.add(t);
        }
        return words;
    }
}
