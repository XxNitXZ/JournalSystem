package com.medinexus.medicore.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.hl7.fhir.r4.model.HumanName;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.service.RegistrationService;
import com.medinexus.medicore.fhir.support.LocalFhirServer;
import com.medinexus.medicore.support.TestPersonnummer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Personnummer stored as {@code YYYYMMDD-XXXX} (as on the KTH server) match the 12-digit account personnummer in the
 * patient-initiated journal access workflow, without weakening it: import makes a waiting request PENDING, an explicit
 * request later finds an imported record, a doctor must still approve, and ambiguous or conflicting records are never
 * matched. Real HTTP with sessions and CSRF, PostgreSQL {@code medinexus_test} only, a local FHIR test server only.
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/medinexus_test")
class FormattedPersonnummerIntegrationTest {

    private static final String SYSTEM = "http://electronichealth.se/identifier/personnummer";
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
    private JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();
    private final List<Long> accounts = new ArrayList<>();
    private final List<String> patients = new ArrayList<>();
    private final String password = "Synthetic-Passw0rd-" + UUID.randomUUID();
    private String suffix;

    @BeforeEach
    void checkTheDatabase() {
        assertThat(datasourceUrl).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/medinexus_test");
        suffix = UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void deleteEverythingThisTestCreated() {
        for (String id : patients) jdbc.update("DELETE FROM journal_access_requests WHERE fhir_patient_id = ?", id);
        for (Long id : accounts) jdbc.update("DELETE FROM journal_access_requests WHERE user_id = ?", id);
        for (String id : patients) jdbc.update("DELETE FROM registered_patients WHERE fhir_patient_id = ?", id);
        for (Long id : accounts) jdbc.update("DELETE FROM users WHERE id = ?", id);
    }

    private static String formatted(String pnr) {
        return pnr.substring(0, 8) + "-" + pnr.substring(8);
    }

    private static String family() {
        StringBuilder name = new StringBuilder("Kth");
        for (int i = 0; i < 6; i++) name.append((char) ('a' + ThreadLocalRandom.current().nextInt(26)));
        return name.toString();
    }

    /** A Patient that already exists on the server (not registered by MediCore), with these personnummer values. */
    private String existingPatient(String id, String family, String birthDate, String... personnummer) {
        Patient patient = new Patient();
        patient.setId(id);
        patient.addIdentifier().setSystem("https://github.com/synthetichealth/synthea").setValue(UUID.randomUUID().toString());
        for (String value : personnummer) patient.addIdentifier().setSystem(SYSTEM).setValue(value);
        patient.addName().setUse(HumanName.NameUse.OFFICIAL).setFamily(family).addGiven("Fiktiv");
        patient.getBirthDateElement().setValueAsString(birthDate);
        fhir.seed(patient);
        patients.add(id);
        return id;
    }

    private static String birthDate(String pnr) {
        return pnr.substring(0, 4) + "-" + pnr.substring(4, 6) + "-" + pnr.substring(6, 8);
    }

    private String account(Role role, String pnr) {
        String email = "nit-pnrfmt-" + role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.test";
        accounts.add(registration.register(email, password, role, "Konto", "Namn", pnr).id());
        return email;
    }

    private long id(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private HttpClient signedIn(String email) throws Exception {
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        assertThat(post(client, "/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .statusCode()).isEqualTo(200);
        return client;
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(HttpClient client, String path, String body) throws Exception {
        JsonNode token = json.readTree(get(client, "/api/auth/csrf").body());
        return client.send(request(path).header("Content-Type", "application/json")
                .header(token.get("headerName").asString(), token.get("token").asString())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String status(HttpClient patient) throws Exception {
        return json.readTree(get(patient, "/api/journal-access/me").body()).get("status").asString();
    }

    private long requestId(String email) {
        return jdbc.queryForObject("SELECT id FROM journal_access_requests WHERE user_id = ?", Long.class, id(email));
    }

    private String linkOf(String email) {
        return jdbc.queryForObject("SELECT fhir_patient_id FROM users WHERE email = ?", String.class, email);
    }

    @Test
    void importingAKthFormattedRecordMatchesTheWaitingRequestAndADoctorMustStillApprove(CapturedOutput output)
            throws Exception {
        String pnr = TestPersonnummer.next();
        String family = family();
        String recordId = existingPatient("syn-kth-" + suffix, family, birthDate(pnr), formatted(pnr));
        String patientEmail = account(Role.PATIENT, pnr);
        try (HttpClient patient = signedIn(patientEmail); HttpClient doctor = signedIn(account(Role.DOCTOR,
                TestPersonnummer.next()))) {
            // The patient asks first; the record is not in MediCore yet.
            assertThat(post(patient, "/api/journal-access/me/request", "").statusCode()).isEqualTo(200);
            assertThat(status(patient)).isEqualTo("AWAITING_RECORD");

            // Discovery by the 12-digit and by the hyphenated form finds the record, shown as matchable.
            for (String search : List.of(pnr, formatted(pnr))) {
                HttpResponse<String> found = post(doctor, "/api/patients/import/search",
                        "{\"mode\":\"PERSONNUMMER\",\"value\":\"" + search + "\"}");
                assertThat(found.statusCode()).isEqualTo(200);
                JsonNode candidate = json.readTree(found.body()).get("patients").get(0);
                assertThat(candidate.get("id").asString()).isEqualTo(recordId);
                assertThat(candidate.get("personnummer").asString()).isEqualTo(pnr);
                assertThat(candidate.get("journalMatchable").asBoolean()).isTrue();
                assertThat(candidate.get("personnummerStatus").asString()).isEqualTo("VALID");
            }

            // Import: the waiting request becomes PENDING, but nothing is linked or approved.
            HttpResponse<String> imported = post(doctor, "/api/patients/import", "{\"fhirPatientId\":\"" + recordId + "\"}");
            assertThat(imported.statusCode()).isEqualTo(201);
            assertThat(json.readTree(imported.body()).get("journalMatchable").asBoolean()).isTrue();
            assertThat(status(patient)).isEqualTo("PENDING");
            assertThat(linkOf(patientEmail)).isNull();
            assertThat(get(patient, "/api/patients/me").statusCode()).isEqualTo(404);

            long requestId = requestId(patientEmail);
            JsonNode detail = json.readTree(get(doctor, "/api/journal-access-requests/" + requestId).body());
            assertThat(detail.get("personnummerMatches").asBoolean()).isTrue();
            assertThat(detail.get("record").get("personnummer").asString()).isEqualTo(pnr);

            // The doctor approves: only now is the account linked.
            HttpResponse<String> approved = post(doctor, "/api/journal-access-requests/" + requestId + "/approve", "");
            assertThat(approved.statusCode()).isEqualTo(200);
            assertThat(linkOf(patientEmail)).isEqualTo(recordId);
            assertThat(get(patient, "/api/patients/me").statusCode()).isEqualTo(200);
            for (String path : List.of("/api/patients/me/encounters", "/api/patients/me/notes", "/api/patients/me/conditions")) {
                assertThat(get(patient, path).statusCode()).as(path).isEqualTo(200);
            }
            assertThat(get(doctor, "/api/messages/inbox").statusCode()).isEqualTo(200);
        }
        // The FHIR identifier is never rewritten and no FHIR write happened.
        assertThat(((Patient) fhir.stored("Patient", recordId)).getIdentifier().get(1).getValue()).isEqualTo(formatted(pnr));
        assertThat(fhir.requests()).noneMatch(line -> line.startsWith("PUT ") || line.equals("POST /fhir/Patient"));
        assertThat(output.getAll()).doesNotContain(pnr, formatted(pnr));
    }

    @Test
    void anAlreadyImportedFormattedRecordIsFoundByALaterExplicitRequest() throws Exception {
        String pnr = TestPersonnummer.next();
        String recordId = existingPatient("syn-kth-later-" + suffix, family(), birthDate(pnr), formatted(pnr));
        try (HttpClient doctor = signedIn(account(Role.DOCTOR, TestPersonnummer.next()))) {
            assertThat(post(doctor, "/api/patients/import", "{\"fhirPatientId\":\"" + recordId + "\"}").statusCode())
                    .isEqualTo(201);
            // Importing alone never creates a request.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_access_requests WHERE fhir_patient_id = ?",
                    Integer.class, recordId)).isZero();

            String patientEmail = account(Role.PATIENT, pnr);
            try (HttpClient patient = signedIn(patientEmail)) {
                assertThat(post(patient, "/api/journal-access/me/request", "").statusCode()).isEqualTo(200);
                assertThat(status(patient)).isEqualTo("PENDING");
                assertThat(linkOf(patientEmail)).isNull();
                long requestId = requestId(patientEmail);
                assertThat(post(doctor, "/api/journal-access-requests/" + requestId + "/approve", "").statusCode())
                        .isEqualTo(200);
                assertThat(linkOf(patientEmail)).isEqualTo(recordId);
            }
        }
    }

    @Test
    void duplicateConflictingAndAlreadyLinkedRecordsAreNeverMatched() throws Exception {
        String duplicatePnr = TestPersonnummer.next();
        String conflictPnr = TestPersonnummer.next();
        String first = existingPatient("syn-kth-dup-a-" + suffix, family(), birthDate(duplicatePnr), formatted(duplicatePnr));
        String second = existingPatient("syn-kth-dup-b-" + suffix, family(), birthDate(duplicatePnr), duplicatePnr);
        // One record with two different personnummer: never matched automatically.
        String conflicting = existingPatient("syn-kth-conflict-" + suffix, family(), birthDate(conflictPnr),
                formatted(conflictPnr), TestPersonnummer.next());
        // A record of another system only: never a personnummer.
        String otherSystemPnr = TestPersonnummer.next();
        Patient otherSystem = new Patient();
        otherSystem.setId("syn-kth-mrn-" + suffix);
        otherSystem.addIdentifier().setSystem("http://hospital.smarthealthit.org").setValue(otherSystemPnr);
        otherSystem.addName().setFamily(family()).addGiven("Fiktiv");
        otherSystem.getBirthDateElement().setValueAsString(birthDate(otherSystemPnr));
        fhir.seed(otherSystem);
        patients.add(otherSystem.getIdPart());

        try (HttpClient doctor = signedIn(account(Role.DOCTOR, TestPersonnummer.next()))) {
            for (String id : List.of(first, second, conflicting, otherSystem.getIdPart())) {
                assertThat(post(doctor, "/api/patients/import", "{\"fhirPatientId\":\"" + id + "\"}").statusCode())
                        .as(id).isEqualTo(201);
            }
            JsonNode found = json.readTree(post(doctor, "/api/patients/import/search",
                    "{\"mode\":\"ID\",\"value\":\"" + conflicting + "\"}").body()).get("patients").get(0);
            assertThat(found.get("journalMatchable").asBoolean()).isFalse();
            assertThat(found.get("personnummerStatus").asString()).isEqualTo("AMBIGUOUS");

            for (String pnr : List.of(duplicatePnr, conflictPnr, otherSystemPnr)) {
                String email = account(Role.PATIENT, pnr);
                try (HttpClient patient = signedIn(email)) {
                    assertThat(post(patient, "/api/journal-access/me/request", "").statusCode()).isEqualTo(200);
                    assertThat(status(patient)).as(pnr).isEqualTo("AWAITING_RECORD");
                    assertThat(linkOf(email)).isNull();
                }
            }
        }
    }
}
