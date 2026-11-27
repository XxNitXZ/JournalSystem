package com.medinexus.medicore.security.jwt;

import java.util.List;

import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Finds the token for a request: an {@code Authorization: Bearer} header first (service-to-service calls), otherwise
 * the user token cookie (the browser).
 *
 * <p>On public endpoints a token that fails verification is ignored instead of rejected. Otherwise an expired cookie
 * left in the browser would make login or registration answer 401. On every other endpoint an invalid token is
 * returned as-is, so the resource server rejects it with 401.
 */
public class CookieBearerTokenResolver implements BearerTokenResolver {

    private record PublicEndpoint(HttpMethod method, String path) {
    }

    private static final List<PublicEndpoint> PUBLIC_ENDPOINTS = List.of(
            new PublicEndpoint(HttpMethod.POST, "/api/auth/login"),
            new PublicEndpoint(HttpMethod.POST, "/api/auth/register"),
            new PublicEndpoint(HttpMethod.POST, "/api/auth/logout"),
            new PublicEndpoint(HttpMethod.GET, "/api/auth/csrf"),
            new PublicEndpoint(HttpMethod.GET, "/api/health"),
            new PublicEndpoint(HttpMethod.GET, "/actuator/health"));

    private final DefaultBearerTokenResolver headerResolver = new DefaultBearerTokenResolver();
    private final String cookieName;
    private final JwtDecoder decoder;

    public CookieBearerTokenResolver(String cookieName, JwtDecoder decoder) {
        this.cookieName = cookieName;
        this.decoder = decoder;
    }

    @Override
    public String resolve(HttpServletRequest request) {
        String token = headerResolver.resolve(request);
        if (token == null) token = cookieValue(request);
        if (token == null || !isPublic(request)) return token;
        try {
            decoder.decode(token);
            return token;
        } catch (JwtException unusable) {
            return null;
        }
    }

    private String cookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static boolean isPublic(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui")) return true;
        for (PublicEndpoint endpoint : PUBLIC_ENDPOINTS) {
            if (endpoint.method().matches(request.getMethod()) && endpoint.path().equals(path)) return true;
        }
        return false;
    }
}
