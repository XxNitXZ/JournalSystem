package com.medinexus.medicore.auth.controller;

import static com.medinexus.medicore.auth.TestAccounts.account;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.auth.service.AccountTokenService;
import com.medinexus.medicore.auth.service.AuthenticationService;
import com.medinexus.medicore.auth.service.RegistrationService;
import com.medinexus.medicore.config.CorsConfig;
import com.medinexus.medicore.config.SecurityConfig;
import com.medinexus.medicore.security.jwt.JwtProperties;
import com.medinexus.medicore.security.jwt.JwtSecurityConfig;
import com.medinexus.medicore.security.jwt.JwtTokenService;
import com.medinexus.medicore.security.jwt.TestTokens;
import com.medinexus.medicore.security.jwt.UserClaims;

import tools.jackson.databind.ObjectMapper;

/**
 * The JWT cookie over real HTTP with a real cookie jar, as a browser uses it (NIT-327). Replaces the Lab 1 session
 * integration tests. Only the account store and password check are mocked.
 */
@SpringBootTest(classes = JwtCookieAuthenticationIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JwtCookieAuthenticationIntegrationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({SecurityConfig.class, CorsConfig.class, AuthenticationController.class, RegistrationController.class,
            AccountTokenService.class})
    static class TestApplication {
    }

    private static final String PASSWORD = "a secure password";
    private static final String LOGIN = "{\"email\":\"user@example.com\",\"password\":\"a secure password\"}";

    @LocalServerPort
    private int port;

    @MockitoBean
    private AuthenticationService authenticationService;

    @MockitoBean
    private RegistrationService registrationService;

    @MockitoBean
    private UserRepository users;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void storedAccount() {
        when(users.findById(42L)).thenReturn(Optional.of(account(42L, "user@example.com", Role.PATIENT, "Anna",
                "Testsson")));
        when(authenticationService.authenticate("user@example.com", PASSWORD))
                .thenReturn(new UserDto(42L, "user@example.com", Role.PATIENT, "Anna", "Testsson"));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private static HttpClient browser(CookieManager cookies) {
        return HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    private HttpResponse<String> send(HttpClient client, HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** POST the way MediPortal still does until NIT-338: fetch the no-op CSRF token, then send it as a header. */
    private HttpResponse<String> mediPortalPost(HttpClient client, String path, String body) throws Exception {
        var csrf = json.readTree(send(client, request("/api/auth/csrf").GET().build()).body());
        return send(client, request(path).header(csrf.get("headerName").asString(), csrf.get("token").asString())
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    private HttpResponse<String> me(HttpClient client) throws Exception {
        return send(client, request("/api/auth/me").GET().build());
    }

    private HttpResponse<String> meWithToken(String token) throws Exception {
        try (HttpClient fresh = HttpClient.newHttpClient()) {
            return send(fresh, request("/api/auth/me").header("Cookie", "medicore_token=" + token).GET().build());
        }
    }

    private static String token(CookieManager cookies) {
        return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("medicore_token"))
                .map(HttpCookie::getValue).findFirst().orElse(null);
    }

    @Test
    void loginSetsTheCookieTheBrowserSendsBackAndLogoutRemovesIt() throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (HttpClient client = browser(cookies)) {
            assertThat(me(client).statusCode()).isEqualTo(401);

            HttpResponse<String> login = mediPortalPost(client, "/api/auth/login", LOGIN);
            assertThat(login.statusCode()).isEqualTo(200);
            String setCookie = login.headers().firstValue("set-cookie").orElseThrow();
            assertThat(setCookie).startsWith("medicore_token=")
                    .contains("HttpOnly", "SameSite=Strict", "Path=/", "Max-Age=1800")
                    .doesNotContain("JSESSIONID");
            assertThat(login.headers().allValues("set-cookie")).noneMatch(value -> value.startsWith("JSESSIONID"));

            HttpResponse<String> me = me(client);
            assertThat(me.statusCode()).isEqualTo(200);
            assertThat(json.readTree(me.body()).get("email").asString()).isEqualTo("user@example.com");

            assertThat(mediPortalPost(client, "/api/auth/logout", null).statusCode()).isEqualTo(204);
            assertThat(token(cookies)).as("the browser dropped the cookie").isNull();
            assertThat(me(client).statusCode()).isEqualTo(401);
        }
    }

    @Test
    void aCopiedTokenStaysValidUntilItExpiresEvenAfterLogout() throws Exception {
        // Documented trade-off of stateless tokens (docs/architecture.md §9): logout removes the cookie from this
        // browser, but nothing on the server remembers it. A token copied before logout keeps working for at most
        // the 30-minute lifetime. The Lab 1 session version of this test asserted the opposite.
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (HttpClient client = browser(cookies)) {
            mediPortalPost(client, "/api/auth/login", LOGIN);
            String copied = token(cookies);
            mediPortalPost(client, "/api/auth/logout", null);
            assertThat(meWithToken(copied).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void forgedExpiredAndTamperedTokensAreRejected() throws Exception {
        UserClaims patient = new UserClaims(42L, "user@example.com", Role.PATIENT, null, null, null, null);

        // Signed with another key: a forgery.
        JwtProperties otherKey = new JwtProperties("a-different-secret-that-is-also-long-enough-0123", Duration.ofMinutes(30),
                Duration.ofSeconds(60), "medicore_token", "medicore", false);
        String forged = new JwtTokenService(new JwtSecurityConfig().jwtEncoder(otherKey), otherKey,
                java.time.Clock.systemUTC()).userToken(patient);
        assertThat(meWithToken(forged).statusCode()).isEqualTo(401);

        // Expired an hour ago (beyond the 60-second clock skew).
        String expired = TestTokens.tokens(Instant.now().minusSeconds(3600)).userToken(patient);
        assertThat(meWithToken(expired).statusCode()).isEqualTo(401);

        // A valid token whose payload was edited to claim the DOCTOR role: the signature no longer matches.
        String valid = TestTokens.tokens().userToken(patient);
        String[] parts = valid.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"PATIENT\"", "\"DOCTOR\"");
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        assertThat(meWithToken(tampered).statusCode()).isEqualTo(401);

        // Not a JWT at all.
        assertThat(meWithToken("garbage").statusCode()).isEqualTo(401);

        // The untouched token works, so the checks above failed for the right reason.
        assertThat(meWithToken(valid).statusCode()).isEqualTo(200);
    }

    @Test
    void aServiceTokenIsNotAUserSignIn() throws Exception {
        String service = TestTokens.tokens().serviceToken("clinical");
        assertThat(meWithToken(service).statusCode()).isEqualTo(403);
    }

    @Test
    void patientRegistrationSignsInWithTheCookie() throws Exception {
        when(registrationService.register("user@example.com", PASSWORD, Role.PATIENT, "Anna", "Testsson",
                "182003151236", null)).thenReturn(new UserDto(42L, "user@example.com", Role.PATIENT, "Anna", "Testsson"));
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (HttpClient client = browser(cookies)) {
            assertThat(mediPortalPost(client, "/api/auth/register", registration(Role.PATIENT)).statusCode())
                    .isEqualTo(201);
            assertThat(me(client).statusCode()).isEqualTo(200);
        }
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void clinicalRolesMustStillSignInManually(Role role) throws Exception {
        when(registrationService.register("user@example.com", PASSWORD, role, "Anna", "Testsson", "182003151236", null))
                .thenReturn(new UserDto(43L, "user@example.com", role));
        try (HttpClient client = browser(new CookieManager(null, CookiePolicy.ACCEPT_ALL))) {
            assertThat(mediPortalPost(client, "/api/auth/register", registration(role)).statusCode()).isEqualTo(201);
            assertThat(me(client).statusCode()).isEqualTo(401);
        }
    }

    @Test
    void aDuplicateRegistrationWithCorrectCredentialsDoesNotSignIn() throws Exception {
        when(registrationService.register("user@example.com", PASSWORD, Role.PATIENT, "Anna", "Testsson",
                "182003151236", null)).thenThrow(new DuplicateKeyException("Email is already registered"));
        try (HttpClient client = browser(new CookieManager(null, CookiePolicy.ACCEPT_ALL))) {
            assertThat(mediPortalPost(client, "/api/auth/register", registration(Role.PATIENT)).statusCode())
                    .isEqualTo(409);
            assertThat(me(client).statusCode()).isEqualTo(401);
        }
        verify(authenticationService, never()).authenticate(any(), any());
    }

    @ParameterizedTest
    @CsvSource({"/v3/api-docs, 200", "/v3/api-docs.yaml, 200", "/v3/api-docs/swagger-config, 200",
            "/swagger-ui.html, 302", "/swagger-ui/index.html, 200", "/swagger-ui/swagger-ui.css, 200"})
    void documentationRemainsPublic(String path, int expectedStatus) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertThat(send(client, request(path).GET().build()).statusCode()).isEqualTo(expectedStatus);
        }
    }

    private static String registration(Role role) {
        return """
                {"email":"user@example.com","password":"a secure password","role":"%s","givenName":"Anna","familyName":"Testsson","personnummer":"182003151236"}
                """.formatted(role);
    }
}
