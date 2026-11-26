package com.ismailsanan.buster.core;

/**
 * how far a scan is, for the progress bar
 *
 *   done / total  work units of the current phase (requests or lookups);
 *                 total is an estimate for file wordlists (their line count),
 *                 so percent() is capped at 99 until the scan says it is finished
 *   label         what is being worked on, e.g. "dir 2 of 5: https://t/admin/"
 *   rate          requests (or lookups) per second over the whole scan
 */
public record Progress(long done, long total, String label, double rate) {

    public int percent() {
        if (total <= 0) return 0;
        return (int) Math.min(99, done * 100 / total);
    }

    /** seconds left at the current rate, -1 when unknown */
    public long etaSeconds() {
        if (rate <= 0 || total <= 0 || done >= total) return -1;
        return (long) Math.ceil((total - done) / rate);
    }

    /** "12,345 / 46,140 (26%) | 180 req/s | ETA 3m 10s" */
    public String describe(String unit) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%,d / %,d (%d%%)", done, Math.max(total, done), percent()));
        if (rate > 0) sb.append(String.format(" | %.0f %s/s", rate, unit));
        long eta = etaSeconds();
        if (eta >= 0) sb.append(" | ETA ").append(duration(eta));
        return sb.toString();
    }

    public static String duration(long seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    }
}
