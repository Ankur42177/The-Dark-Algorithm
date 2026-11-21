package com.pulse.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * CORRELATION IDs: every request gets an {@code X-Request-Id} (kept if the client sent a sane one,
 * generated otherwise). It is forwarded to the downstream service, echoed in the response, and written
 * in the gateway's access log — so one id lets you find a request in every service's logs.
 * (Phase 9 adds distributed tracing with trace/span ids on top of this.)
 *
 * <p>Runs FIRST (highest precedence), before Spring Security, so even 401/429 responses carry the id.
 * It also strips spoofable X-Forwarded-* headers when the gateway is the edge.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter implements WebFilter {

    public static final String HEADER = "X-Request-Id";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{8,64}");
    private static final Logger access = LoggerFactory.getLogger("pulse.gateway.access");

    private final boolean edge;

    public RequestIdFilter(GatewayProps props) {
        this.edge = props.trustedProxyHops() == 0;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String id = incoming != null && SAFE.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        long start = System.nanoTime();

        ServerHttpRequest request = exchange.getRequest().mutate().headers(h -> {
            h.set(HEADER, id);
            if (edge) {
                // A client could send "X-Forwarded-For: 1.2.3.4" to look like someone else (and dodge rate
                // limits downstream). At the edge we drop such headers; the gateway then adds the REAL peer IP.
                h.remove("X-Forwarded-For");
                h.remove("X-Forwarded-Host");
                h.remove("X-Forwarded-Proto");
                h.remove("X-Forwarded-Port");
                h.remove("X-Real-IP");
                h.remove("Forwarded");
            }
        }).build();
        exchange.getResponse().getHeaders().set(HEADER, id);

        return chain.filter(exchange.mutate().request(request).build())
                .doFinally(signal -> {
                    try (var ignored = MDC.putCloseable("requestId", id)) {
                        var status = exchange.getResponse().getStatusCode();
                        access.info("{} {} -> {} in {} ms", request.getMethod(), request.getPath().value(),
                                status == null ? "-" : status.value(), (System.nanoTime() - start) / 1_000_000);
                    }
                });
    }
}
