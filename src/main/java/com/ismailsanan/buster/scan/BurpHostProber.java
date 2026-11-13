package com.ismailsanan.buster.scan;

import com.ismailsanan.buster.core.Baseline;
import com.ismailsanan.buster.core.BaselineDetector;
import com.ismailsanan.buster.core.DnsResult;
import com.ismailsanan.buster.core.HttpEngine;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * subdomain discovery through Burp's network stack
 *
 * the Montoya API has no DNS lookup, so each candidate is requested as
 * https://name/ (falling back to http://name/) via Burp: Burp resolves the
 * name with its own settings (upstream proxy, SOCKS with remote DNS ...) and
 * a name counts as found when it answers. This finds live hosts, not IPs.
 *
 * random names are probed first; if they answer (an upstream proxy's error
 * page, a wildcard web host) answers that look the same are filtered out
 */
final class BurpHostProber {

    static final int BATCH = 300;
    private static final int BASELINE_PROBES = 3;
    private static final SecureRandom RANDOM = new SecureRandom();

    record Outcome(int found, int filtered) {}

    private final HttpEngine engine;
    private final BooleanSupplier stopped;

    BurpHostProber(HttpEngine engine, BooleanSupplier stopped) {
        this.engine = engine;
        this.stopped = stopped;
    }

    /** answer from probing random names, empty when random names do not answer (the normal case) */
    Optional<Baseline> baseline(String domain) {
        List<String> hosts = new ArrayList<>();
        for (int i = 0; i < BASELINE_PROBES; i++) hosts.add("zz-nope-" + Long.toHexString(RANDOM.nextLong()) + "." + domain);
        List<Hit> answers = probe(hosts);
        List<Integer> statuses = new ArrayList<>();
        for (Hit h : answers) if (h != null) statuses.add(h.probe().status());
        if (statuses.isEmpty()) return Optional.empty();
        int status = BaselineDetector.dominantStatus(statuses);
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (Hit h : answers) {
            if (h == null || h.probe().status() != status) continue;
            min = Math.min(min, h.probe().length());
            max = Math.max(max, h.probe().length());
        }
        return Optional.of(new Baseline(status, min, max));
    }

    Outcome scan(String domain, Iterator<String> words, Optional<Baseline> baseline, Consumer<DnsResult> onHit) {
        int found = 0, filtered = 0;
        List<String> hosts = new ArrayList<>(BATCH);
        while (words.hasNext() && !stopped.getAsBoolean()) {
            hosts.add(words.next() + "." + domain);
            if (hosts.size() < BATCH && words.hasNext()) continue;
            List<Hit> answers = probe(hosts);
            for (Hit h : answers) {
                if (h == null) continue;
                if (baseline.isPresent() && !baseline.get().differsFrom(h.probe().status(), h.probe().length())) {
                    filtered++;
                    continue;
                }
                onHit.accept(h.toResult());
                found++;
            }
            hosts.clear();
        }
        return new Outcome(found, filtered);
    }

    private record Hit(String host, String scheme, HttpEngine.Probe probe) {
        DnsResult toResult() {
            String value = probe.status() + ", " + probe.length() + " bytes";
            if (probe.location() != null && !probe.location().isBlank()) value += " -> " + probe.location();
            return new DnsResult(host, scheme.toUpperCase(), value);
        }
    }

    /** https first, http for the names that did not answer; one entry per host, null = no answer */
    private List<Hit> probe(List<String> hosts) {
        List<Hit> out = new ArrayList<>(hosts.size());
        List<Future<HttpEngine.Probe>> https = new ArrayList<>(hosts.size());
        for (String h : hosts) https.add(engine.submit("https://" + h + "/", HttpEngine.KEEP_NONE));

        List<Integer> retry = new ArrayList<>();
        for (int i = 0; i < hosts.size(); i++) {
            if (stopped.getAsBoolean()) { out.add(null); continue; }
            HttpEngine.Probe p = engine.await(https.get(i));
            out.add(p == null ? null : new Hit(hosts.get(i), "https", p));
            if (p == null) retry.add(i);
        }

        List<Future<HttpEngine.Probe>> http = new ArrayList<>(retry.size());
        for (int i : retry) http.add(engine.submit("http://" + hosts.get(i) + "/", HttpEngine.KEEP_NONE));
        for (int k = 0; k < retry.size() && !stopped.getAsBoolean(); k++) {
            HttpEngine.Probe p = engine.await(http.get(k));
            if (p != null) out.set(retry.get(k), new Hit(hosts.get(retry.get(k)), "http", p));
        }
        return out;
    }
}
