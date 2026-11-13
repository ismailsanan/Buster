package com.ismailsanan.buster.core;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * DNS resolution via InetAddress, i.e. the operating system resolver
 *
 * NOTE: lookups do not go through Burp's network stack (upstream proxy /
 * SOCKS settings do not apply), they leave from the machine running Burp
 */
public class DnsResolver {

    private final Consumer<String> errorLog;

    public DnsResolver(Consumer<String> errorLog) {
        this.errorLog = errorLog == null ? m -> {} : errorLog;
    }

    public List<DnsResult> resolve(String host) {
        List<DnsResult> out = new ArrayList<>();
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                String ip = a.getHostAddress();
                out.add(new DnsResult(host, ip.contains(":") ? "AAAA" : "A", ip));
            }
        } catch (UnknownHostException e) {
            // name does not resolve, the normal "not found" case
        } catch (RuntimeException e) {
            errorLog.accept("[DNS] resolve error for " + host + " -> "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return out;
    }
}
