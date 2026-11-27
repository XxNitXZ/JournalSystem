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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
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
 * Import of existing FHIR Patients end to end: discovery search on the (local) FHIR server, reference import into
 * {@code registered_patients}, normal profile, search and Encounters afterwards, and journal access matching of a
 * waiting request. Real HTTP with session cookies and CSRF, PostgreSQL {@code medinexus_test} only and a local FHIR
 * test server (nothing is written to KTH). Fictional data only.
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/medinexus_test")
class PatientImportIntegrationTest {

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
    private String family;

    @BeforeEach
    void onlyTheTestDatabase() {
        assertThat(datasourceUrl).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/medinexus_test");
        // A family name unique to this test, so that other tests' Patients on the local server never match.
        StringBuilder letters = new StringBuilder();
        for (int i = 0; i < 6; i++) letters.append((char) ('a' + java.util.concurrent.ThreadLocalRandom.current().nextInt(26)));
        family = "Importsson" + letters;
    }

    @AfterEach
    void deleteEverythingThisTestCreated() {
        for (String id : patients) jdbc.update("DELETE FROM journal_access_requests WHERE fhir_patient_id = ?", id);
        for (Long id : accounts) jdbc.update("DELETE FROM journal_access_requests WHERE user_id = ?", id);
        for (String id : patients) jdbc.update("DELETE FROM registered_patients WHERE fhir_patient_id = ?", id);
        for (Long id : accounts) jdbc.update("DELETE FROM registered_patients WHERE registered_by = ?", id);
        for (Long id : accounts) jdbc.update("DELETE FROM users WHERE id = ?", id);
    }

    /** An existing Patient on the FHIR server, created by someone else (not registered in MediCore). */
    private String existing(String given, String pnr, String birthDate) {
        Patient patient = new Patient();
        String id = "ext-" + UUID.randomUUID().toString().substring(0, 8);
        patient.setId(id);
        if (given != null) patient.addName().setUse(HumanName.NameUse.OFFICIAL).setFamily(family).addGiven(given);
        if (pnr != null) patient.addIdentifier().setSystem(SYSTEM).setValue(pnr);
        if (birthDate != null) patient.getBirthDateElement().setValueAsString(birthDate);
        patient.setGender(AdministrativeGender.FEMALE);
        fhir.seed(patient);
        patients.add(id);
        return id;
    }

    private static String birthDate(String pnr) {
        return pnr.substring(0, 4) + "-" + pnr.substring(4, 6) + "-" + pnr.substring(6, 8);
    }

    private String account(Role role, String pnr) {
        String email = "nit-import-" + role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.test";
        accounts.add(registration.register(email, password, role, "Konto", "Namn", pnr).id());
        return email;
    }

    private long id(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    private HttpClient signedIn(String email) throws Exception {
        HttpClient client = newClient();
        assertThat(post(client, "/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .statusCode()).isEqualTo(200);
        return client;
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(HttpClient client, String path, String body) throws Exception {
        JsonNode token = json.readTree(get(client, "/api/auth/csrf").body());
        return client.send(request(path).header(token.get("headerName").asString(), token.get("token").asString())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode search(HttpClient client, String mode, String value) throws Exception {
        HttpResponse<String> response = post(client, "/api/patients/import/search",
                "{\"mode\":\"" + mode + "\",\"value\":\"" + value + "\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private HttpResponse<String> importPatient(HttpClient client, String id) throws Exception {
        return post(client, "/api/patients/import", "{\"fhirPatientId\":\"" + id + "\"}");
    }

    private long patientWrites() {
        return fhir.requests().stream().filter(line -> line.equals("POST /fhir/Patient") || line.startsWith("PUT ")
                || line.startsWith("DELETE ")).count();
    }

    private int registryRows(String id) {
        return jdbc.queryForObject("SELECT count(*) FROM registered_patients WHERE fhir_patient_id = ?", Integer.class, id);
    }

    @Test
    void cliniciansFindExistingPatientsByNamePersonnummerAndIdAndPatientsCannot() throws Exception {
        String pnr = TestPersonnummer.next();
        String withPnr = existing("Ali", pnr, birthDate(pnr));
        String withoutPnr = existing("Ali", null, "1990-01-01");
        try (HttpClient doctor = signedIn(account(Role.DOCTOR, TestPersonnummer.next()));
                HttpClient staff = signedIn(account(Role.STAFF, TestPersonnummer.next()));
                HttpClient patient = signedIn(account(Role.PATIENT, TestPersonnummer.next()))) {
            for (HttpClient clinician : List.of(doctor, staff)) {
                JsonNode byName = search(clinician, "NAME", "Ali " + family);
                assertThat(byName.get("truncated").asBoolean()).isFalse();
                assertThat(byName.get("patients")).hasSize(2);
                for (JsonNode candidate : byName.get("patients")) {
                    assertThat(candidate.get("registered").asBoolean()).isFalse();
                    assertThat(candidate.get("importable").asBoolean()).isTrue();
                    assertThat(candidate.get("name").asString()).isEqualTo("Ali " + family);
                }
            }
            JsonNode byPnr = search(doctor, "PERSONNUMMER", pnr.substring(0, 8) + "-" + pnr.substring(8));
            assertThat(byPnr.get("patients")).singleElement().satisfies(candidate -> {
                assertThat(candidate.get("id").asString()).isEqualTo(withPnr);
                assertThat(candidate.get("journalMatchable").asBoolean()).isTrue();
            });
            JsonNode byId = search(doctor, "ID", withoutPnr);
            assertThat(byId.get("patients")).singleElement().satisfies(candidate -> {
                assertThat(candidate.get("personnummer").isNull()).isTrue();
                assertThat(candidate.get("journalMatchable").asBoolean()).isFalse();
            });
            assertThat(search(doctor, "ID", "ext-missing").get("patients")).isEmpty();
            assertThat(post(doctor, "/api/patients/import/search", "{\"mode\":\"NAME\",\"value\":\"1\"}").statusCode())
                    .isEqualTo(400);

            // Patients never browse the shared directory or import.
            assertThat(post(patient, "/api/patients/import/search", "{\"mode\":\"NAME\",\"value\":\"Ali\"}")
                    .statusCode()).isEqualTo(403);
            assertThat(importPatient(patient, withPnr).statusCode()).isEqualTo(403);
            try (HttpClient anonymous = newClient()) {
                assertThat(importPatient(anonymous, withPnr).statusCode()).isEqualTo(401);
            }
        }
        assertThat(registryRows(withPnr)).isZero();
    }

    @Test
    void anImportRecordsOnlyTheReferenceAndTheExistingPatientWorksAsUsual(CapturedOutput output) throws Exception {
        String pnr = TestPersonnummer.next();
        String patientId = existing("Sara", pnr, birthDate(pnr));
        Encounter visit = new Encounter();
        visit.setId("enc-" + UUID.randomUUID().toString().substring(0, 8));
        visit.setStatus(Encounter.EncounterStatus.FINISHED);
        visit.setClass_(new Coding("http://terminology.hl7.org/CodeSystem/v3-ActCode", "AMB", null));
        visit.setSubject(new Reference("Patient/" + patientId));
        visit.getPeriod().setStartElement(new DateTimeType("2026-10-01T09:00:00Z"));
        fhir.seed(visit);
        String doctorEmail = account(Role.DOCTOR, TestPersonnummer.next());
        int usersBefore = jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);
        long writesBefore = patientWrites();
        try (HttpClient doctor = signedIn(doctorEmail)) {
            // Before the import the Patient is not part of MediCore.
            assertThat(get(doctor, "/api/patients/" + patientId).statusCode()).isEqualTo(404);
            assertThat(get(doctor, "/api/patients/" + patientId + "/encounters").statusCode()).isEqualTo(404);

            HttpResponse<String> imported = importPatient(doctor, patientId);
            assertThat(imported.statusCode()).isEqualTo(201);
            assertThat(json.readTree(imported.body()).get("registered").asBoolean()).isTrue();
            assertThat(jdbc.queryForObject("SELECT registered_by FROM registered_patients WHERE fhir_patient_id = ?",
                    Long.class, patientId)).isEqualTo(id(doctorEmail));
            assertThat(registryRows(patientId)).isEqualTo(1);

            // Now a normal MediCore Patient: profile, search and existing Encounters.
            HttpResponse<String> profile = get(doctor, "/api/patients/" + patientId);
            assertThat(profile.statusCode()).isEqualTo(200);
            assertThat(json.readTree(profile.body()).get("names").get(0).get("family").asString()).isEqualTo(family);
            assertThat(post(doctor, "/api/patients/search", "{\"query\":\"" + family + "\"}").body()).contains(patientId);
            JsonNode encounters = json.readTree(get(doctor, "/api/patients/" + patientId + "/encounters").body());
            assertThat(encounters).singleElement().satisfies(entry ->
                    assertThat(entry.get("start").asString()).isEqualTo("2026-10-01T09:00:00Z"));
            assertThat(search(doctor, "ID", patientId).get("patients").get(0).get("registered").asBoolean()).isTrue();

            // A second import is a duplicate.
            HttpResponse<String> again = importPatient(doctor, patientId);
            assertThat(again.statusCode()).isEqualTo(409);
            assertThat(json.readTree(again.body()).get("code").asString()).isEqualTo("ALREADY_REGISTERED");
        }
        // Nothing was created, changed or deleted on the FHIR server, and no patient data is in PostgreSQL.
        assertThat(patientWrites()).isEqualTo(writesBefore);
        assertThat(((Patient) fhir.stored("Patient", patientId)).getMeta().getVersionId()).isEqualTo("1");
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = "
                + "'registered_patients'", String.class))
                .containsExactlyInAnyOrder("fhir_patient_id", "registered_by", "registered_at");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(usersBefore);
        assertThat(output.getAll()).contains("Patient imported: accountId=").doesNotContain(pnr, family);
    }

    @Test
    void concurrentImportsCreateExactlyOneRegistryRow() throws Exception {
        String pnr = TestPersonnummer.next();
        String patientId = existing("Samtidig", pnr, birthDate(pnr));
        List<HttpClient> clinicians = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
            for (int i = 0; i < 5; i++) {
                clinicians.add(signedIn(account(i % 2 == 0 ? Role.DOCTOR : Role.STAFF, TestPersonnummer.next())));
            }
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (HttpClient clinician : clinicians) {
                JsonNode token = json.readTree(get(clinician, "/api/auth/csrf").body());
                HttpRequest request = request("/api/patients/import")
                        .header(token.get("headerName").asString(), token.get("token").asString())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"fhirPatientId\":\"" + patientId + "\"}")).build();
                results.add(pool.submit(() -> {
                    start.await();
                    return clinician.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) statuses.add(result.get());
            assertThat(statuses).containsOnly(201, 409);
            assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
        } finally {
            clinicians.forEach(HttpClient::close);
        }
        assertThat(registryRows(patientId)).isEqualTo(1);
    }

    @Test
    void unknownInvalidAndUnusablePatientsAreRejected() throws Exception {
        String nameless = existing(null, TestPersonnummer.next(), "1990-01-01");
        try (HttpClient doctor = signedIn(account(Role.DOCTOR, TestPersonnummer.next()))) {
            assertThat(importPatient(doctor, "ext-does-not-exist").statusCode()).isEqualTo(404);
            for (String body : List.of("{\"fhirPatientId\":\"bad_id\"}", "{\"fhirPatientId\":\"Patient/x\"}",
                    "{\"fhirPatientId\":\"ext-1\",\"personnummer\":\"200412201971\"}", "{}")) {
                HttpResponse<String> response = post(doctor, "/api/patients/import", body);
                assertThat(response.statusCode()).as(body).isEqualTo(400);
            }
            HttpResponse<String> unusable = importPatient(doctor, nameless);
            assertThat(unusable.statusCode()).isEqualTo(422);
            assertThat(json.readTree(unusable.body()).get("code").asString()).isEqualTo("PATIENT_NOT_IMPORTABLE");
        }
        assertThat(registryRows(nameless)).isZero();
    }

    // ---------- journal access ----------

    private String requestStatus(String email) {
        return jdbc.queryForObject("SELECT status FROM journal_access_requests WHERE user_id = ?", String.class, id(email));
    }

    @Test
    void anImportLetsAWaitingRequestMatchButNeverCreatesOneOrGrantsAccess() throws Exception {
        String pnr = TestPersonnummer.next();
        String patientId = existing("Vantande", pnr, birthDate(pnr));
        String patientEmail = account(Role.PATIENT, pnr);
        String bystanderEmail = account(Role.PATIENT, TestPersonnummer.next());
        try (HttpClient patient = signedIn(patientEmail); HttpClient doctor = signedIn(account(Role.DOCTOR,
                TestPersonnummer.next()))) {
            // The patient asked before the record was part of MediCore: it waits.
            assertThat(post(patient, "/api/journal-access/me/request", "").statusCode()).isEqualTo(200);
            assertThat(requestStatus(patientEmail)).isEqualTo("AWAITING_RECORD");

            assertThat(importPatient(doctor, patientId).statusCode()).isEqualTo(201);

            // Matched by exact personnummer, now waiting for a doctor; not approved, not linked.
            assertThat(requestStatus(patientEmail)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT fhir_patient_id FROM journal_access_requests WHERE user_id = ?",
                    String.class, id(patientEmail))).isEqualTo(patientId);
            assertThat(jdbc.queryForObject("SELECT fhir_patient_id FROM users WHERE id = ?", String.class,
                    id(patientEmail))).isNull();
            assertThat(get(patient, "/api/patients/me").statusCode()).isEqualTo(404);
            // No request was created for anybody else.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_access_requests WHERE user_id = ?",
                    Integer.class, id(bystanderEmail))).isZero();

            // Only a doctor's approval grants access.
            long requestId = jdbc.queryForObject("SELECT id FROM journal_access_requests WHERE user_id = ?", Long.class,
                    id(patientEmail));
            assertThat(post(doctor, "/api/journal-access-requests/" + requestId + "/approve", "").statusCode())
                    .isEqualTo(200);
            assertThat(get(patient, "/api/patients/me").statusCode()).isEqualTo(200);
            assertThat(get(patient, "/api/patients/me/encounters").statusCode()).isEqualTo(200);
            assertThat(get(patient, "/api/patients/" + patientId + "/encounters").statusCode()).isEqualTo(403);
        }
    }

    @Test
    void missingOrAmbiguousPersonnummerNeverLinksAnything() throws Exception {
        String pnr = TestPersonnummer.next();
        String patientEmail = account(Role.PATIENT, pnr);
        // Same name as the account, but no personnummer: importable, never matched by name.
        String noPnr = existing("Konto", null, birthDate(pnr));
        // Two existing Patients with the account's personnummer: ambiguous.
        String first = existing("Dubbel", pnr, birthDate(pnr));
        String second = existing("Dubbel", pnr, birthDate(pnr));
        try (HttpClient patient = signedIn(patientEmail); HttpClient doctor = signedIn(account(Role.DOCTOR,
                TestPersonnummer.next()))) {
            assertThat(post(patient, "/api/journal-access/me/request", "").statusCode()).isEqualTo(200);
            assertThat(requestStatus(patientEmail)).isEqualTo("AWAITING_RECORD");

            assertThat(importPatient(doctor, noPnr).statusCode()).isEqualTo(201);
            assertThat(requestStatus(patientEmail)).isEqualTo("AWAITING_RECORD");

            // The first one was registered earlier while the request did not wait yet; the second import makes it
            // ambiguous, so nothing is attached.
            jdbc.update("INSERT INTO registered_patients (fhir_patient_id) VALUES (?)", first);
            assertThat(importPatient(doctor, second).statusCode()).isEqualTo(201);
            assertThat(requestStatus(patientEmail)).isEqualTo("AWAITING_RECORD");
            assertThat(jdbc.queryForObject("SELECT fhir_patient_id FROM users WHERE id = ?", String.class,
                    id(patientEmail))).isNull();
            assertThat(json.readTree(get(patient, "/api/journal-access/me").body()).get("status").asString())
                    .isEqualTo("AWAITING_RECORD");
        }
    }
}
