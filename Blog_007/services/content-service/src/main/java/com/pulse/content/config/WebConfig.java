package com.pulse.content.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * {@code @Configuration} classes declare beans with {@code @Bean} methods: "Spring, call this method
 * once at startup and keep the returned object in your container".
 */
@Configuration
public class WebConfig {

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
