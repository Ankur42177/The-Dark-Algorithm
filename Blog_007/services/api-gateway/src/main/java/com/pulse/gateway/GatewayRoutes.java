package com.pulse.gateway;

import org.springframework.cloud.gateway.filter.factory.RetryGatewayFilterFactory;
import org.springframework.cloud.gateway.route.builder.GatewayFilterSpec;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.support.RouteMetadataUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * THE ROUTING TABLE. Each route = predicate (which requests?) + filters (what to do on the way) + uri (where to).
 * The gateway holds NO business logic: it routes, authenticates, limits and observes.
 */
@Configuration
public class GatewayRoutes {

    @Bean
    RouteLocator pulseRoutes(RouteLocatorBuilder routes, GatewayProps props, RateLimiting limits) {
        return routes.routes()
                // ---- content-service: public reads (+ the view beacon) ------------------------------
                .route("content-public", r -> r
                        .path("/api/v1/posts/**", "/api/v1/sections", "/api/v1/trending", "/api/v1/search/**",
                                "/api/v1/feed/**", "/.well-known/jwks.json")
                        .filters(f -> common(f).filters(limits.filter(RateLimitPolicy.PUBLIC))
                                .retry(GatewayRoutes::retryIdempotentGets))
                        .uri(props.contentUri()))
                // ---- login: strict brute-force limit -------------------------------------------------
                .route("content-login", r -> r
                        .path("/api/v1/auth/login")
                        .filters(f -> common(f).filters(limits.filter(RateLimitPolicy.LOGIN)))
                        .uri(props.contentUri()))
                // ---- refresh / logout / me ------------------------------------------------------------
                .route("content-auth", r -> r
                        .path("/api/v1/auth/**")
                        .filters(f -> common(f).filters(limits.filter(RateLimitPolicy.PUBLIC)))
                        .uri(props.contentUri()))
                // ---- public forms that SEND email: newsletter subscribe + contact ----------------------
                // Strict per-IP bucket (5 at once, then 1 per 2 min). POSTs, never retried.
                .route("content-forms", r -> r
                        .path("/api/v1/newsletter/subscribe", "/api/v1/contact")
                        .filters(f -> common(f).filters(limits.filter(RateLimitPolicy.FORMS)))
                        .uri(props.contentUri()))
                // ---- links clicked from an email: confirm + unsubscribe -------------------------------
                // NOT in the forms bucket: they send nothing, need a 256-bit token anyway, and an unsubscribe must
                // never be refused because a school or office NAT used up "forms" (anti-spam law; Phase 7 review).
                .route("content-token-links", r -> r
                        .path("/api/v1/newsletter/confirm", "/api/v1/newsletter/unsubscribe")
                        .filters(f -> common(f).filters(limits.filter(RateLimitPolicy.PUBLIC)))
                        .uri(props.contentUri()))
                // ---- admin APIs (JWT checked by SecurityConfig before we get here) -------------------
                .route("content-admin", r -> r
                        .path("/api/v1/admin/posts/**", "/api/v1/admin/posts", "/api/v1/admin/preview",
                                "/api/v1/admin/newsletter/**", "/api/v1/admin/newsletter",
                                "/api/v1/admin/contact/**", "/api/v1/admin/contact")
                        .filters(f -> common(f).filters(limits.filter(RateLimitPolicy.ADMIN)))
                        .uri(props.contentUri()))
                .route("ingestion-admin", r -> r
                        .path("/api/v1/admin/sources/**", "/api/v1/admin/sources", "/api/v1/admin/ingestion/**")
                        .filters(f -> common(f).filters(limits.filter(RateLimitPolicy.ADMIN)))
                        .uri(props.ingestionUri()))
                // ---- AI assistant: chat streams (SSE) can run long → longer response timeout ----------
                .route("assistant", r -> r
                        .path("/api/v1/assistant/**")
                        .filters(f -> common(f).filters(limits.filters(RateLimitPolicy.CHAT_MINUTE, RateLimitPolicy.CHAT_DAY)))
                        .metadata(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR, 60_000)
                        .uri(props.assistantUri()))
                .build();
    }

    /**
     * Filters EVERY route gets. (YAML "default-filters" only apply to routes defined in YAML, not to routes
     * built here in Java — a gotcha our tests caught.)
     * <ul>
     *   <li>Reject bodies over 2 MB with 413 before they reach any service.</li>
     *   <li>Gateway AND content-service both add CORS headers: keep exactly one of each.</li>
     * </ul>
     */
    private static GatewayFilterSpec common(GatewayFilterSpec f) {
        return f.setRequestSize(DataSize.ofMegabytes(2))
                .dedupeResponseHeader("Access-Control-Allow-Origin Access-Control-Allow-Credentials Access-Control-Expose-Headers", "RETAIN_FIRST")
                .dedupeResponseHeader("Vary", "RETAIN_UNIQUE");
    }

    /**
     * Retry ONLY idempotent GETs (repeating them is harmless), only on "the server wasn't reachable" kinds of
     * failure, at most 2 times with exponential backoff + jitter (so retries from many clients don't line up).
     * Never retry POSTs: a retried "publish" could run twice.
     */
    private static void retryIdempotentGets(RetryGatewayFilterFactory.RetryConfig c) {
        c.setRetries(2)
                .setMethods(HttpMethod.GET)
                .setStatuses(HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE)
                .setExceptions(IOException.class, TimeoutException.class)
                .setBackoff(Duration.ofMillis(50), Duration.ofMillis(400), 2, true);
    }
}
