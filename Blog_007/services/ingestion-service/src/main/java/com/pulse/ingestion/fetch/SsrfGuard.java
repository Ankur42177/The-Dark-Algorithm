package com.pulse.ingestion.fetch;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * SSRF defence (PLAN §15). Fetching admin-supplied URLs is a classic Server-Side Request Forgery hole:
 * a feed URL like http://169.254.169.254/latest/meta-data/ (cloud metadata → credentials!) or
 * http://localhost:9081/actuator would make OUR server attack our own network.
 *
 * <p>Two layers:
 * <ol>
 *   <li>{@link #checkUrl}: only http/https, no credentials in the URL, no odd ports.</li>
 *   <li>{@link #dnsResolver}: the HTTP client resolves hostnames THROUGH this resolver, and it refuses
 *       private / loopback / link-local / metadata addresses. Because the check happens in the SAME lookup
 *       the client connects with, a "DNS rebinding" trick (public IP on the check, 127.0.0.1 on the
 *       connect) doesn't work. Redirect targets go through it too.</li>
 * </ol>
 */
@Component
public class SsrfGuard {

    private final Set<String> allowedPrivateHosts;

    /** Only for tests (WireMock runs on localhost). Never set in production. */
    public SsrfGuard(@Value("${pulse.fetch.allowed-private-hosts:}") Set<String> allowedPrivateHosts) {
        this.allowedPrivateHosts = allowedPrivateHosts;
    }

    public URI checkUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw FetchException.permanent("Invalid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw FetchException.permanent("Blocked scheme: " + scheme);            // file:, gopher:, ftp:, jar: …
        }
        if (uri.getHost() == null || uri.getUserInfo() != null) {
            throw FetchException.permanent("Blocked URL (missing host or embedded credentials)");
        }
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443 && !allowedPrivateHosts.contains(uri.getHost())) {
            throw FetchException.permanent("Blocked port: " + port);
        }
        return uri;
    }

    public DnsResolver dnsResolver() {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                InetAddress[] all = SystemDefaultDnsResolver.INSTANCE.resolve(host);
                if (!allowedPrivateHosts.contains(host)) {
                    for (InetAddress a : all) {
                        if (isBlocked(a)) {
                            throw new BlockedAddressException(host + " resolves to a blocked address " + a.getHostAddress());
                        }
                    }
                }
                return all;
            }

            @Override
            public String resolveCanonicalHostname(String host) throws UnknownHostException {
                return SystemDefaultDnsResolver.INSTANCE.resolveCanonicalHostname(host);
            }
        };
    }

    /** Loopback, private (10/8, 172.16/12, 192.168/16), link-local incl. 169.254.169.254, CGNAT, multicast, IPv6 ULA… */
    static boolean isBlocked(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int first = b[0] & 0xff, second = b[1] & 0xff;
            return first == 0                                    // "this network"
                    || (first == 100 && second >= 64 && second <= 127) // carrier-grade NAT 100.64/10
                    || (first == 192 && second == 0 && (b[2] & 0xff) == 0) // IETF protocol assignments
                    || first >= 240;                             // reserved
        }
        if (a instanceof Inet6Address) {
            return (b[0] & 0xfe) == 0xfc;                        // fc00::/7 unique-local
        }
        return false;
    }

    /** Thrown by the resolver; the fetcher turns it into a permanent failure. */
    public static class BlockedAddressException extends UnknownHostException {
        public BlockedAddressException(String msg) {
            super(msg);
        }
    }
}
