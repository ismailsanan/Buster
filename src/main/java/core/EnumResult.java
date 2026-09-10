package core;

import burp.api.montoya.http.message.HttpRequestResponse;

/**
 * one row of output, shared by every mode
 *
 *   name        the thing found (URL, vhost, subdomain)
 *   redirect    where a 3xx points, empty for non redirects
 *   status      HTTP status for dir/vhost, 0 for dns
 *   length      body length, 0 where not applicable
 *   extra       short type flag (dir, file, vhost, resolved)
 *   exchange    the full request/response, kept so a click can show it in
 *               Burp's native editor, null for dns which does no HTTP
 */
public record EnumResult(
        String name,
        String redirect,
        int status,
        long length,
        String extra,
        HttpRequestResponse exchange) {

    public static EnumResult http(String url, String redirect, int status, long length,
                                  String extra, HttpRequestResponse exchange) {
        return new EnumResult(url, redirect, status, length, extra, exchange);
    }

    public static EnumResult dns(String host, String resolvedIps) {
        return new EnumResult(host, "", 0, 0, "resolved", null);
    }

    public String fingerprint() { return status + ":" + length; }
}