package core;

/**
 * what "nothing here" looks like, used by dir and vhost
 * dir captures it per directory, vhost once with a garbage Host header
 */
public record Baseline(int status, long length, boolean soft404) {

    private static final double TOLERANCE = 0.05;

    // dir: clean server trusts status, soft404 server compares length
    public boolean isInteresting(int status, long length) {
        if (!soft404) return status != 404;
        if (status != this.status) return true;
        if (this.length == 0) return length != 0;
        return Math.abs(length - this.length) / (double) this.length > TOLERANCE;
    }

    // vhost: always length based, a wildcard vhost returns the same status
    // for every Host so only body size distinguishes a real vhost
    public boolean differsFrom(int status, long length) {
        if (status != this.status) return true;
        if (this.length == 0) return length != 0;
        return Math.abs(length - this.length) / (double) this.length > TOLERANCE;
    }
}
