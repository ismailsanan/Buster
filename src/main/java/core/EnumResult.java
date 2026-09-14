package core;

import burp.api.montoya.http.message.HttpRequestResponse;

/**
 * one HTTP-mode result row (dir, vhost)
 * DNS has its own result type since it carries no HTTP data
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
}
