package com.ismailsanan.buster.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * view filter for HTTP results, all parts optional
 *
 *   hide status  "404, 500-599"
 *   hide size    "0, 1234"
 *   match        regex that the result name or redirect must contain
 */
public final class ResultFilter {

    public static final ResultFilter NONE = new ResultFilter(List.of(), Set.of(), null);

    private final List<int[]> hiddenStatus;
    private final Set<Long> hiddenSizes;
    private final Pattern match;

    private ResultFilter(List<int[]> hiddenStatus, Set<Long> hiddenSizes, Pattern match) {
        this.hiddenStatus = hiddenStatus;
        this.hiddenSizes = hiddenSizes;
        this.match = match;
    }

    /** thrown with the name of the field that did not parse, so the UI can mark it */
    public static final class InvalidFilter extends IllegalArgumentException {
        public final String field;
        InvalidFilter(String field, String message) { super(message); this.field = field; }
    }

    public static ResultFilter parse(String statuses, String sizes, String regex) {
        List<int[]> st = new ArrayList<>();
        for (String part : split(statuses)) {
            try {
                int dash = part.indexOf('-');
                int lo = Integer.parseInt((dash < 0 ? part : part.substring(0, dash)).trim());
                int hi = dash < 0 ? lo : Integer.parseInt(part.substring(dash + 1).trim());
                st.add(new int[]{Math.min(lo, hi), Math.max(lo, hi)});
            } catch (NumberFormatException e) {
                throw new InvalidFilter("status", "bad status filter: " + part);
            }
        }
        Set<Long> sz = new HashSet<>();
        for (String part : split(sizes)) {
            try {
                sz.add(Long.parseLong(part));
            } catch (NumberFormatException e) {
                throw new InvalidFilter("size", "bad size filter: " + part);
            }
        }
        Pattern p = null;
        if (regex != null && !regex.isBlank()) {
            try {
                p = Pattern.compile(regex.trim(), Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
                throw new InvalidFilter("regex", "bad regex: " + e.getDescription());
            }
        }
        return st.isEmpty() && sz.isEmpty() && p == null ? NONE : new ResultFilter(st, sz, p);
    }

    public boolean isEmpty() { return this == NONE; }

    /** status and size are the table cell values, an Integer/Long or "" when absent */
    public boolean shows(String name, Object status, Object size, String redirect) {
        Long s = asLong(status);
        if (s != null) {
            for (int[] r : hiddenStatus) if (s >= r[0] && s <= r[1]) return false;
        }
        Long len = asLong(size);
        if (len == null) len = 0L;   // the table shows a zero length as blank
        if (hiddenSizes.contains(len)) return false;
        if (match != null) {
            boolean hit = (name != null && match.matcher(name).find())
                    || (redirect != null && match.matcher(redirect).find());
            if (!hit) return false;
        }
        return true;
    }

    private static List<String> split(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String p : raw.split("[,\\s]+")) if (!p.isBlank()) out.add(p.trim());
        return out;
    }

    private static Long asLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v == null) return null;
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }
}
