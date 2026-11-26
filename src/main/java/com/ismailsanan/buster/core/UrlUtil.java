package com.ismailsanan.buster.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

/** URL helpers kept free of Burp types so they can be unit tested */
public final class UrlUtil {

    private UrlUtil() {}

    /**
     * parse a user supplied target into scheme://host[:port]/path, dropping
     * query and fragment, defaulting to https when no scheme is given
     *
     * @throws IllegalArgumentException when the target is not a usable http(s) URL
     */
    public static String normalizeTarget(String raw, boolean trailingSlash) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("target is empty");
        String u = raw.trim();
        if (!u.matches("(?i)^https?://.*")) u = "https://" + u;
        URI uri;
        try {
            uri = new URI(u);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("invalid target URL: " + raw);
        }
        String hostPort;
        if (uri.getHost() != null && !uri.getHost().isBlank()) {
            hostPort = uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        } else {
            // java.net.URI rejects '_' in host names (common for docker services), parse by hand
            String auth = uri.getRawAuthority();
            if (auth == null) throw new IllegalArgumentException("target has no host: " + raw);
            auth = auth.substring(auth.lastIndexOf('@') + 1);
            if (!auth.matches("[A-Za-z0-9._-]+(:\\d{1,5})?"))
                throw new IllegalArgumentException("target has no valid host: " + raw);
            hostPort = auth;
        }

        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (trailingSlash && !path.endsWith("/")) path += "/";
        return uri.getScheme().toLowerCase() + "://" + hostPort + path;
    }

    /**
     * percent-encode a wordlist entry for use in a path, keeping '/' and
     * characters that are legal in a path, and leaving existing %XX escapes alone
     */
    public static String encodePath(String word) {
        StringBuilder sb = new StringBuilder(word.length() + 8);
        byte[] bytes = word.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xff;
            if (b == '%' && i + 2 < bytes.length && isHex(bytes[i + 1]) && isHex(bytes[i + 2])) {
                sb.append('%');
            } else if (isPathSafe(b)) {
                sb.append((char) b);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(b >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b & 0xf, 16)));
            }
        }
        return sb.toString();
    }

    /** resolve a Location header against the URL that produced it, the raw header when it cannot be parsed */
    public static String resolveLocation(String requested, String location) {
        if (location == null || location.isBlank()) return requested;
        try {
            return URI.create(requested).resolve(location.trim()).toString();
        } catch (IllegalArgumentException e) {
            return location.trim();
        }
    }

    /** path component of a URL, or the input itself when it cannot be parsed */
    public static String pathOf(String url) {
        try {
            String p = URI.create(url).getRawPath();
            return p == null || p.isEmpty() ? "/" : p;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    /**
     * true when a redirect from requested points at requested + "/" (or below it),
     * comparing paths only so an http -> https or host canonicalisation redirect still counts
     */
    public static boolean redirectsIntoDirectory(String requested, String location) {
        if (location == null || location.isBlank()) return false;
        String reqPath = pathOf(requested);
        if (reqPath.endsWith("/")) return false;
        String target = pathOf(resolveLocation(requested, location));
        return target.startsWith(reqPath + "/");
    }

    /** last path segment has no extension, so it could be a directory */
    public static boolean looksExtensionless(String url) {
        String path = pathOf(url);
        if (path.endsWith("/")) return true;
        String last = path.substring(path.lastIndexOf('/') + 1);
        // dot-directories (.git, .svn, .well-known) have no extension
        if (last.startsWith(".")) last = last.substring(1);
        return !last.contains(".");
    }

    /** "https://h:8443/a/b/c.php?x" -> "https://h:8443/a/b/", the directory a request lives in */
    public static String directoryOf(String url) {
        String base = normalizeTarget(url, false);
        int schemeEnd = base.indexOf("://") + 3;
        int lastSlash = base.lastIndexOf('/');
        return lastSlash < schemeEnd ? base + "/" : base.substring(0, lastSlash + 1);
    }

    /** "https://h:8443/a/b" -> "https://h:8443" */
    public static String originOf(String url) {
        String base = normalizeTarget(url, false);
        int pathStart = base.indexOf('/', base.indexOf("://") + 3);
        return pathStart < 0 ? base : base.substring(0, pathStart);
    }

    /** host of a URL without port, brackets kept for IPv6 */
    public static String hostOf(String url) {
        String origin = originOf(url);
        String hostPort = origin.substring(origin.indexOf("://") + 3);
        if (hostPort.startsWith("[")) return hostPort.substring(0, hostPort.indexOf(']') + 1);
        int colon = hostPort.lastIndexOf(':');
        return colon < 0 ? hostPort : hostPort.substring(0, colon);
    }

    /**
     * "https://h:8443/x" -> "h:8443", "http://h/" -> "h", "https://h:443" -> "h":
     * the address part of a target, without the scheme and without a port that
     * is the scheme's default
     */
    public static String hostPortOf(String url) {
        String origin = originOf(url);
        boolean https = origin.startsWith("https://");
        String hp = origin.substring(origin.indexOf("://") + 3);
        String defaultPort = https ? ":443" : ":80";
        return hp.endsWith(defaultPort) ? hp.substring(0, hp.length() - defaultPort.length()) : hp;
    }

    public static boolean isHttps(String url) {
        return normalizeTarget(url, false).startsWith("https://");
    }

    /**
     * a target typed as "10.0.0.5", "10.0.0.5:8080" or a full URL; when no scheme
     * is typed the https flag decides it, a typed scheme always wins
     */
    public static String withScheme(String hostOrUrl, boolean https) {
        if (hostOrUrl == null || hostOrUrl.isBlank()) throw new IllegalArgumentException("target is empty");
        String t = hostOrUrl.trim();
        if (t.matches("(?i)^https?://.*")) return normalizeTarget(t, false);
        return normalizeTarget((https ? "https://" : "http://") + t, false);
    }

    private static boolean isIp(String host) {
        return host.startsWith("[") || host.matches("\\d+(\\.\\d+){3}");
    }

    /** second-level labels that, under a two-letter country TLD, are part of the public suffix (co.uk, com.au ...) */
    private static final java.util.Set<String> CC_SECOND_LEVEL = java.util.Set.of(
            "co", "com", "net", "org", "gov", "edu", "ac", "gob", "mil", "nic", "or", "ne", "go", "gv", "nom", "sch", "ltd", "plc");

    /**
     * the registered domain a host belongs to, the last level worth enumerating
     * under: "a.b.target.com" -> "target.com", "www.shop.co.uk" -> "shop.co.uk";
     * IPs and single labels are returned as they are
     */
    public static String registrableDomain(String host) {
        if (host == null) return "";
        String h = host.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\.+$", "");
        if (h.isEmpty() || isIp(h)) return h;
        String[] labels = h.split("\\.");
        if (labels.length <= 2) return h;
        String tld = labels[labels.length - 1];
        String second = labels[labels.length - 2];
        int keep = tld.length() == 2 && CC_SECOND_LEVEL.contains(second) ? 3 : 2;
        if (labels.length <= keep) return h;
        return String.join(".", java.util.Arrays.copyOfRange(labels, labels.length - keep, labels.length));
    }

    /**
     * every level from the full host down to its registered domain, deepest
     * first: "a.b.target.com" -> [a.b.target.com, b.target.com, target.com]
     */
    public static java.util.List<String> domainLevels(String host) {
        String h = host == null ? "" : host.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\.+$", "");
        String root = registrableDomain(h);
        java.util.List<String> out = new java.util.ArrayList<>();
        if (h.isEmpty()) return out;
        if (isIp(h)) { out.add(h); return out; }
        String cur = h;
        while (true) {
            out.add(cur);
            if (cur.equals(root) || !cur.contains(".")) break;
            cur = cur.substring(cur.indexOf('.') + 1);
        }
        return out;
    }

    private static boolean isHex(byte b) {
        return (b >= '0' && b <= '9') || (b >= 'a' && b <= 'f') || (b >= 'A' && b <= 'F');
    }

    private static boolean isPathSafe(int b) {
        if (b >= 'a' && b <= 'z') return true;
        if (b >= 'A' && b <= 'Z') return true;
        if (b >= '0' && b <= '9') return true;
        return "/-._~!$&'()*+,;=:@".indexOf(b) >= 0;
    }
}
