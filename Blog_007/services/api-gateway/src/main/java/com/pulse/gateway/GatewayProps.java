package com.pulse.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Everything environment-specific about the gateway ("pulse.gateway.*"). Locally the downstream URIs
 * are localhost ports; in Kubernetes (Phase 10) they become DNS names like http://content-service:8081.
 *
 * @param contentUri          content-service base URI
 * @param ingestionUri        ingestion-service base URI
 * @param assistantUri        assistant-service base URI
 * @param corsAllowedOrigins  browser origins allowed to call the API with cookies
 * @param jwtIssuer           expected "iss" claim of admin JWTs
 * @param trustedProxyHops    how many proxies in front of us we trust for X-Forwarded-For (0 = we ARE the edge)
 * @param rateLimitMultiplier scales every bucket's size and refill (1 = PLAN limits; large = load tests)
 */
@ConfigurationProperties("pulse.gateway")
public record GatewayProps(
        String contentUri,
        String ingestionUri,
        String assistantUri,
        List<String> corsAllowedOrigins,
        String jwtIssuer,
        int trustedProxyHops,
        int rateLimitMultiplier) {
}
