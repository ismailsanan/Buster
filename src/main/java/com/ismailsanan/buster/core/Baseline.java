package com.ismailsanan.buster.core;

/**
 * what "nothing here" looks like, built from one or more calibration probes
 *
 *   status     status the probes returned
 *   minLength  smallest body length seen across the probes
 *   maxLength  largest body length seen across the probes
 *
 * a length range rather than a single value absorbs pages that echo the
 * requested path back, so their size drifts with the word length
 */
public record Baseline(int status, long minLength, long maxLength) {

    static final double TOLERANCE = 0.05;

    public static Baseline of(int status, long length) {
        return new Baseline(status, length, length);
    }

    /**
     * dir mode: a clean server (404 baseline) is trusted on status alone,
     * any other catch-all status (200, 302, 401, 403 ...) also compares length
     */
    public boolean isInteresting(int status, long length) {
        if (this.status == 404) return status != 404;
        return differsFrom(status, length);
    }

    /**
     * vhost mode: always status plus length, a wildcard vhost returns the same
     * status for every Host so only body size distinguishes a real vhost
     */
    public boolean differsFrom(int status, long length) {
        if (status != this.status) return true;
        if (maxLength == 0) return length != 0;
        double lo = minLength * (1 - TOLERANCE);
        double hi = maxLength * (1 + TOLERANCE);
        return length < lo || length > hi;
    }

    public String describe() {
        return "status=" + status + " len=" + (minLength == maxLength ? minLength : minLength + "-" + maxLength);
    }
}
