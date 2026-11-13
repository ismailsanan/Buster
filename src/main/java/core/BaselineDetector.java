package core;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Future;

/**
 * calibration for dir and vhost, sends several random probes and folds the
 * responses into one Baseline
 *
 * returns empty when no probe got a response, callers must treat that as
 * "cannot calibrate" rather than silently assuming a clean 404 server
 */
public class BaselineDetector {

    static final int PROBES = 3;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final HttpEngine engine;

    public BaselineDetector(HttpEngine engine) { this.engine = engine; }

    public Optional<Baseline> forDirectory(String dirUrl) {
        List<Future<HttpEngine.Probe>> probes = new ArrayList<>();
        // varying lengths so a page that echoes the path shows its size drift
        for (int i = 0; i < PROBES; i++) probes.add(engine.submit(dirUrl + "zz-nope-" + token(8 + i * 6), HttpEngine.KEEP_NONE));
        return fold(probes);
    }

    public Optional<Baseline> forVhost(String url) {
        List<Future<HttpEngine.Probe>> probes = new ArrayList<>();
        for (int i = 0; i < PROBES; i++) probes.add(engine.submit(url, "zz-nope-" + token(8 + i * 6) + ".invalid", HttpEngine.KEEP_NONE));
        return fold(probes);
    }

    private Optional<Baseline> fold(List<Future<HttpEngine.Probe>> probes) {
        List<Integer> statuses = new ArrayList<>();
        List<Long> lengths = new ArrayList<>();
        for (Future<HttpEngine.Probe> f : probes) {
            HttpEngine.Probe p = engine.await(f);
            if (p == null) continue;
            statuses.add(p.status());
            lengths.add(p.length());
        }
        if (statuses.isEmpty()) return Optional.empty();

        int status = dominantStatus(statuses);
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (int i = 0; i < statuses.size(); i++) {
            if (statuses.get(i).intValue() != status) continue;
            min = Math.min(min, lengths.get(i));
            max = Math.max(max, lengths.get(i));
        }
        return Optional.of(new Baseline(status, min, max));
    }

    /**
     * most common status, ignoring transient ones (429, 5xx) when anything else
     * answered, so one rate-limited probe cannot become the "not found" baseline
     */
    static int dominantStatus(List<Integer> statuses) {
        java.util.Map<Integer, Integer> counts = new java.util.LinkedHashMap<>();
        for (int s : statuses) counts.merge(s, 1, Integer::sum);
        boolean anyStable = counts.keySet().stream().anyMatch(s -> !isTransient(s));
        int best = statuses.get(0), bestCount = -1;
        for (var e : counts.entrySet()) {
            if (anyStable && isTransient(e.getKey())) continue;
            if (e.getValue() > bestCount) { best = e.getKey(); bestCount = e.getValue(); }
        }
        return best;
    }

    private static boolean isTransient(int status) { return status == 429 || status >= 500; }

    private static String token(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        return sb.toString();
    }
}
