package com.pulse.content.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Browser requests carry the access token in the HttpOnly {@code pulse_at} cookie. This filter — placed
 * AFTER Spring Security's CsrfFilter — copies it into an {@code Authorization: Bearer} header, so the
 * standard JWT filter can validate it. Order matters: CSRF is checked first, while the request still
 * looks cookie-authenticated.
 */
class CookieToBearerFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(HttpHeaders.AUTHORIZATION) == null ? fromCookie(request) : null;
        if (token == null) {
            chain.doFilter(request, response);
            return;
        }
        String bearer = "Bearer " + token;
        // Requests are immutable, so we wrap it and answer header lookups for "Authorization" ourselves.
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {
                return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name) ? bearer : super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)
                        ? Collections.enumeration(List.of(bearer)) : super.getHeaders(name);
            }
        }, response);
    }

    private static String fromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie c : request.getCookies()) {
            if (AuthController.ACCESS_COOKIE.equals(c.getName()) && !c.getValue().isBlank()) {
                return c.getValue();
            }
        }
        return null;
    }
}
