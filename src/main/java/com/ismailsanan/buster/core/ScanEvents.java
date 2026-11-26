package com.ismailsanan.buster.core;

/**
 * what a running scan tells the UI besides hits and the status line,
 * every method may be called from any thread
 */
public interface ScanEvents {

    /** periodic progress of the current phase */
    default void progress(Progress p) {}

    /**
     * a calibration result: responses that look like this baseline at this
     * location are hidden (unless the scan shows all responses), like
     * feroxbuster's "auto-filtering found 404-like response"
     */
    default void autoFilter(String where, Baseline baseline) {}

    /** same, for filters that are not an HTTP baseline (wildcard DNS): what is hidden, in words */
    default void autoFilterNote(String where, String what) {}

    /** one line for the scan log */
    default void log(String line) {}

    ScanEvents NONE = new ScanEvents() {};
}
