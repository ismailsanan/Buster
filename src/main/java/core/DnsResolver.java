package core;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * DNS resolution via InetAddress, the system resolver
 */
public class DnsResolver {

    public static volatile java.util.function.Consumer<String> debug = null;

    public static List<DnsResult> resolve(String host) {
        List<DnsResult> out = new ArrayList<>();
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            for (InetAddress a : addrs) {
                String ip = a.getHostAddress();
                String type = ip.contains(":") ? "AAAA" : "A";
                out.add(DnsResult.of(host, type, ip));
            }
        } catch (java.net.UnknownHostException e) {
            // name does not resolve, this is the normal "not found" case
        } catch (Throwable t) {
            // anything else is a real problem worth surfacing
            if (debug != null) debug.accept("[DNS] resolve error for " + host
                    + " -> " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return out;
    }
}