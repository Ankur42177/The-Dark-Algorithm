package com.pulse.content.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * {@code @Configuration} classes declare beans with {@code @Bean} methods: "Spring, call this method
 * once at startup and keep the returned object in your container".
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** Comma-separated list, e.g. "http://localhost:3000". Empty = no cross-origin browser calls allowed. */
    @Value("${pulse.cors.allowed-origins:}")
    private String[] allowedOrigins;

    /**
     * CORS (Cross-Origin Resource Sharing). Browsers block JavaScript on http://localhost:3000 from
     * reading responses from http://localhost:8081 (a different "origin") unless the server says it's OK.
     * The website's "Load more" button needs this. From Phase 5 the api-gateway owns CORS instead.
     * GET for reads, POST only for the fire-and-forget view beacon (Phase 3).
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins.length == 0) {
            return;
        }
        registry.addMapping("/api/v1/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST")
                .maxAge(3600);
    }

    /**
     * ETags for the public API.
     *
     * <p>How it works: the filter hashes the response body and sends it as an {@code ETag} header.
     * Next time, the client sends {@code If-None-Match: "<hash>"}. If the new body hashes to the same
     * value, the server answers {@code 304 Not Modified} with an EMPTY body: less bandwidth, faster
     * on slow phones.
     *
     * <p>"Shallow" = the server still does all the work (DB query, JSON) and only saves the network
     * transfer. Saving the server work too is what Redis caching (Phase 3) is for.
     */
    @Bean
    public FilterRegistrationBean<ShallowEtagHeaderFilter> etagFilter() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration =
                new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        registration.addUrlPatterns("/api/v1/*");
        registration.setName("etagFilter");
        return registration;
    }
}
