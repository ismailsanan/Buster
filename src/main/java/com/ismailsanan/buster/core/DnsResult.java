package com.ismailsanan.buster.core;

/**
 * a resolved DNS record, DNS mode's own result type
 *   type   A / AAAA
 *   value  the IP address
 */
public record DnsResult(String host, String type, String value) {}
