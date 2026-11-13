package core;

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
        if (uri.getHost() == null || uri.getHost().isBlank())
            throw new IllegalArgumentException("target has no host: " + raw);

        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (trailingSlash && !path.endsWith("/")) path += "/";
        String port = uri.getPort() == -1 ? "" : ":" + uri.getPort();
        return uri.getScheme().toLowerCase() + "://" + uri.getHost() + port + path;
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

    /** resolve a Location header against the URL that produced it */
    public static String resolveLocation(String requested, String location) {
        if (location == null || location.isBlank()) return requested;
        try {
            return URI.create(requested).resolve(location.trim()).toString();
        } catch (IllegalArgumentException e) {
            return requested;
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
        return !last.contains(".");
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
