package com.medinexus.medicore.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.medinexus.medicore.support.TestPersonnummer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Account names (Flyway V4) end to end against PostgreSQL ({@code medinexus_test} only): registration stores and
 * returns first and last names, invalid names are rejected, the database constraints hold, and accounts without
 * names (created before V4) still sign in and are returned with null names.
 *
 * <p>Runs only when {@code DB_URL} names the {@code medinexus_test} database. Every row uses a random
 * {@code nit-names-...@example.test} e-mail and is deleted afterwards; no other row is read or changed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/medinexus_test")
class UserNamesIntegrationTest {

    @LocalServerPort
    private int port;
    @Value("${spring.datasource.url}")
    private String datasourceUrl;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> emails = new ArrayList<>();
    private final String password = "Synthetic-Passw0rd-" + UUID.randomUUID();

    @BeforeEach
    void onlyTheTestDatabase() {
        assertThat(datasourceUrl).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/medinexus_test");
    }

    @AfterEach
    void deleteCreatedRows() {
        emails.forEach(email -> jdbc.update("DELETE FROM users WHERE email = ?", email));
    }

    private String newEmail() {
        String email = "nit-names-" + UUID.randomUUID() + "@example.test";
        emails.add(email);
        return email;
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    private HttpResponse<String> post(HttpClient client, String path, Map<String, Object> body) throws Exception {
        JsonNode token = json.readTree(client.send(request("/api/auth/csrf").GET().build(),
                HttpResponse.BodyHandlers.ofString()).body());
        return client.send(request(path).header(token.get("headerName").asString(), token.get("token").asString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> registration(String email, String role, Object givenName, Object familyName) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("email", email, "password", password, "role", role));
        body.put("givenName", givenName);
        body.put("familyName", familyName);
        body.put("personnummer", TestPersonnummer.next());
        return body;
    }

    private HttpResponse<String> me(HttpClient client) throws Exception {
        return client.send(request("/api/auth/me").GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void migrationV4IsApplied() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '4' AND success", Integer.class);
        assertThat(applied).isEqualTo(1);
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT column_name, is_nullable, character_maximum_length FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'users' AND column_name IN ('given_name', 'family_name')
                ORDER BY column_name
                """);
        assertThat(columns).extracting(row -> row.get("column_name")).containsExactly("family_name", "given_name");
        assertThat(columns).allSatisfy(row -> {
            assertThat(row.get("is_nullable")).isEqualTo("YES");
            assertThat(((Number) row.get("character_maximum_length")).intValue()).isEqualTo(100);
        });
    }

    @Test
    void registrationStoresAndReturnsNamesAndPatientsAreSignedInWithThem() throws Exception {
        String email = newEmail();
        try (HttpClient client = newClient()) {
            HttpResponse<String> registered = post(client, "/api/auth/register",
                    registration(email, "PATIENT", "  Anna-Karin ", "Testsson"));
            assertThat(registered.statusCode()).isEqualTo(201);
            JsonNode account = json.readTree(registered.body());
            assertThat(account.get("givenName").asString()).isEqualTo("Anna-Karin");
            assertThat(account.get("familyName").asString()).isEqualTo("Testsson");

            HttpResponse<String> me = me(client);
            assertThat(me.statusCode()).isEqualTo(200);
            assertThat(json.readTree(me.body())).isEqualTo(account);

            assertThat(post(client, "/api/auth/logout", Map.of()).statusCode()).isEqualTo(204);
            HttpResponse<String> login = post(client, "/api/auth/login", Map.of("email", email, "password", password));
            assertThat(login.statusCode()).isEqualTo(200);
            assertThat(json.readTree(login.body())).isEqualTo(account);
        }
        Map<String, Object> row = jdbc.queryForMap("SELECT given_name, family_name FROM users WHERE email = ?", email);
        assertThat(row).containsEntry("given_name", "Anna-Karin").containsEntry("family_name", "Testsson");
    }

    @Test
    void registrationsWithoutValidNamesAreRejectedAndCreateNothing() throws Exception {
        String tooLong = "x".repeat(101);
        Object[][] invalid = {{null, "Testsson"}, {"Anna", null}, {"", "Testsson"}, {"Anna", "   "},
                {tooLong, "Testsson"}, {"Anna", tooLong}, {"An\nna", "Testsson"}};
        try (HttpClient client = newClient()) {
            for (Object[] names : invalid) {
                String email = newEmail();
                HttpResponse<String> response = post(client, "/api/auth/register",
                        registration(email, "STAFF", names[0], names[1]));
                assertThat(response.statusCode()).as("status for invalid names").isEqualTo(400);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email)).isZero();
            }
            assertThat(me(client).statusCode()).isEqualTo(401);
        }
    }

    @Test
    void anAccountCreatedBeforeNamesExistedStillSignsInWithNullNames() throws Exception {
        // A row as written before V4: no names. It must keep working and stay unchanged.
        String email = newEmail();
        String hash = passwordEncoder.encode(password);
        jdbc.update("INSERT INTO users (email, password_hash, role) VALUES (?, ?, 'DOCTOR')", email, hash);
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM users WHERE email = ?", email);

        try (HttpClient client = newClient()) {
            HttpResponse<String> login = post(client, "/api/auth/login", Map.of("email", email, "password", password));
            assertThat(login.statusCode()).isEqualTo(200);
            JsonNode account = json.readTree(login.body());
            assertThat(account.get("givenName").isNull()).isTrue();
            assertThat(account.get("familyName").isNull()).isTrue();
            assertThat(account.get("role").asString()).isEqualTo("DOCTOR");
            assertThat(json.readTree(me(client).body())).isEqualTo(account);
        }
        assertThat(jdbc.queryForMap("SELECT * FROM users WHERE email = ?", email)).isEqualTo(before);
    }

    @Test
    void theDatabaseRejectsIncompleteOrBlankNames() {
        String hash = passwordEncoder.encode(password);
        for (String[] names : new String[][] {{"Anna", null}, {null, "Testsson"}, {"  ", "Testsson"}, {"Anna", ""}}) {
            String email = newEmail();
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO users (email, password_hash, role, given_name, family_name) VALUES (?, ?, 'PATIENT', ?, ?)",
                    email, hash, names[0], names[1])).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email)).isZero();
        }
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO users (email, password_hash, role, given_name, family_name) VALUES (?, ?, 'PATIENT', ?, ?)",
                newEmail(), hash, "x".repeat(101), "Testsson")).isInstanceOf(DataIntegrityViolationException.class);
    }
}
