package com.pulse.gateway;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Writes RFC 9457 Problem Details JSON from anywhere in the gateway (rate limiter, security, error handler),
 * so every error looks the same as content-service's errors — clients handle ONE format.
 */
final class Problems {

    private Problems() {
    }

    static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String title, String detail) {
        ServerHttpResponse res = exchange.getResponse();
        if (res.isCommitted()) {
            return Mono.empty();
        }
        res.setStatusCode(status);
        res.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        res.getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
        // The error handler sees the ORIGINAL request (before RequestIdFilter mutated it), so prefer the response header.
        String requestId = res.getHeaders().getFirst(RequestIdFilter.HEADER);
        if (requestId == null) {
            requestId = exchange.getRequest().getHeaders().getFirst(RequestIdFilter.HEADER);
        }
        String json = "{\"type\":\"about:blank\",\"title\":\"" + esc(title) + "\",\"status\":" + status.value()
                + ",\"detail\":\"" + esc(detail) + "\",\"instance\":\"" + esc(exchange.getRequest().getPath().value()) + "\""
                + (requestId != null ? ",\"requestId\":\"" + esc(requestId) + "\"" : "") + "}";
        DataBuffer buf = res.bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8));
        return res.writeWith(Mono.just(buf));
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("[\\x00-\\x1f]", " ");
    }
}
