package com.medinexus.medicore.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

import com.medinexus.medicore.auth.entity.Role;

/** Signing, verification and claim checks of the JWT package, without Spring. */
class JwtTokenServiceTest {

    private final JwtProperties properties = TestTokens.properties();
    private final JwtSecurityConfig config = new JwtSecurityConfig();
    private final JwtDecoder decoder = config.jwtDecoder(properties);

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "too-short-31-bytes-0123456789ab"})
    void aMissingOrShortSecretStopsStartup(String secret) {
        assertThatThrownBy(() -> new JwtProperties(secret, Duration.ofMinutes(30), Duration.ofSeconds(60),
                "medicore_token", "medicore", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void theSecretIsNeverPrinted() {
        assertThat(properties.toString()).doesNotContain(TestTokens.SECRET).contains("<hidden>");
    }

    @Test
    void aUserTokenRoundTripsWithItsClaims() {
        String token = TestTokens.tokens().userToken(new UserClaims(7L, "anna@example.test", Role.DOCTOR, "Anna",
                "Testsson", null, "prac-1"));
        var jwt = decoder.decode(token);
        assertThat(jwt.getSubject()).isEqualTo("7");
        assertThat(jwt.getClaimAsString("typ")).isEqualTo("user");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("DOCTOR");
        assertThat(jwt.getClaimAsString("fhirPractitionerId")).isEqualTo("prac-1");
        assertThat(jwt.hasClaim("fhirPatientId")).isFalse();
        assertThat(jwt.getExpiresAt()).isEqualTo(jwt.getIssuedAt().plus(Duration.ofMinutes(30)));
    }

    @Test
    void aServiceTokenIsShortLivedAndHasTheServiceRole() {
        var jwt = decoder.decode(TestTokens.tokens().serviceToken("messaging"));
        assertThat(jwt.getClaimAsString("role")).isEqualTo("SERVICE");
        assertThat(jwt.getClaimAsString("typ")).isEqualTo("service");
        assertThat(jwt.getExpiresAt()).isEqualTo(jwt.getIssuedAt().plusSeconds(60));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Clinical", "../etc", "a service"})
    void invalidServiceNamesAreRefused(String name) {
        assertThatThrownBy(() -> TestTokens.tokens().serviceToken(name)).isInstanceOf(IllegalArgumentException.class);
    }

    /** Correctly signed with our key, but with claims MediCore never writes: still rejected. */
    @ParameterizedTest
    @ValueSource(strings = {"no-type", "admin-role", "service-claims-user-role", "user-with-text-id", "user-no-email",
            "other-issuer"})
    void correctlySignedTokensWithUnexpectedClaimsAreRejected(String variant) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuer("medicore").subject("42").issuedAt(now)
                .expiresAt(now.plusSeconds(600)).claim("typ", "user").claim("role", "PATIENT")
                .claim("email", "user@example.com");
        switch (variant) {
            case "no-type" -> claims.claims(map -> map.remove("typ"));
            case "admin-role" -> claims.claim("role", "ADMIN");
            case "service-claims-user-role" -> claims.claim("typ", "service").subject("clinical");
            case "user-with-text-id" -> claims.subject("clinical");
            case "user-no-email" -> claims.claims(map -> map.remove("email"));
            case "other-issuer" -> claims.issuer("someone-else");
            default -> throw new IllegalArgumentException(variant);
        }
        String token = config.jwtEncoder(properties).encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void theCookieIsHttpOnlyStrictAndSiteWide() {
        String cookie = TestTokens.tokens().tokenCookie("abc").toString();
        assertThat(cookie).contains("medicore_token=abc", "HttpOnly", "SameSite=Strict", "Path=/", "Max-Age=1800")
                .doesNotContain("Secure");
        assertThat(TestTokens.tokens().clearedCookie().toString()).contains("medicore_token=", "Max-Age=0");
    }
}
