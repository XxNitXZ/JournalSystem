package com.medinexus.medicore.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.medinexus.medicore.support.TestPersonnummer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Account management end to end over real HTTP with session cookies and CSRF, against PostgreSQL
 * ({@code medinexus_test} only): optional phone number at registration (V9), read-only names and personnummer,
 * e-mail change with the current password, phone number changes and password changes.
 *
 * <p>Runs only when {@code DB_URL} names the {@code medinexus_test} database. Every row uses a random
 * {@code nit-profile-...@example.test} e-mail and is deleted afterwards; no other row is read or changed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/medinexus_test")
class ProfileUpdateIntegrationTest {

    @LocalServerPort
    private int port;
    @Value("${spring.datasource.url}")
    private String datasourceUrl;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final ObjectMapper json = new ObjectMapper();
    private final List<Long> accounts = new ArrayList<>();
    private final String password = "Synthetic-Passw0rd-" + UUID.randomUUID();

    @BeforeEach
    void onlyTheTestDatabase() {
        assertThat(datasourceUrl).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/medinexus_test");
    }

    @AfterEach
    void deleteCreatedRows() {
        accounts.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
    }

    private static String newEmail() {
        return "nit-profile-" + UUID.randomUUID() + "@example.test";
    }

    /** Inserts an account directly, optionally without names, personnummer or phone (as older accounts). */
    private String account(String role, String givenName, String familyName, String personnummer) {
        String email = newEmail();
        jdbc.update("INSERT INTO users (email, password_hash, role, given_name, family_name, personnummer) "
                + "VALUES (?, ?, ?, ?, ?, ?)", email, passwordEncoder.encode(password), role, givenName, familyName,
                personnummer);
        accounts.add(id(email));
        return email;
    }

    private long id(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private Map<String, Object> row(long id) {
        return new HashMap<>(jdbc.queryForMap("SELECT * FROM users WHERE id = ?", id));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    private HttpResponse<String> send(HttpClient client, String method, String path, Object body, boolean withCsrf)
            throws Exception {
        HttpRequest.Builder builder = request(path).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (withCsrf) {
            JsonNode token = json.readTree(client.send(request("/api/auth/csrf").GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            builder.header(token.get("headerName").asString(), token.get("token").asString());
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> login(HttpClient client, String email, String secret) throws Exception {
        return send(client, "POST", "/api/auth/login", Map.of("email", email, "password", secret), true);
    }

    private HttpClient signedIn(String email) throws Exception {
        HttpClient client = newClient();
        assertThat(login(client, email, password).statusCode()).isEqualTo(200);
        return client;
    }

    private JsonNode get(HttpClient client, String path) throws Exception {
        HttpResponse<String> response = client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> body = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) body.put((String) keyValues[i], keyValues[i + 1]);
        return body;
    }

    private void assertCode(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo(code);
    }

    // ---------- registration with an optional phone number ----------

    @Test
    void everyRoleRegistersWithOrWithoutAPhoneNumber(CapturedOutput output) throws Exception {
        try (HttpClient client = newClient()) {
            for (String role : List.of("PATIENT", "DOCTOR", "STAFF")) {
                String withPhone = newEmail();
                HttpResponse<String> registered = send(client, "POST", "/api/auth/register", map("email", withPhone,
                        "password", password, "role", role, "givenName", "Fiktiv", "familyName", "Testperson",
                        "personnummer", TestPersonnummer.next(), "phoneNumber", "+46 70-123 45 67"), true);
                assertThat(registered.statusCode()).isEqualTo(201);
                accounts.add(id(withPhone));
                assertThat(registered.body()).doesNotContain("701234567", "phone");
                assertThat(row(id(withPhone)).get("phone_number")).isEqualTo("+46701234567");

                String withoutPhone = newEmail();
                assertThat(send(client, "POST", "/api/auth/register", map("email", withoutPhone, "password", password,
                        "role", role, "givenName", "Fiktiv", "familyName", "Testperson", "personnummer",
                        TestPersonnummer.next()), true).statusCode()).isEqualTo(201);
                accounts.add(id(withoutPhone));
                assertThat(row(id(withoutPhone)).get("phone_number")).isNull();
                // A PATIENT registration signs in; sign out again for the next registration.
                send(client, "POST", "/api/auth/logout", Map.of(), true);
            }
            String invalid = newEmail();
            assertThat(send(client, "POST", "/api/auth/register", map("email", invalid, "password", password,
                    "role", "PATIENT", "givenName", "Fiktiv", "familyName", "Testperson", "personnummer",
                    TestPersonnummer.next(), "phoneNumber", "12"), true).statusCode()).isEqualTo(400);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, invalid)).isZero();
        }
        assertThat(output.getAll()).doesNotContain("+46701234567", "70-123 45 67");
    }

    @Test
    void anAccountCreatedBeforePhoneNumbersAndPersonnummerSignsInAndReadsItsProfile() throws Exception {
        String email = account("STAFF", null, null, null);
        try (HttpClient client = signedIn(email)) {
            JsonNode profile = get(client, "/api/auth/profile");
            assertThat(profile.get("email").asString()).isEqualTo(email);
            assertThat(profile.get("phoneNumber").isNull()).isTrue();
            assertThat(profile.get("personnummer").isNull()).isTrue();
            assertThat(profile.get("givenName").isNull()).isTrue();
        }
    }

    // ---------- immutable identity ----------

    @Test
    void namesAndPersonnummerCannotBeChangedByAnyRole() throws Exception {
        for (String role : List.of("PATIENT", "DOCTOR", "STAFF")) {
            String pnr = TestPersonnummer.next();
            String email = account(role, "Anna", "Testsson", pnr);
            Map<String, Object> before = row(id(email));
            try (HttpClient client = signedIn(email)) {
                JsonNode profile = get(client, "/api/auth/profile");
                assertThat(profile.get("givenName").asString()).isEqualTo("Anna");
                assertThat(profile.get("personnummer").asString()).isEqualTo(pnr);
                for (Map<String, Object> attempt : List.of(map("givenName", "Changed"), map("familyName", "Changed"),
                        map("personnummer", "200512241971"), map("role", "DOCTOR"), map("id", 1),
                        map("fhirPatientId", "syn-x"), map("phoneNumber", "0701234567", "givenName", "Changed"))) {
                    assertCode(send(client, "PATCH", "/api/auth/profile", attempt, true), 400, "FIELD_NOT_EDITABLE");
                }
            }
            assertThat(row(id(email))).isEqualTo(before);
        }
    }

    // ---------- e-mail ----------

    @Test
    void theEmailChangesWithTheCurrentPasswordAndTheNewAddressSignsIn() throws Exception {
        String email = account("PATIENT", "Anna", "Testsson", TestPersonnummer.next());
        String other = account("PATIENT", "Bert", "Annan", TestPersonnummer.next());
        String newAddress = newEmail();
        try (HttpClient client = signedIn(email)) {
            // Wrong or missing password, another account's address, an invalid address: nothing changes.
            assertCode(send(client, "PATCH", "/api/auth/profile", map("email", newAddress, "currentPassword", "wrong"),
                    true), 403, "CURRENT_PASSWORD_INVALID");
            assertCode(send(client, "PATCH", "/api/auth/profile", map("email", newAddress), true), 403,
                    "CURRENT_PASSWORD_INVALID");
            assertCode(send(client, "PATCH", "/api/auth/profile", map("email", other.toUpperCase(), "currentPassword",
                    password), true), 409, "EMAIL_TAKEN");
            assertCode(send(client, "PATCH", "/api/auth/profile", map("email", "not-an-email", "currentPassword",
                    password), true), 400, "EMAIL_INVALID");
            assertThat(row(id(email)).get("email")).isEqualTo(email);

            HttpResponse<String> changed = send(client, "PATCH", "/api/auth/profile",
                    map("email", newAddress.toUpperCase(), "currentPassword", password), true);
            assertThat(changed.statusCode()).isEqualTo(200);
            assertThat(json.readTree(changed.body()).get("email").asString()).isEqualTo(newAddress);
            assertThat(changed.body()).doesNotContain(password);
            // The same session continues with the new address.
            assertThat(get(client, "/api/auth/me").get("email").asString()).isEqualTo(newAddress);
            assertThat(get(client, "/api/auth/profile").get("email").asString()).isEqualTo(newAddress);
        }
        try (HttpClient client = newClient()) {
            assertThat(login(client, email, password).statusCode()).isEqualTo(401);
            assertThat(login(client, newAddress, password).statusCode()).isEqualTo(200);
        }
        assertThat(row(id(other)).get("email")).isEqualTo(other);
    }

    // ---------- phone number ----------

    @Test
    void thePhoneNumberCanBeAddedChangedAndRemoved() throws Exception {
        String email = account("DOCTOR", "Anna", "Testsson", TestPersonnummer.next());
        String other = account("PATIENT", "Bert", "Annan", TestPersonnummer.next());
        try (HttpClient client = signedIn(email)) {
            assertThat(json.readTree(send(client, "PATCH", "/api/auth/profile", map("phoneNumber", "070-123 45 67"), true)
                    .body()).get("phoneNumber").asString()).isEqualTo("0701234567");
            assertThat(row(id(email)).get("phone_number")).isEqualTo("0701234567");
            send(client, "PATCH", "/api/auth/profile", map("phoneNumber", "+44 20 7946 0958"), true);
            assertThat(row(id(email)).get("phone_number")).isEqualTo("+442079460958");
            assertCode(send(client, "PATCH", "/api/auth/profile", map("phoneNumber", "070 ABC"), true), 400,
                    "PHONE_INVALID");
            assertThat(row(id(email)).get("phone_number")).isEqualTo("+442079460958");
            assertThat(send(client, "PATCH", "/api/auth/profile", map("phoneNumber", null), true).statusCode())
                    .isEqualTo(200);
            assertThat(row(id(email)).get("phone_number")).isNull();
        }
        assertThat(row(id(other)).get("phone_number")).isNull();
    }

    // ---------- password ----------

    @Test
    void thePasswordChangesOnlyWithTheCurrentOneAndOnlyTheNewOneWorksAfterwards(CapturedOutput output)
            throws Exception {
        String email = account("STAFF", "Anna", "Testsson", TestPersonnummer.next());
        String newPassword = "New-Synthetic-Passw0rd-" + UUID.randomUUID();
        try (HttpClient client = signedIn(email)) {
            assertCode(send(client, "POST", "/api/auth/password", map("currentPassword", "wrong", "newPassword",
                    newPassword, "confirmPassword", newPassword), true), 403, "CURRENT_PASSWORD_INVALID");
            assertCode(send(client, "POST", "/api/auth/password", map("currentPassword", password, "newPassword",
                    newPassword, "confirmPassword", "different"), true), 400, "PASSWORD_MISMATCH");
            assertCode(send(client, "POST", "/api/auth/password", map("currentPassword", password, "newPassword",
                    " ", "confirmPassword", " "), true), 400, "PASSWORD_INVALID");

            HttpResponse<String> changed = send(client, "POST", "/api/auth/password", map("currentPassword", password,
                    "newPassword", newPassword, "confirmPassword", newPassword), true);
            assertThat(changed.statusCode()).isEqualTo(204);
            assertThat(changed.body()).isEmpty();
            // The current session continues.
            assertThat(get(client, "/api/auth/me").get("email").asString()).isEqualTo(email);
        }
        String hash = (String) row(id(email)).get("password_hash");
        assertThat(hash).startsWith("$2").doesNotContain(newPassword);
        assertThat(passwordEncoder.matches(newPassword, hash)).isTrue();
        try (HttpClient client = newClient()) {
            assertThat(login(client, email, password).statusCode()).isEqualTo(401);
            assertThat(login(client, email, newPassword).statusCode()).isEqualTo(200);
        }
        assertThat(output.getAll()).doesNotContain(newPassword, password);
    }

    @Test
    void anonymousRequestsAndRequestsWithoutCsrfChangeNothing() throws Exception {
        String email = account("PATIENT", "Anna", "Testsson", TestPersonnummer.next());
        Map<String, Object> before = row(id(email));
        try (HttpClient anonymous = newClient(); HttpClient client = signedIn(email)) {
            assertThat(send(anonymous, "PATCH", "/api/auth/profile", map("phoneNumber", "0701234567"), true)
                    .statusCode()).isEqualTo(401);
            assertThat(send(anonymous, "POST", "/api/auth/password", map("currentPassword", password), true)
                    .statusCode()).isEqualTo(401);
            assertThat(client.send(request("/api/auth/profile").GET().build(), HttpResponse.BodyHandlers.ofString())
                    .statusCode()).isEqualTo(200);
            assertThat(send(client, "PATCH", "/api/auth/profile", map("phoneNumber", "0701234567"), false)
                    .statusCode()).isEqualTo(403);
            assertThat(send(client, "POST", "/api/auth/password", map("currentPassword", password, "newPassword", "x",
                    "confirmPassword", "x"), false).statusCode()).isEqualTo(403);
        }
        assertThat(row(id(email))).isEqualTo(before);
    }
}
