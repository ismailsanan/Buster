package core;

/**
 * one row of output, shared by every mode
 *
 * fields are generic so dir, vhost and dns share one result type and one table:
 *   name    the thing found (URL, vhost, subdomain)
 *   detail  mode specific context (redirect target, resolved IP)
 *   status  HTTP status for dir/vhost, 0 for dns
 *   length  body length, 0 where not applicable
 *   extra   short flag shown in the table (dir, wildcard, resolved)
 */
public record EnumResult(String name, String detail, int status, long length, String extra) {

    public static EnumResult http(String url, String detail, int status, long length, String extra) {
        return new EnumResult(url, detail, status, length, extra);
    }

    public static EnumResult dns(String host, String resolvedIps) {
        return new EnumResult(host, resolvedIps, 0, 0, "resolved");
    }

    public String fingerprint() { return status + ":" + length; }
}
