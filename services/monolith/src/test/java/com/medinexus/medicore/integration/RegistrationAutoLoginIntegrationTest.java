package com.medinexus.medicore.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.medinexus.medicore.support.TestPersonnummer;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.repository.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Automatic sign-in after public registration, end to end over real HTTP with real accounts and BCrypt in
 * PostgreSQL ({@code medinexus_test} only). No FHIR request is involved.
 *
 * <p>Runs only when {@code DB_URL} names the {@code medinexus_test} database. Every account uses a random
 * {@code nit-autologin-...@example.test} e-mail and is deleted afterwards.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/medinexus_test")
class RegistrationAutoLoginIntegrationTest {

    @LocalServerPort
    private int port;
    @Value("${spring.datasource.url}")
    private String datasourceUrl;
    @Autowired
    private UserRepository users;

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> emails = new java.util.ArrayList<>();
    private final String password = "Synthetic-Passw0rd-" + UUID.randomUUID();

    @BeforeEach
    void onlyTheTestDatabase() {
        assertThat(datasourceUrl).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/medinexus_test");
    }

    @AfterEach
    void deleteCreatedAccounts() {
        emails.forEach(email -> users.findByEmail(email).ifPresent(users::delete));
    }

    private String newEmail(Role role) {
        String email = "nit-autologin-" + role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.test";
        emails.add(email);
        return email;
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private HttpResponse<String> post(HttpClient client, String path, String body) throws Exception {
        JsonNode token = json.readTree(client.send(request("/api/auth/csrf").GET().build(),
                HttpResponse.BodyHandlers.ofString()).body());
        return client.send(request(path).header(token.get("headerName").asString(), token.get("token").asString())
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> register(HttpClient client, String email, String secret, Role role) throws Exception {
        return post(client, "/api/auth/register",
                "{\"email\":\"" + email + "\",\"password\":\"" + secret + "\",\"role\":\"" + role + "\",\"givenName\":\"Synthetic\",\"familyName\":\"Testperson\",\"personnummer\":\"" + TestPersonnummer.next() + "\"}");
    }

    private HttpResponse<String> me(HttpClient client) throws Exception {
        return client.send(request("/api/auth/me").GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aNewPatientIsSignedInAndCanSignOutAndInAgain(CapturedOutput output) throws Exception {
        String email = newEmail(Role.PATIENT);
        try (HttpClient client = newClient()) {
            HttpResponse<String> registered = register(client, email, password, Role.PATIENT);
            assertThat(registered.statusCode()).isEqualTo(201);
            JsonNode account = json.readTree(registered.body());

            HttpResponse<String> me = me(client);
            assertThat(me.statusCode()).isEqualTo(200);
            assertThat(json.readTree(me.body())).isEqualTo(account);
            assertThat(account.get("role").asString()).isEqualTo("PATIENT");

            assertThat(post(client, "/api/auth/logout", null).statusCode()).isEqualTo(204);
            assertThat(me(client).statusCode()).isEqualTo(401);
            assertThat(post(client, "/api/auth/login",
                    "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}").statusCode()).isEqualTo(200);
            assertThat(me(client).statusCode()).isEqualTo(200);
        }
        assertThat(output.getAll().contains(password)).as("log contains the password").isFalse();
        assertThat(output.getAll()).doesNotContain("Automatic sign-in after registration failed");
    }

    @Test
    void doctorAndStaffRegistrationsAreNotSignedIn() throws Exception {
        for (Role role : List.of(Role.DOCTOR, Role.STAFF)) {
            try (HttpClient client = newClient()) {
                assertThat(register(client, newEmail(role), password, role).statusCode()).isEqualTo(201);
                assertThat(me(client).statusCode()).isEqualTo(401);
            }
        }
    }

    @Test
    void failedRegistrationsNeverSignIn() throws Exception {
        String email = newEmail(Role.PATIENT);
        try (HttpClient first = newClient()) {
            assertThat(register(first, email, password, Role.PATIENT).statusCode()).isEqualTo(201);
        }
        try (HttpClient client = newClient()) {
            // Duplicate e-mail with the correct password of the existing account: 409 and no session.
            assertThat(register(client, email, password, Role.PATIENT).statusCode()).isEqualTo(409);
            assertThat(me(client).statusCode()).isEqualTo(401);
            // Invalid data.
            assertThat(register(client, "not-an-email", password, Role.PATIENT).statusCode()).isEqualTo(400);
            assertThat(register(client, newEmail(Role.PATIENT), " ", Role.PATIENT).statusCode()).isEqualTo(400);
            assertThat(post(client, "/api/auth/register", "{invalid").statusCode()).isEqualTo(400);
            assertThat(me(client).statusCode()).isEqualTo(401);
        }
    }

    @Test
    void aSignedInUserWhoRegistersAnotherAccountKeepsTheirSession() throws Exception {
        String first = newEmail(Role.PATIENT);
        String second = newEmail(Role.PATIENT);
        try (HttpClient client = newClient()) {
            assertThat(register(client, first, password, Role.PATIENT).statusCode()).isEqualTo(201);
            assertThat(register(client, second, password, Role.PATIENT).statusCode()).isEqualTo(201);
            assertThat(json.readTree(me(client).body()).get("email").asString()).isEqualTo(first);
        }
        assertThat(users.findByEmail(second)).isPresent();
    }
}
