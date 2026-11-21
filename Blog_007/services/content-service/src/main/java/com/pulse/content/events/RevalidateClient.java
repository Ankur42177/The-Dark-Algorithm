package com.pulse.content.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Calls the website's POST /api/revalidate so Next.js rebuilds the pages showing a changed post.
 * Timeouts on every outbound call (PLAN §16.4): 2 s to connect, 5 s to answer.
 * Disabled when no secret is configured (e.g. in tests).
 */
@Component
public class RevalidateClient {

    private static final Logger log = LoggerFactory.getLogger(RevalidateClient.class);

    private final RestClient http;
    private final String secret;
    private final boolean enabled;

    public RevalidateClient(@Value("${pulse.web.revalidate-url:http://localhost:3000/api/revalidate}") String url,
                            @Value("${pulse.web.revalidate-secret:}") String secret) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().baseUrl(url).requestFactory(factory).build();
        this.secret = secret;
        this.enabled = secret != null && !secret.isBlank();
        if (!enabled) {
            log.warn("pulse.web.revalidate-secret not set: website revalidation is disabled");
        }
    }

    /** Throws on failure so the Kafka error handler retries (and finally dead-letters) the event. */
    public void revalidate(List<String> tags) {
        if (!enabled) {
            return;
        }
        http.post()
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-revalidate-secret", secret)
                .body(Map.of("tags", tags))
                .retrieve()
                .toBodilessEntity();
    }
}
