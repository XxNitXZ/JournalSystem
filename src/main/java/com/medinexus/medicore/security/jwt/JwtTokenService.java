package com.medinexus.medicore.security.jwt;

import java.time.Clock;
import java.time.Instant;

import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/**
 * Signs MediCore tokens and builds the cookie that carries them.
 *
 * <p>Two kinds of token, told apart by the {@code typ} claim:
 * <ul>
 *   <li><b>user</b>: issued by Identity at login, sent by the browser as the {@code HttpOnly} cookie. Lives
 *       {@link JwtProperties#ttl()} and is renewed by {@code GET /api/auth/me}.</li>
 *   <li><b>service</b>: signed by a service for one call to another service's {@code /internal/**} endpoint, sent
 *       as {@code Authorization: Bearer}. Carries {@code role=SERVICE} and lives {@link JwtProperties#serviceTtl()}.
 *       </li>
 * </ul>
 * Tokens can't be revoked: a copied token stays valid until it expires, even after logout. That is the trade-off
 * of stateless tokens (docs/architecture.md §9).
 */
public class JwtTokenService {

    public static final String TYPE_USER = "user";
    public static final String TYPE_SERVICE = "service";
    public static final String SERVICE_ROLE = "SERVICE";

    public static final String CLAIM_TYPE = "typ";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_GIVEN_NAME = "givenName";
    public static final String CLAIM_FAMILY_NAME = "familyName";
    public static final String CLAIM_FHIR_PATIENT_ID = "fhirPatientId";
    public static final String CLAIM_FHIR_PRACTITIONER_ID = "fhirPractitionerId";

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;

    public JwtTokenService(JwtEncoder encoder, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    /** A signed user token for the account. */
    public String userToken(UserClaims account) {
        Instant now = clock.instant();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(account.accountId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.ttl()))
                .claim(CLAIM_TYPE, TYPE_USER)
                .claim(CLAIM_ROLE, account.role().name())
                .claim(CLAIM_EMAIL, account.email());
        putIfPresent(claims, CLAIM_GIVEN_NAME, account.givenName());
        putIfPresent(claims, CLAIM_FAMILY_NAME, account.familyName());
        putIfPresent(claims, CLAIM_FHIR_PATIENT_ID, account.fhirPatientId());
        putIfPresent(claims, CLAIM_FHIR_PRACTITIONER_ID, account.fhirPractitionerId());
        return sign(claims.build());
    }

    /** A short-lived token that lets {@code serviceName} call another service's {@code /internal/**} endpoints. */
    public String serviceToken(String serviceName) {
        if (serviceName == null || !serviceName.matches("[a-z][a-z0-9-]{0,31}")) {
            throw new IllegalArgumentException("Invalid service name");
        }
        Instant now = clock.instant();
        return sign(JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(serviceName)
                .issuedAt(now)
                .expiresAt(now.plus(properties.serviceTtl()))
                .claim(CLAIM_TYPE, TYPE_SERVICE)
                .claim(CLAIM_ROLE, SERVICE_ROLE)
                .build());
    }

    /** The cookie that carries a user token: HttpOnly, SameSite=Strict, whole site, removed when the token expires. */
    public ResponseCookie tokenCookie(String token) {
        return cookie(token, properties.ttl().toSeconds());
    }

    /** Overwrites the token cookie with an empty, already expired one (logout). */
    public ResponseCookie clearedCookie() {
        return cookie("", 0);
    }

    public String cookieName() {
        return properties.cookieName();
    }

    private ResponseCookie cookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(properties.cookieName(), value)
                .httpOnly(true)
                .secure(properties.secureCookie())
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
    }

    private String sign(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static void putIfPresent(JwtClaimsSet.Builder claims, String name, String value) {
        if (value != null) {
            claims.claim(name, value);
        }
    }
}
