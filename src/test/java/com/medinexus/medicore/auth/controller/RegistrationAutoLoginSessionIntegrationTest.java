package com.medinexus.medicore.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import com.medinexus.medicore.auth.service.AuthenticationService;
import com.medinexus.medicore.auth.service.RegistrationService;
import com.medinexus.medicore.config.CorsConfig;
import com.medinexus.medicore.config.SecurityConfig;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Automatic sign-in after registration in a real servlet container (cookies, session ID, CSRF), without a
 * database: the registration and authentication services are mocked.
 */
@SpringBootTest(classes = RegistrationAutoLoginSessionIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RegistrationAutoLoginSessionIntegrationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({SecurityConfig.class, CorsConfig.class, AuthenticationController.class, RegistrationController.class})
    static class TestApplication {
    }

    private static final String PASSWORD = "a secure password";

    @LocalServerPort
    private int port;

    @MockitoBean
    private AuthenticationService authenticationService;

    @MockitoBean
    private RegistrationService registrationService;

    private final ObjectMapper json = new ObjectMapper();

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth" + path));
    }

    private JsonNode csrf(HttpClient client) throws Exception {
        return json.readTree(client.send(request("/csrf").GET().build(), HttpResponse.BodyHandlers.ofString()).body());
    }

    private HttpResponse<String> post(HttpClient client, JsonNode token, String path, String body) throws Exception {
        return client.send(request(path).header(token.get("headerName").asString(), token.get("token").asString())
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String registration(Role role) {
        return """
                {"email":"new@example.com","password":"a secure password","role":"%s","givenName":"Anna","familyName":"Testsson","personnummer":"182003151236"}
                """.formatted(role);
    }

    private static String sessionCookie(CookieManager cookies) {
        return cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("JSESSIONID"))
                .map(cookie -> cookie.getName() + "=" + cookie.getValue()).findFirst().orElseThrow();
    }

    private HttpResponse<String> me(HttpClient client) throws Exception {
        return client.send(request("/me").GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void patientRegistrationStartsANewSessionRotatesCsrfAndCanBeSignedOut() throws Exception {
        UserDto user = new UserDto(42L, "new@example.com", Role.PATIENT);
        when(registrationService.register("new@example.com", PASSWORD, Role.PATIENT, "Anna", "Testsson", "182003151236", null)).thenReturn(user);
        when(authenticationService.authenticate("new@example.com", PASSWORD)).thenReturn(user);
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build()) {
            JsonNode anonymousToken = csrf(client);
            String anonymousCookie = sessionCookie(cookies);

            HttpResponse<String> registered = post(client, anonymousToken, "/register", registration(Role.PATIENT));
            assertThat(registered.statusCode()).isEqualTo(201);
            assertThat(registered.headers().firstValue("set-cookie").orElseThrow()).contains("JSESSIONID=", "HttpOnly");
            assertThat(sessionCookie(cookies)).isNotEqualTo(anonymousCookie);

            HttpResponse<String> me = me(client);
            assertThat(me.statusCode()).isEqualTo(200);
            assertThat(json.readTree(me.body()).get("email").asString()).isEqualTo("new@example.com");
            assertThat(json.readTree(me.body()).get("role").asString()).isEqualTo("PATIENT");

            // The CSRF token was rotated on sign-in: the token from before registration no longer works.
            assertThat(post(client, anonymousToken, "/logout", null).statusCode()).isEqualTo(403);
            assertThat(me(client).statusCode()).isEqualTo(200);

            String authenticatedCookie = sessionCookie(cookies);
            assertThat(post(client, csrf(client), "/logout", null).statusCode()).isEqualTo(204);
            assertThat(me(client).statusCode()).isEqualTo(401);
            try (HttpClient replay = HttpClient.newHttpClient()) {
                assertThat(replay.send(request("/me").header("Cookie", authenticatedCookie).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
                // The pre-registration session never became authenticated.
                assertThat(replay.send(request("/me").header("Cookie", anonymousCookie).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void clinicalRolesMustStillSignInManually(Role role) throws Exception {
        UserDto user = new UserDto(43L, "new@example.com", role);
        when(registrationService.register("new@example.com", PASSWORD, role, "Anna", "Testsson", "182003151236", null)).thenReturn(user);
        when(authenticationService.authenticate("new@example.com", PASSWORD)).thenReturn(user);
        try (HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            assertThat(post(client, csrf(client), "/register", registration(role)).statusCode()).isEqualTo(201);
            assertThat(me(client).statusCode()).isEqualTo(401);
            // Existing behaviour unchanged: an explicit login still works.
            assertThat(post(client, csrf(client), "/login", """
                    {"email":"new@example.com","password":"a secure password"}
                    """).statusCode()).isEqualTo(200);
            assertThat(me(client).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void aDuplicateRegistrationWithCorrectCredentialsDoesNotSignIn() throws Exception {
        when(registrationService.register("new@example.com", PASSWORD, Role.PATIENT, "Anna", "Testsson", "182003151236", null))
                .thenThrow(new DuplicateKeyException("Email is already registered"));
        when(authenticationService.authenticate("new@example.com", PASSWORD))
                .thenReturn(new UserDto(7L, "new@example.com", Role.PATIENT));
        try (HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            assertThat(post(client, csrf(client), "/register", registration(Role.PATIENT)).statusCode()).isEqualTo(409);
            assertThat(me(client).statusCode()).isEqualTo(401);
        }
        verify(authenticationService, never()).authenticate(any(), any());
    }
}
