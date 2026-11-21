package com.pulse.gateway;

import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;

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
        return addr.getAddress() != null ? addr.getAddress().getHostAddress() : addr.getHostString();
    }
}
