package com.pulse.gateway;

import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HexFormat;

/**
 * Who is the client? Rate limits are keyed by IP, so this must not be spoofable.
 *
 * <p>With {@code trustedProxyHops = 0} the gateway is the edge: we use the TCP peer address and ignore
 * X-Forwarded-For entirely (any client can type that header). Behind a load balancer/ingress (Phase 10)
 * set it to 1: we then trust exactly one hop of X-Forwarded-For, counted from the right.
 */
@Component
public class ClientIpResolver {

    private final XForwardedRemoteAddressResolver forwarded;
    private final boolean useForwarded;

    public ClientIpResolver(GatewayProps props) {
        this.useForwarded = props.trustedProxyHops() > 0;
        this.forwarded = XForwardedRemoteAddressResolver.maxTrustedIndex(Math.max(1, props.trustedProxyHops()));
    }

    public String resolve(ServerWebExchange exchange) {
        InetSocketAddress addr = useForwarded ? forwarded.resolve(exchange) : exchange.getRequest().getRemoteAddress();
        if (addr == null) {
            return "unknown";
        }
        return addr.getAddress() != null ? rateLimitKey(addr.getAddress()) : addr.getHostString();
    }

    /**
     * IPv4: the address itself. IPv6: only the first 64 bits (the "/64 prefix").
     *
     * <p>Why? An ISP gives every IPv6 customer at least a /64 — 2^64 addresses that one person can use freely.
     * Keying the rate limit on the full 128-bit address let a single attacker rotate through billions of
     * "different" clients and get a fresh bucket for every request (Phase 7 security review). One /64 ≈ one
     * customer, the same granularity as one IPv4 address behind a home router.
     */
    static String rateLimitKey(InetAddress ip) {
        if (ip instanceof Inet6Address) {
            byte[] prefix = java.util.Arrays.copyOf(ip.getAddress(), 8);   // first 8 bytes = 64 bits
            return HexFormat.of().formatHex(prefix) + "::/64";
        }
        return ip.getHostAddress();
    }
}
