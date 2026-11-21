package com.pulse.gateway;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.List;

/**
 * Edge security: JWT validation for admin routes, CORS, security headers.
 *
 * <p>The gateway VERIFIES tokens but can't CREATE them: it only knows content-service's PUBLIC key,
 * fetched from {@code /.well-known/jwks.json} (asymmetric RS256, Phase 4). content-service still
 * validates the JWT and the CSRF token itself — defence in depth: a mistake in one layer isn't fatal.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    static final String ADMIN_PATHS = "/api/v1/admin/**";

    @Bean
    SecurityWebFilterChain edgeSecurity(ServerHttpSecurity http, ServerAuthenticationConverter adminTokenConverter) {
        return http
                // CSRF is enforced by content-service (double-submit cookie). The gateway passes the cookies and
                // the X-XSRF-TOKEN header through untouched — it must never turn the cookie into an
                // Authorization header downstream, or content-service would (correctly) skip its CSRF check.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance()) // stateless
                .cors(Customizer.withDefaults())
                .headers(h -> h
                        .cache(ServerHttpSecurity.HeaderSpec.CacheSpec::disable)   // keep downstream Cache-Control (CDN!)
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
                .authorizeExchange(a -> a
                        .pathMatchers(ADMIN_PATHS).hasRole("ADMIN")
                        .anyExchange().permitAll())
                .oauth2ResourceServer(rs -> rs
                        .bearerTokenConverter(adminTokenConverter)
                        .jwt(j -> j.jwtAuthenticationConverter(rolesFromClaim()))
                        .authenticationEntryPoint((ex, e) -> Problems.write(ex, HttpStatus.UNAUTHORIZED, "Unauthorized", "Authentication required"))
                        .accessDeniedHandler((ex, e) -> Problems.write(ex, HttpStatus.FORBIDDEN, "Forbidden", "Admin role required")))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((ex, err) -> Problems.write(ex, HttpStatus.UNAUTHORIZED, "Unauthorized", "Authentication required"))
                        .accessDeniedHandler((ex, err) -> Problems.write(ex, HttpStatus.FORBIDDEN, "Forbidden", "Admin role required")))
                .build();
    }

    /**
     * Find the JWT: "Authorization: Bearer …" or the HttpOnly pulse_at cookie — but ONLY on admin paths.
     * Gotcha avoided: if we looked at the cookie everywhere, an admin with an EXPIRED cookie browsing the public
     * site would get 401 on every public API call (an invalid token fails authentication even on permitAll routes).
     */
    @Bean
    ServerAuthenticationConverter adminTokenConverter() {
        ServerBearerTokenAuthenticationConverter header = new ServerBearerTokenAuthenticationConverter();
        return exchange -> {
            if (!exchange.getRequest().getPath().value().startsWith("/api/v1/admin/")) {
                return Mono.empty();
            }
            return header.convert(exchange).switchIfEmpty(Mono.defer(() -> {
                HttpCookie c = exchange.getRequest().getCookies().getFirst("pulse_at");
                return c == null || c.getValue().isBlank() ? Mono.empty() : Mono.just(new BearerTokenAuthenticationToken(c.getValue()));
            }));
        };
    }

    /** Verifies signature (keys from the JWKS URL, cached and re-fetched on unknown "kid"), expiry and issuer. */
    @Bean
    ReactiveJwtDecoder jwtDecoder(GatewayProps props) {
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)
                .responseTimeout(Duration.ofSeconds(2));               // never hang on a slow JWKS endpoint
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withJwkSetUri(props.contentUri() + "/.well-known/jwks.json")
                .webClient(WebClient.builder().clientConnector(new ReactorClientHttpConnector(http)).build())
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.jwtIssuer()));
        return decoder;
    }

    /** "roles": ["ADMIN"] → ROLE_ADMIN (same mapping as content-service). */
    private static ReactiveJwtAuthenticationConverterAdapter rolesFromClaim() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter c = new JwtAuthenticationConverter();
        c.setJwtGrantedAuthoritiesConverter(roles);
        return new ReactiveJwtAuthenticationConverterAdapter(c);
    }

    /** CORS now lives at the edge: the website origin may call the API with cookies. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(GatewayProps props) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(props.corsAllowedOrigins());
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "Idempotency-Key", "If-Match", RequestIdFilter.HEADER));
        c.setExposedHeaders(List.of(RequestIdFilter.HEADER, "Retry-After", "X-RateLimit-Remaining"));
        c.setAllowCredentials(true);
        c.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/api/**", c);
        return src;
    }
}
