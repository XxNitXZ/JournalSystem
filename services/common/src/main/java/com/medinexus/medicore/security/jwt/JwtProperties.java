package com.medinexus.medicore.security.jwt;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the signed JWT that replaces the HTTP session (docs/architecture.md §6, NIT-327).
 *
 * @param secret       HS256 signing key shared by every service (env {@code JWT_SECRET}); at least 32 bytes
 * @param ttl          lifetime of a user token and its cookie; renewed by {@code GET /api/auth/me}
 * @param serviceTtl   lifetime of a service-to-service token
 * @param cookieName   name of the HttpOnly cookie that carries the user token
 * @param issuer       {@code iss} claim written and required on every token
 * @param secureCookie whether the cookie is sent over HTTPS only (true outside local HTTP development)
 */
@ConfigurationProperties("medinexus.jwt")
public record JwtProperties(
        String secret,
        @DefaultValue("30m") Duration ttl,
        @DefaultValue("60s") Duration serviceTtl,
        @DefaultValue("medicore_token") String cookieName,
        @DefaultValue("medicore") String issuer,
        @DefaultValue("false") boolean secureCookie) {

    /** HS256 needs a key of at least 256 bits. */
    static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        // Never include the secret itself in the message.
        if (secret == null || secret.isBlank() || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("medinexus.jwt.secret (env JWT_SECRET) must be set and at least "
                    + MIN_SECRET_BYTES + " bytes long");
        }
        if (ttl.isNegative() || ttl.isZero() || serviceTtl.isNegative() || serviceTtl.isZero()) {
            throw new IllegalArgumentException("medinexus.jwt.ttl and service-ttl must be positive");
        }
    }

    @Override
    public String toString() {
        return "JwtProperties[secret=<hidden>, ttl=" + ttl + ", serviceTtl=" + serviceTtl + ", cookieName="
                + cookieName + ", issuer=" + issuer + ", secureCookie=" + secureCookie + "]";
    }
}
