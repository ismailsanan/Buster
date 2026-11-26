package com.ismailsanan.buster.ui;

import com.ismailsanan.buster.core.Baseline;
import com.ismailsanan.buster.core.Progress;
import com.ismailsanan.buster.core.ScanEvents;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * sends one scan's events to the UI: the progress bar, the auto-filter line
 * above the results and the scan log
 *
 * the auto-filter line answers "what is being hidden": like feroxbuster's
 * "auto-filtering found 404-like response", it shows the status (and size)
 * each calibration decided means "not found"
 */
final class UiScanEvents implements ScanEvents {

    /** shown before any scan ran, so the default behaviour is visible up front */
    static final String IDLE_HINT =
            "Auto-filter: on start, Buster learns what \"not found\" looks like (usually 404) and hides it";

    private final ResultsTable<?> table;
    private final ScanLog log;
    private final String unit;
    private final boolean dirMode;
    private final boolean showAll;
    private final Map<String, String> filters = new LinkedHashMap<>();

    UiScanEvents(ResultsTable<?> table, ScanLog log, String unit, boolean dirMode, boolean showAll) {
        this.table = table;
        this.log = log;
        this.unit = unit;
        this.dirMode = dirMode;
        this.showAll = showAll;
        table.progressStarting();
        table.autoFilter(showAll
                ? "Auto-filter OFF (Show all): every response is listed, \"not found\" ones as Type = baseline"
                : "Auto-filter: calibrating ...", null);
    }

    @Override public void progress(Progress p) { table.progress(p, unit); }

    @Override public void log(String line) { log.log(line); }

    @Override
    public void autoFilter(String where, Baseline b) {
        String what = describe(b, dirMode);
        log.log((showAll ? "baseline   " : "auto-filter ") + where + " -> " + what
                + (showAll ? " (listed as Type = baseline)" : " hidden"));
        autoFilterNote(where, what);
    }

    @Override
    public void autoFilterNote(String where, String what) {
        String text, tip;
        synchronized (filters) {
            filters.put(where, what);
            text = summary();
            StringBuilder sb = new StringBuilder();
            filters.forEach((k, v) -> sb.append(k).append(" -> ").append(v).append('\n'));
            tip = sb.toString().trim();
        }
        table.autoFilter(text, tip);
    }

    private String summary() {
        String first = filters.values().iterator().next();
        boolean same = filters.values().stream().allMatch(first::equals);
        String prefix = showAll ? "Not-found baseline (shown, Type = baseline): " : "Auto-filter, hiding: ";
        if (filters.size() == 1 || same) {
            return prefix + first + (filters.size() > 1 ? " in all " + filters.size() + " places" : "");
        }
        return prefix + first + " at the target, " + (filters.size() - 1)
                + " other place(s) differ (hover or see Scan log)";
    }

    /**
     * human form of a baseline: dir mode trusts a clean 404 on status alone
     * (any size), every other baseline also needs the size to match
     */
    static String describe(Baseline b, boolean dirMode) {
        String size = b.minLength() == b.maxLength()
                ? "size " + b.minLength()
                : "size " + b.minLength() + "-" + b.maxLength();
        if (dirMode && b.status() == 404) return "status 404 (any size)";
        return "status " + b.status() + " with " + size + " (+/-5%)";
    }
}
