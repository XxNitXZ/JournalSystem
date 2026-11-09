package com.medinexus.medicore.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hl7.fhir.r4.model.Patient;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.medinexus.medicore.support.TestPersonnummer;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.auth.service.RegistrationService;
import com.medinexus.medicore.fhir.support.LocalFhirServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * US-04.01 end to end: real HTTP with session cookies and CSRF, real accounts in PostgreSQL
 * ({@code medinexus_test} only) and a local FHIR test server; no external FHIR server.
 *
 * <p>Synthetic test personnummer only (Skatteverket's test series). Runs only when {@code DB_URL} names the
 * {@code medinexus_test} database. Each test deletes exactly the accounts it created; Patients exist only on
 * the local FHIR server, which is discarded after the class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/medinexus_test")
class PatientRegistrationIntegrationTest {

    private static final String SYSTEM = "http://electronichealth.se/identifier/personnummer";
    private static final String PERSONNUMMER = "199001012385";
    private static final String OTHER_PERSONNUMMER = "198506152381";
    private static final String UNUSED_PERSONNUMMER = "197303159250";

    private static LocalFhirServer fhir;

    @DynamicPropertySource
    static void fhirServer(DynamicPropertyRegistry registry) throws IOException {
        if (fhir == null) fhir = LocalFhirServer.start();
        registry.add("medinexus.fhir.base-url", fhir::baseUrl);
    }

    @AfterAll
    static void stopFhirServer() {
        if (fhir != null) fhir.close();
        fhir = null;
    }

    @LocalServerPort
    private int port;
    @Value("${spring.datasource.url}")
    private String datasourceUrl;
    @Autowired
    private RegistrationService registration;
    @Autowired
    private UserRepository users;
    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();
    private final List<Long> createdAccounts = new ArrayList<>();
    private final String password = "Synthetic-Passw0rd-" + UUID.randomUUID();

    @BeforeEach
    void onlyTheTestDatabase() {
        assertThat(datasourceUrl).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/medinexus_test");
    }

    @AfterEach
    void deleteCreatedAccounts() {
        // Registry entries of Patients registered by this test's accounts, then the accounts themselves.
        createdAccounts.forEach(id -> jdbc.update("DELETE FROM registered_patients WHERE registered_by = ?", id));
        createdAccounts.forEach(users::deleteById);
    }

    private String account(Role role) {
        String email = "nit-us0401-" + role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.test";
        createdAccounts.add(registration.register(email, password, role, "Synthetic", "Testperson", TestPersonnummer.next()).id());
        return email;
    }

    private static String body(String personnummer, String birthDate) {
        return """
                {"personnummer":"%s","family":"Testsson","given":["Tolvan","Syntet"],"gender":"FEMALE","birthDate":"%s"}
                """.formatted(personnummer, birthDate);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private HttpResponse<String> post(HttpClient client, String path, String body) throws Exception {
        JsonNode token = json.readTree(client.send(request("/api/auth/csrf").GET().build(),
                HttpResponse.BodyHandlers.ofString()).body());
        return client.send(request(path).header(token.get("headerName").asString(), token.get("token").asString())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpClient signedIn(String email) throws Exception {
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        HttpResponse<String> login = post(client, "/api/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        assertThat(login.statusCode()).isEqualTo(200);
        return client;
    }

    private void assertNoPatientDataLogged(CapturedOutput output) {
        for (String forbidden : List.of(PERSONNUMMER, OTHER_PERSONNUMMER, UNUSED_PERSONNUMMER, "9001012385",
                "Testsson", "Tolvan", password)) {
            assertThat(output.getAll().contains(forbidden)).as("log contains patient data or credentials").isFalse();
        }
    }

    @Test
    void staffAndDoctorsRegisterPatientsInFhirAndDuplicatesAreRejected(CapturedOutput output) throws Exception {
        long patientsBefore = fhir.count("Patient");
        try (HttpClient staff = signedIn(account(Role.STAFF)); HttpClient doctor = signedIn(account(Role.DOCTOR))) {
            HttpResponse<String> created = post(staff, "/api/patients", body(PERSONNUMMER, "1990-01-01"));
            assertThat(created.statusCode()).isEqualTo(201);
            JsonNode patient = json.readTree(created.body());
            String id = patient.get("id").asString();
            assertThat(created.headers().firstValue("Location")).hasValue("/api/patients/" + id);
            assertThat(patient.get("identifiers").get(0).get("system").asString()).isEqualTo(SYSTEM);
            assertThat(patient.get("identifiers").get(0).get("value").asString()).isEqualTo(PERSONNUMMER);
            assertThat(patient.get("birthDate").asString()).isEqualTo("1990-01-01");

            // Stored in HAPI FHIR with the personnummer as identifier, created by a conditional create.
            Patient stored = (Patient) fhir.stored("Patient", id);
            assertThat(stored.getIdentifierFirstRep().getSystem()).isEqualTo(SYSTEM);
            assertThat(stored.getIdentifierFirstRep().getValue()).isEqualTo(PERSONNUMMER);
            assertThat(URLDecoder.decode(fhir.conditionalCreates().getLast(), StandardCharsets.UTF_8))
                    .endsWith("identifier=" + SYSTEM + "|" + PERSONNUMMER);

            // The same personnummer again, from another account: 409 and nothing new in FHIR.
            HttpResponse<String> duplicate = post(doctor, "/api/patients", body(PERSONNUMMER, "1990-01-01"));
            assertThat(duplicate.statusCode()).isEqualTo(409);
            assertThat(duplicate.body()).isEmpty();
            assertThat(fhir.count("Patient")).isEqualTo(patientsBefore + 1);

            HttpResponse<String> other = post(doctor, "/api/patients", body(OTHER_PERSONNUMMER, "1985-06-15"));
            assertThat(other.statusCode()).isEqualTo(201);
            assertThat(fhir.count("Patient")).isEqualTo(patientsBefore + 2);

            // Both new Patients are recorded in the local registry (ID, registering account, time); the
            // duplicate is not recorded again.
            String otherId = json.readTree(other.body()).get("id").asString();
            for (String registered : List.of(id, otherId)) {
                Map<String, Object> row = jdbc.queryForMap("SELECT * FROM registered_patients WHERE fhir_patient_id = ?", registered);
                assertThat(row).containsOnlyKeys("fhir_patient_id", "registered_by", "registered_at");
                assertThat(row.get("registered_at")).isNotNull();
            }
            assertThat(jdbc.queryForObject("SELECT registered_by FROM registered_patients WHERE fhir_patient_id = ?",
                    Long.class, id)).isEqualTo(createdAccounts.getFirst());
            assertThat(jdbc.queryForObject("SELECT registered_by FROM registered_patients WHERE fhir_patient_id = ?",
                    Long.class, otherId)).isEqualTo(createdAccounts.get(1));
        }
        assertThat(output.getAll()).contains("Patient registered: accountId=")
                .contains("Patient registration rejected as duplicate: accountId=");
        assertNoPatientDataLogged(output);
    }

    @Test
    void unauthorizedAndInvalidRegistrationsNeverReachFhir(CapturedOutput output) throws Exception {
        int requestsBefore = fhir.requests().size();
        try (HttpClient patientAccount = signedIn(account(Role.PATIENT));
                HttpClient doctor = signedIn(account(Role.DOCTOR));
                HttpClient anonymous = HttpClient.newBuilder()
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            assertThat(post(anonymous, "/api/patients", body(UNUSED_PERSONNUMMER, "1973-03-15")).statusCode())
                    .isEqualTo(401);
            assertThat(post(patientAccount, "/api/patients", body(UNUSED_PERSONNUMMER, "1973-03-15")).statusCode())
                    .isEqualTo(403);
            // Impossible date (30 February), birth date different from the personnummer, invalid gender, unreadable
            // JSON.
            for (String invalid : List.of(body("197302301234", "1973-02-28"), body(UNUSED_PERSONNUMMER, "1973-03-16"),
                    body(UNUSED_PERSONNUMMER, "1973-03-15").replace("FEMALE", "F"), "{\"personnummer\":")) {
                HttpResponse<String> response = post(doctor, "/api/patients", invalid);
                assertThat(response.statusCode()).isEqualTo(400);
                assertThat(response.body()).isEmpty();
            }
        }
        assertThat(fhir.requests()).hasSize(requestsBefore);
        assertNoPatientDataLogged(output);
    }

    @Test
    void theCurrentDatabaseRoleDecidesNotTheRoleInTheSession() throws Exception {
        String email = account(Role.STAFF);
        int requestsBefore = fhir.requests().size();
        try (HttpClient client = signedIn(email)) {
            // The session was created as STAFF; the account is then changed to PATIENT.
            User account = users.findByEmail(email).orElseThrow();
            account.setRole(Role.PATIENT);
            users.saveAndFlush(account);
            assertThat(post(client, "/api/patients", body(UNUSED_PERSONNUMMER, "1973-03-15")).statusCode())
                    .isEqualTo(403);
        }
        assertThat(fhir.requests()).hasSize(requestsBefore);
    }

    @Test
    void noPatientDataIsStoredInPostgreSql() throws Exception {
        String email = account(Role.STAFF);
        try (HttpClient staff = signedIn(email)) {
            assertThat(post(staff, "/api/patients", body("196407133252", "1964-07-13")).statusCode()).isEqualTo(201);
        }
        // Read-only checks: the only patient-related table is the registry of logical IDs (no names, no
        // personnummer, no clinical data), and the registering account is unchanged.
        List<String> patientTables = jdbc.queryForList("""
                select table_name from information_schema.tables
                where table_schema = 'public' and table_name ilike '%patient%'
                """, String.class);
        assertThat(patientTables).containsExactly("registered_patients");
        assertThat(jdbc.queryForList("""
                select column_name from information_schema.columns
                where table_schema = 'public' and table_name = 'registered_patients' order by column_name
                """, String.class)).containsExactly("fhir_patient_id", "registered_at", "registered_by");
        for (String forbidden : List.of("196407133252", "Testsson", "Tolvan")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM registered_patients WHERE fhir_patient_id = ?",
                    Integer.class, forbidden)).isZero();
        }
        User account = users.findByEmail(email).orElseThrow();
        assertThat(account.getRole()).isEqualTo(Role.STAFF);
        assertThat(account.getFhirPatientId()).isNull();
        assertThat(account.getFhirPractitionerId()).isNull();
    }
}
