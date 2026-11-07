package com.medinexus.medicore.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.auth.service.AccountFhirIdentityService;
import com.medinexus.medicore.auth.service.RegistrationService;
import com.medinexus.medicore.fhir.client.FhirServerException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * US-03.06 (NIT-138, account part): the complete application with real PostgreSQL ({@code medinexus_test}
 * only) while the FHIR server is unavailable. The configured FHIR server is a local socket that accepts each
 * connection and closes it without answering; it counts every connection attempt.
 *
 * <p>Runs only when {@code DB_URL} names the {@code medinexus_test} database. Each test deletes exactly the
 * accounts it created.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/medinexus_test")
class FhirOutageCoreFunctionalityIntegrationTest {

    private static final AtomicInteger FHIR_CONNECTIONS = new AtomicInteger();
    private static ServerSocket deadFhirServer;

    @DynamicPropertySource
    static void unavailableFhirServer(DynamicPropertyRegistry registry) throws IOException {
        if (deadFhirServer == null) {
            deadFhirServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(() -> {
                while (!deadFhirServer.isClosed()) {
                    try (Socket connection = deadFhirServer.accept()) {
                        FHIR_CONNECTIONS.incrementAndGet();
                        // Close immediately without any HTTP response.
                    } catch (IOException closed) {
                        return;
                    }
                }
            }, "unavailable-fhir-server");
            acceptor.setDaemon(true);
            acceptor.start();
        }
        registry.add("medinexus.fhir.base-url", () -> fhirBaseUrl());
    }

    private static String fhirBaseUrl() {
        return "http://127.0.0.1:" + deadFhirServer.getLocalPort() + "/fhir";
    }

    @AfterAll
    static void stopFhirServer() throws IOException {
        if (deadFhirServer != null) deadFhirServer.close();
        deadFhirServer = null;
    }

    @LocalServerPort
    private int port;
    @Value("${spring.datasource.url}")
    private String datasourceUrl;
    @Autowired
    private RegistrationService registration;
    @Autowired
    private AccountFhirIdentityService identities;
    @Autowired
    private UserRepository users;

    private final ObjectMapper json = new ObjectMapper();
    private final List<Long> createdAccounts = new ArrayList<>();
    private final String email = "nit-us0306-" + UUID.randomUUID() + "@example.test";
    private final String password = "Synthetic-Passw0rd-" + UUID.randomUUID();

    @BeforeEach
    void onlyTheTestDatabase() {
        assertThat(datasourceUrl).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/medinexus_test");
    }

    @AfterEach
    void deleteCreatedAccounts() {
        users.findByEmail(email).map(User::getId).filter(id -> !createdAccounts.contains(id)).ifPresent(createdAccounts::add);
        createdAccounts.forEach(users::deleteById);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private JsonNode csrf(HttpClient client) throws Exception {
        HttpResponse<String> response = client.send(request("/api/auth/csrf").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private HttpResponse<String> post(HttpClient client, String path, String body) throws Exception {
        JsonNode token = csrf(client);
        return client.send(request(path).header(token.get("headerName").asString(), token.get("token").asString())
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthRegistrationLoginMeAndLogoutWorkWhileFhirIsUnavailable(CapturedOutput output) throws Exception {
        int before = FHIR_CONNECTIONS.get();
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build()) {
            HttpResponse<String> health = client.send(request("/api/health").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(json.readTree(health.body()).get("status").asString()).isEqualTo("UP");

            HttpResponse<String> registered = post(client, "/api/auth/register",
                    "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"role\":\"PATIENT\"}");
            assertThat(registered.statusCode()).isEqualTo(201);
            createdAccounts.add(json.readTree(registered.body()).get("id").asLong());

            HttpResponse<String> login = post(client, "/api/auth/login",
                    "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
            assertThat(login.statusCode()).isEqualTo(200);

            HttpResponse<String> me = client.send(request("/api/auth/me").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(me.statusCode()).isEqualTo(200);
            JsonNode user = json.readTree(me.body());
            assertThat(user.get("email").asString()).isEqualTo(email);
            assertThat(user.get("role").asString()).isEqualTo("PATIENT");

            HttpResponse<String> logout = post(client, "/api/auth/logout", null);
            assertThat(logout.statusCode()).isEqualTo(204);
            HttpResponse<String> afterLogout = client.send(request("/api/auth/me").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(afterLogout.statusCode()).isEqualTo(401);
        }

        // None of these functions contacted the FHIR server, and the account holds no FHIR reference.
        assertThat(FHIR_CONNECTIONS.get()).isEqualTo(before);
        User account = users.findByEmail(email).orElseThrow();
        assertThat(account.getFhirPatientId()).isNull();
        assertThat(account.getFhirPractitionerId()).isNull();
        assertThat(output.getAll().contains("FHIR request failed")).isFalse();
        assertNoSensitiveData(output, account);
    }

    @Test
    void identityAssignmentDuringTheOutageFailsOnceAndChangesNothing(CapturedOutput output) {
        Long accountId = registration.register(email, password, Role.PATIENT).id();
        createdAccounts.add(accountId);
        User before = users.findById(accountId).orElseThrow();
        String fhirId = "syn-pat-" + UUID.randomUUID().toString().substring(0, 18);
        int connectionsBefore = FHIR_CONNECTIONS.get();

        assertThatThrownBy(() -> identities.assign(accountId, fhirId))
                .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                    assertThat(failure.operation()).isEqualTo("patient-read");
                    assertThat(failure.type()).isEqualTo(FhirServerException.Type.UNAVAILABLE);
                    assertThat(failure.httpStatus()).isEmpty();
                    assertThat(failure.getCause()).isNull();
                    for (String sensitive : List.of(email, password, fhirId, fhirBaseUrl(), before.getPasswordHash())) {
                        assertThat(failure.getMessage().contains(sensitive)).as("message contains sensitive value").isFalse();
                    }
                });

        // Exactly one connection attempt (no automatic retry) and the account is unchanged.
        assertThat(FHIR_CONNECTIONS.get() - connectionsBefore).isEqualTo(1);
        User after = users.findById(accountId).orElseThrow();
        assertThat(after.getEmail()).isEqualTo(before.getEmail());
        assertThat(after.getRole()).isEqualTo(Role.PATIENT);
        assertThat(after.getPasswordHash()).isEqualTo(before.getPasswordHash());
        assertThat(after.getFhirPatientId()).isNull();
        assertThat(after.getFhirPractitionerId()).isNull();

        String all = output.getAll();
        assertThat(all).contains("FHIR request failed: operation=patient-read, type=UNAVAILABLE, httpStatus=-");
        assertThat(all.split("FHIR request failed", -1)).hasSize(2);
        assertThat(all.contains(fhirId)).as("log contains the FHIR ID").isFalse();
        assertNoSensitiveData(output, after);
    }

    /** E-mail, password, hash, database password and FHIR server address; checked without printing them. */
    private void assertNoSensitiveData(CapturedOutput output, User account) {
        List<String> secrets = new ArrayList<>(List.of(email, password, account.getPasswordHash(), fhirBaseUrl(),
                "127.0.0.1:" + deadFhirServer.getLocalPort()));
        String databasePassword = System.getenv("DB_PASSWORD");
        if (databasePassword != null && !databasePassword.isBlank()) secrets.add(databasePassword);
        String all = output.getAll();
        for (int i = 0; i < secrets.size(); i++) {
            assertThat(all.contains(secrets.get(i))).as("log contains sensitive value #%d", i).isFalse();
        }
        assertThat(all.contains("RetryExec")).as("HTTP client retry logged").isFalse();
    }
}
