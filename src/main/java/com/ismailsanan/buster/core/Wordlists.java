package com.ismailsanan.buster.core;

import java.util.ArrayList;
import java.util.List;

/** normalises raw wordlist text into clean words */
public final class Wordlists {

    private Wordlists() {}

    public static List<String> parse(String raw) {
        List<String> words = new ArrayList<>();
        if (raw == null) return words;
        for (String line : raw.split("\\R")) {
            String w = clean(line);
            if (w != null) words.add(w);
        }
        return words;
    }

    /** one wordlist line to a word, null for blank, comment or over-long lines */
    public static String clean(String line) {
        String t = line.trim();
        if (t.isEmpty() || t.startsWith("#") || t.length() > WordlistSource.MAX_LINE) return null;
        if (t.startsWith("/")) t = t.substring(1);
        return t.isEmpty() ? null : t;
    }

    /** true as soon as one usable word is found, cheap enough for the EDT */
    public static boolean hasAnyWord(String raw) {
        return raw != null && raw.lines().anyMatch(l -> clean(l) != null);
    }
}
