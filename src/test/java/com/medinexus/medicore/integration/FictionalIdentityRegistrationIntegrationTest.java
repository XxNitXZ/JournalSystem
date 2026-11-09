package com.medinexus.medicore.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
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
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.auth.service.RegistrationService;
import com.medinexus.medicore.fhir.support.LocalFhirServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Registration with freely chosen fictional identities: any mathematically valid personnummer and any nonblank
 * names are accepted, against a local mock FHIR server on 127.0.0.1 (never the shared KTH server). Real HTTP,
 * PostgreSQL ({@code medinexus_test} only).
 *
 * <p>The fictional personnummer have birth years in the 1820s: mathematically valid, not on Skatteverket's list, and
 * no living person can have them (personnummer were introduced in 1947). Nothing is sent to an external FHIR server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/medinexus_test")
class FictionalIdentityRegistrationIntegrationTest {

    private static final String SYSTEM = "http://electronichealth.se/identifier/personnummer";
    private static final String FICTIONAL_1 = "182003151236";
    private static final String FICTIONAL_2 = "182511044568";
    private static final String FICTIONAL_3 = "183012317891";

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
    void deleteCreatedData() {
        createdAccounts.forEach(id -> jdbc.update("DELETE FROM registered_patients WHERE registered_by = ?", id));
        createdAccounts.forEach(users::deleteById);
    }

    private HttpClient signedInStaff() throws Exception {
        String email = "nit-fictional-" + UUID.randomUUID() + "@example.test";
        createdAccounts.add(registration.register(email, password, Role.STAFF, "Synthetic", "Testperson", TestPersonnummer.next()).id());
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        assertThat(post(client, "/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .statusCode()).isEqualTo(200);
        return client;
    }

    private HttpResponse<String> post(HttpClient client, String path, String body) throws Exception {
        JsonNode token = json.readTree(client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header(token.get("headerName").asString(), token.get("token").asString())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String patient(String personnummer, String given, String family, String birthDate) {
        return """
                {"personnummer":"%s","family":"%s","given":["%s"],"gender":"OTHER","birthDate":"%s"}
                """.formatted(personnummer, family, given, birthDate);
    }

    private List<String> search(HttpClient client, String query) throws Exception {
        HttpResponse<String> response = post(client, "/api/patients/search", "{\"query\":\"" + query + "\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        json.readTree(response.body()).forEach(found -> ids.add(found.get("id").asString()));
        return ids;
    }

    @Test
    void arbitraryValidPersonnummerAndNamesAreRegisteredFoundAndShown(CapturedOutput output) throws Exception {
        // The local FHIR server is shared by the tests of this class.
        long patientsBefore = fhir.count("Patient");
        try (HttpClient staff = signedInStaff()) {
            HttpResponse<String> first = post(staff, "/api/patients", patient(FICTIONAL_1, "Zelda", "Ångström-Pihl", "1820-03-15"));
            assertThat(first.statusCode()).isEqualTo(201);
            String firstId = json.readTree(first.body()).get("id").asString();
            HttpResponse<String> second = post(staff, "/api/patients", patient(FICTIONAL_2, "Zelda", "Ångström-Pihl", "1825-11-04"));
            assertThat(second.statusCode()).isEqualTo(201);
            String secondId = json.readTree(second.body()).get("id").asString();

            // Stored in the local FHIR server with the personnummer as identifier.
            Patient stored = (Patient) fhir.stored("Patient", firstId);
            assertThat(stored.getIdentifierFirstRep().getSystem()).isEqualTo(SYSTEM);
            assertThat(stored.getIdentifierFirstRep().getValue()).isEqualTo(FICTIONAL_1);
            assertThat(stored.getNameFirstRep().getFamily()).isEqualTo("Ångström-Pihl");

            // Duplicate detection (conditional create) still applies.
            assertThat(post(staff, "/api/patients", patient(FICTIONAL_1, "Other", "Name", "1820-03-15")).statusCode())
                    .isEqualTo(409);
            assertThat(fhir.count("Patient")).isEqualTo(patientsBefore + 2);

            // Search by name and by personnummer, and the profile.
            assertThat(search(staff, "Zelda Ångström-Pihl")).containsExactly(firstId, secondId);
            assertThat(search(staff, "zelda")).containsExactly(firstId, secondId);
            assertThat(search(staff, "18251104-4568")).containsExactly(secondId);
            HttpResponse<String> profile = HttpClient.newBuilder().build().send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/api/patients/" + firstId)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(profile.statusCode()).isEqualTo(401);
            HttpResponse<String> ownProfile = staff.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/api/patients/" + firstId)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(ownProfile.statusCode()).isEqualTo(200);
            assertThat(json.readTree(ownProfile.body()).get("birthDate").asString()).isEqualTo("1820-03-15");
        }
        // Only the logical IDs are in PostgreSQL.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM registered_patients WHERE registered_by = ?",
                Integer.class, createdAccounts.getFirst())).isEqualTo(2);
        for (String forbidden : List.of(FICTIONAL_1, FICTIONAL_2, "Zelda", "Ångström", password)) {
            assertThat(output.getAll().contains(forbidden)).as("log contains patient data").isFalse();
        }
    }

    @Test
    void aWrongLuhnCheckDigitDoesNotPreventRegistrationAndTheBirthDateIsDerived() throws Exception {
        // 183012317892: a real past date (1830-12-31) with a wrong check digit (the correct one would be 3).
        try (HttpClient staff = signedInStaff()) {
            HttpResponse<String> created = post(staff, "/api/patients", """
                    {"personnummer":"183012317892","family":"Kontrollsson","given":["Fiktiv"],"gender":"OTHER"}
                    """);
            assertThat(created.statusCode()).isEqualTo(201);
            JsonNode patient = json.readTree(created.body());
            assertThat(patient.get("birthDate").asString()).isEqualTo("1830-12-31");
            assertThat(patient.get("identifiers").get(0).get("system").asString()).isEqualTo(SYSTEM);
            assertThat(patient.get("identifiers").get(0).get("value").asString()).isEqualTo("183012317892");
            // Duplicate detection still works for such numbers.
            assertThat(post(staff, "/api/patients", """
                    {"personnummer":"183012317892","family":"Kontrollsson","given":["Fiktiv"],"gender":"OTHER"}
                    """).statusCode()).isEqualTo(409);
            // And it is found by an exact personnummer search.
            HttpResponse<String> found = post(staff, "/api/patients/search", "{\"query\":\"18301231-7892\"}");
            assertThat(found.statusCode()).isEqualTo(200);
            assertThat(json.readTree(found.body())).singleElement()
                    .satisfies(match -> assertThat(match.get("id").asString()).isEqualTo(patient.get("id").asString()));
        }
    }

    @Test
    void personnummerAndNameValidationStillApply() throws Exception {
        int before = fhir.requests().size();
        try (HttpClient staff = signedInStaff()) {
            for (String invalid : List.of(
                    // Invalid dates (30 February, month 13), coordination number (day + 60).
                    patient("183002301234", "Anna", "Fiktiv", "1830-02-28"),
                    patient("199013019808", "Anna", "Fiktiv", "1990-13-01"),
                    patient("199001619809", "Anna", "Fiktiv", "1990-01-01"),
                    // Birth date that does not match the personnummer, blank names.
                    patient(FICTIONAL_3, "Anna", "Fiktiv", "1830-12-30"),
                    patient(FICTIONAL_3, "  ", "Fiktiv", "1830-12-31"),
                    patient(FICTIONAL_3, "Anna", " ", "1830-12-31"),
                    // A future birth date (born 2099).
                    patient("209901011230", "Anna", "Fiktiv", "2099-01-01"))) {
                HttpResponse<String> response = post(staff, "/api/patients", invalid);
                assertThat(response.statusCode()).as("status for invalid registration data").isEqualTo(400);
            }
        }
        assertThat(fhir.requests()).hasSize(before);
    }
}
