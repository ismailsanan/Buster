package core;

import java.util.List;

/**
 * a resolved DNS record, DNS mode's own result type
 *   type   A / AAAA / CNAME
 *   value  the IP, or the CNAME target
 *   note   takeover hint or wildcard warning when present
 */
public record DnsResult(String host, String type, String value, String note) {

    private static final List<String> TAKEOVER_SERVICES = List.of(
            "s3.amazonaws.com", "cloudfront.net", "herokuapp.com", "herokudns.com",
            "github.io", "githubusercontent.com", "gitlab.io", "netlify.app",
            "netlify.com", "vercel.app", "now.sh", "azurewebsites.net",
            "cloudapp.net", "trafficmanager.net", "blob.core.windows.net",
            "fastly.net", "pantheonsite.io", "wpengine.com", "zendesk.com",
            "statuspage.io", "surge.sh", "bitbucket.io", "readthedocs.io",
            "ghost.io", "shopify.com", "myshopify.com", "unbounce.com",
            "readme.io", "webflow.io"
    );

    public static DnsResult of(String host, String type, String value) {
        String note = "";
        if ("CNAME".equals(type)) {
            String v = value.toLowerCase();
            for (String svc : TAKEOVER_SERVICES) {
                if (v.contains(svc)) { note = "check takeover (" + svc + ")"; break; }
            }
        }
        return new DnsResult(host, type, value, note);
    }
}
