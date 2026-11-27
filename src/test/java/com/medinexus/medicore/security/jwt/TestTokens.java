package com.medinexus.medicore.security.jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;

import jakarta.servlet.http.Cookie;

/** Test helpers for the JWT cookie: real tokens signed with the test-only key from config/application.properties. */
public final class TestTokens {

    /** Same value as src/test/resources/config/application.properties. */
    public static final String SECRET = "test-only-jwt-secret-for-medicore-unit-tests-0123456789";
    public static final String COOKIE = "medicore_token";

    private TestTokens() {
    }

    public static JwtProperties properties() {
        return new JwtProperties(SECRET, Duration.ofMinutes(30), Duration.ofSeconds(60), COOKIE, "medicore", false);
    }

    /** A token service that signs with the test key, as of {@code now}. */
    public static JwtTokenService tokens(Instant now) {
        JwtProperties properties = properties();
        return new JwtTokenService(new JwtSecurityConfig().jwtEncoder(properties), properties,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    public static JwtTokenService tokens() {
        return tokens(Instant.now());
    }

    /** A cookie carrying a valid user token for these claims. */
    public static Cookie cookieFor(UserClaims claims) {
        return new Cookie(COOKIE, tokens().userToken(claims));
    }

    /** The token cookie the response set, or null if it set none. */
    public static Cookie tokenCookie(MvcResult result) {
        MockHttpServletResponse response = result.getResponse();
        return response.getCookie(COOKIE);
    }

    /** A stored account as the repository would return it. */
    public static User account(Long id, String email, Role role, String givenName, String familyName) {
        User account = new User();
        ReflectionTestUtils.setField(account, "id", id);
        account.setEmail(email);
        account.setRole(role);
        account.setGivenName(givenName);
        account.setFamilyName(familyName);
        account.setPasswordHash("{noop}unused");
        return account;
    }
}
