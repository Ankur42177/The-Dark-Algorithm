package com.pulse.ingestion.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * ingestion-service never issues tokens. It VERIFIES content-service's JWTs with the PUBLIC keys from
 * GET /.well-known/jwks.json (asymmetric signing, Phase 4): it can check a token but could never forge one.
 * Same cookie + CSRF rules as content-service (the XSRF-TOKEN cookie is shared on the same host).
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.spa())
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().permitAll())
                .oauth2ResourceServer(rs -> rs.jwt(j -> j.jwtAuthenticationConverter(converter)))
                .addFilterAfter(new CookieToBearerFilter(), CsrfFilter.class);
        return http.build();
    }

    /** Fetches and caches the JWKS from content-service; checks signature, expiry and issuer. */
    @Bean
    JwtDecoder jwtDecoder(@Value("${pulse.auth.jwks-uri:http://localhost:8081/.well-known/jwks.json}") String jwksUri,
                          @Value("${pulse.auth.issuer:https://pulse.local}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${pulse.cors.allowed-origins:http://localhost:3000}") String origins) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        c.setAllowedMethods(List.of("GET", "POST", "PATCH"));
        c.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));
        c.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/api/**", c);
        return src;
    }

    /** Browser requests carry the token in the HttpOnly pulse_at cookie; copy it AFTER the CSRF check (see content-service). */
    static class CookieToBearerFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
            String token = null;
            if (req.getHeader(HttpHeaders.AUTHORIZATION) == null && req.getCookies() != null) {
                for (Cookie c : req.getCookies()) {
                    if ("pulse_at".equals(c.getName()) && !c.getValue().isBlank()) {
                        token = c.getValue();
                    }
                }
            }
            if (token == null) {
                chain.doFilter(req, res);
                return;
            }
            String bearer = "Bearer " + token;
            chain.doFilter(new HttpServletRequestWrapper(req) {
                @Override
                public String getHeader(String name) {
                    return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name) ? bearer : super.getHeader(name);
                }

                @Override
                public Enumeration<String> getHeaders(String name) {
                    return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name) ? Collections.enumeration(List.of(bearer)) : super.getHeaders(name);
                }
            }, res);
        }
    }
}
