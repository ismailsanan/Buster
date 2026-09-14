package core;

import burp.api.montoya.http.message.HttpRequestResponse;

import java.security.SecureRandom;

/** calibration for dir and vhost */
public class BaselineDetector {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final HttpEngine engine;

    public BaselineDetector(HttpEngine engine) { this.engine = engine; }

    public Baseline forDirectory(String dirUrl) {
        return from(engine.sendOnce(dirUrl + "zz-nope-" + token()), true);
    }

    public Baseline forVhost(String url) {
        return from(engine.sendOnce(url, "zz-nope-" + token() + ".invalid"), false);
    }

    private Baseline from(HttpRequestResponse r, boolean softAllowed) {
        if (r == null || !r.hasResponse()) return new Baseline(404, 0, false);
        int status = r.response().statusCode();
        long length = r.response().body().length();
        boolean soft404 = softAllowed && status >= 200 && status < 400;
        return new Baseline(status, length, soft404);
    }

    private String token() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        return sb.toString();
    }
}
