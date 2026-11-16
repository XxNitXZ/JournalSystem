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

import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
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
import com.medinexus.medicore.auth.service.AccountFhirIdentityService;
import com.medinexus.medicore.auth.service.RegistrationService;
import com.medinexus.medicore.fhir.support.LocalFhirServer;
import com.medinexus.medicore.support.TestPersonnummer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * US-05.01 end to end: DOCTOR and STAFF with a linked Practitioner write clinical notes on existing visits of
 * registered and imported Patients; notes are FHIR DocumentReferences only; a PATIENT reads only their own after
 * approved journal access. Real HTTP with session cookies and CSRF, PostgreSQL {@code medinexus_test} only and a
 * local FHIR test server (nothing is written to KTH). Fictional data only. Each test deletes exactly the accounts,
 * registry entries and requests it created.
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/medinexus_test")
class ClinicalNoteIntegrationTest {

    private static final String SYSTEM = "http://electronichealth.se/identifier/personnummer";
    // Fictional note with Swedish letters, an emoji, line breaks and markup that must stay plain text.
    private static final String TEXT = "Patienten mår bättre 🙂\nÅterbesök om två veckor.\n<script>alert(1)</script>";
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
    private AccountFhirIdentityService identities;
    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();
    private final List<Long> accounts = new ArrayList<>();
    private final List<String> registered = new ArrayList<>();
    private final String password = "Synthetic-Passw0rd-" + UUID.randomUUID();
    private String suffix;
    private String patientPnr;
    private String patientId;
    private String otherPatientId;
    private String doctorPractitionerId;
    private String staffPractitionerId;
    private String encounterId;
    private String otherEncounterId;

    @BeforeEach
    void seedFhirAndCheckTheDatabase() {
        assertThat(datasourceUrl).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/medinexus_test");
        suffix = UUID.randomUUID().toString().substring(0, 8);
        patientPnr = TestPersonnummer.next();
        patientId = patient("syn-note-" + suffix, patientPnr, true);
        otherPatientId = patient("syn-note-other-" + suffix, TestPersonnummer.next(), true);
        doctorPractitionerId = practitioner("syn-note-dr-" + suffix, "Ali", "Khan");
        staffPractitionerId = practitioner("syn-note-staff-" + suffix, "Sara", "Lind");
        encounterId = encounter("syn-note-enc-" + suffix, patientId, "2026-11-10T14:30:00+01:00");
        otherEncounterId = encounter("syn-note-enc-other-" + suffix, otherPatientId, "2026-11-09T10:00:00+01:00");
    }

    @AfterEach
    void deleteEverythingThisTestCreated() {
        for (String id : registered) jdbc.update("DELETE FROM journal_access_requests WHERE fhir_patient_id = ?", id);
        for (Long id : accounts) jdbc.update("DELETE FROM journal_access_requests WHERE user_id = ?", id);
        for (String id : registered) jdbc.update("DELETE FROM registered_patients WHERE fhir_patient_id = ?", id);
        for (Long id : accounts) jdbc.update("DELETE FROM users WHERE id = ?", id);
    }

    private String patient(String id, String pnr, boolean register) {
        Patient patient = new Patient();
        patient.setId(id);
        patient.addIdentifier().setSystem(SYSTEM).setValue(pnr);
        patient.addName().setUse(HumanName.NameUse.OFFICIAL).setFamily("Anteckning").addGiven("Fiktiv");
        patient.getBirthDateElement().setValueAsString(pnr.substring(0, 4) + "-" + pnr.substring(4, 6) + "-"
                + pnr.substring(6, 8));
        fhir.seed(patient);
        if (register) jdbc.update("INSERT INTO registered_patients (fhir_patient_id) VALUES (?)", id);
        registered.add(id);
        return id;
    }

    private static String practitioner(String id, String given, String family) {
        Practitioner practitioner = new Practitioner();
        practitioner.setId(id);
        practitioner.addName().setUse(HumanName.NameUse.OFFICIAL).setFamily(family).addGiven(given).addPrefix("Dr");
        fhir.seed(practitioner);
        return id;
    }

    private static String encounter(String id, String patient, String start) {
        Encounter encounter = new Encounter();
        encounter.setId(id);
        encounter.setStatus(Encounter.EncounterStatus.FINISHED);
        encounter.setClass_(new Coding("http://terminology.hl7.org/CodeSystem/v3-ActCode", "AMB", null));
        encounter.setSubject(new Reference("Patient/" + patient));
        encounter.getPeriod().setStartElement(new org.hl7.fhir.r4.model.DateTimeType(start));
        encounter.addType().setText("Consultation");
        fhir.seed(encounter);
        return id;
    }

    private String account(Role role, String pnr) {
        String email = "nit-us0501-" + role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.test";
        accounts.add(registration.register(email, password, role, "Konto", "Namn", pnr).id());
        return email;
    }

    private long id(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    /** A clinician whose account is linked to the Practitioner by the operator command. */
    private String linked(Role role, String practitionerId) {
        String email = account(role, TestPersonnummer.next());
        identities.assign(id(email), practitionerId);
        return email;
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    private HttpClient signedIn(String email) throws Exception {
        HttpClient client = newClient();
        assertThat(post(client, "/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}",
                true).statusCode()).isEqualTo(200);
        return client;
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(HttpClient client, String path, String body, boolean withCsrf) throws Exception {
        HttpRequest.Builder builder = request(path).header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (withCsrf) {
            JsonNode token = json.readTree(get(client, "/api/auth/csrf").body());
            builder.header(token.get("headerName").asString(), token.get("token").asString());
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String note(String encounter, String content) {
        return json.createObjectNode().put("encounterId", encounter).put("content", content).toString();
    }

    private long notePosts() {
        return fhir.requests().stream().filter(line -> line.startsWith("POST /fhir/DocumentReference")).count();
    }

    private JsonNode list(HttpClient client, String patient) throws Exception {
        HttpResponse<String> response = get(client, "/api/patients/" + patient + "/notes");
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    @Test
    void doctorsAndStaffWriteNotesThatAreFhirDocumentReferencesOnly(CapturedOutput output) throws Exception {
        int requestsBefore = jdbc.queryForObject("SELECT count(*) FROM journal_access_requests", Integer.class);
        try (HttpClient doctor = signedIn(linked(Role.DOCTOR, doctorPractitionerId));
                HttpClient staff = signedIn(linked(Role.STAFF, staffPractitionerId))) {
            assertThat(list(doctor, patientId)).isEmpty();

            HttpResponse<String> first = post(doctor, "/api/patients/" + patientId + "/notes",
                    note(encounterId, "  " + TEXT.replace("\n", "\r\n") + "\n"), true);
            assertThat(first.statusCode()).isEqualTo(201);
            JsonNode created = json.readTree(first.body());
            assertThat(created.get("content").asString()).isEqualTo(TEXT);
            assertThat(created.get("author").asString()).isEqualTo("Dr Ali Khan");
            assertThat(created.get("encounter").get("start").asString()).isEqualTo("2026-11-10T14:30:00+01:00");
            assertThat(created.get("status").asString()).isEqualTo("current");

            // Stored in FHIR with the Patient from the path, the visit, the clinician's own Practitioner and UTF-8 text.
            DocumentReference stored = (DocumentReference) fhir.stored("DocumentReference", created.get("id").asString());
            assertThat(stored.getSubject().getReference()).isEqualTo("Patient/" + patientId);
            assertThat(stored.getContext().getEncounterFirstRep().getReference()).isEqualTo("Encounter/" + encounterId);
            assertThat(stored.getAuthorFirstRep().getReference()).isEqualTo("Practitioner/" + doctorPractitionerId);
            assertThat(stored.getType().getCodingFirstRep().getCode()).isEqualTo("11506-3");
            assertThat(stored.getContentFirstRep().getAttachment().getContentType()).isEqualTo("text/plain");
            assertThat(new String(stored.getContentFirstRep().getAttachment().getData(), StandardCharsets.UTF_8))
                    .isEqualTo(TEXT);

            Thread.sleep(1_100);
            HttpResponse<String> second = post(staff, "/api/patients/" + patientId + "/notes",
                    note(encounterId, "Uppföljning per telefon."), true);
            assertThat(second.statusCode()).isEqualTo(201);
            assertThat(json.readTree(second.body()).get("author").asString()).isEqualTo("Dr Sara Lind");

            // Another Patient's note never appears.
            DocumentReference foreign = new DocumentReference();
            foreign.setId("syn-note-foreign-" + suffix);
            foreign.setSubject(new Reference("Patient/" + otherPatientId));
            foreign.addContent().getAttachment().setContentType("text/plain").setData("Annan".getBytes(StandardCharsets.UTF_8));
            fhir.seed(foreign);

            for (HttpClient clinician : List.of(doctor, staff)) {
                JsonNode notes = list(clinician, patientId);
                assertThat(notes).hasSize(2);
                assertThat(notes.get(0).get("content").asString()).isEqualTo("Uppföljning per telefon.");
                assertThat(notes.get(1).get("content").asString()).isEqualTo(TEXT);
                assertThat(notes.get(1).get("encounter").get("type").asString()).isEqualTo("Consultation");
                assertThat(notes.toString()).doesNotContain("Annan", "\"data\"");
            }
        }
        // Nothing about notes is stored in PostgreSQL.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name ILIKE '%note%'"
                + " OR table_name ILIKE '%document%'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_access_requests", Integer.class))
                .isEqualTo(requestsBefore);
        assertThat(output.getAll()).contains("Clinical note created: accountId=")
                .doesNotContain("Patienten mår", "Uppföljning", patientPnr);
    }

    @Test
    void invalidNotesForeignVisitsAndCliniciansWithoutPractitionerAreRejectedBeforeAnyWrite() throws Exception {
        String unregistered = patient("syn-note-unregistered-" + suffix, TestPersonnummer.next(), false);
        try (HttpClient doctor = signedIn(linked(Role.DOCTOR, doctorPractitionerId));
                HttpClient unlinkedStaff = signedIn(account(Role.STAFF, TestPersonnummer.next()))) {
            long postsBefore = notePosts();
            record Case(String patient, String body, int status, String code) {
            }
            for (Case c : List.of(
                    new Case(patientId, note(otherEncounterId, TEXT), 422, "ENCOUNTER_NOT_FOR_PATIENT"),
                    new Case(patientId, note("syn-note-enc-missing", TEXT), 422, "ENCOUNTER_NOT_FOUND"),
                    new Case(patientId, "{\"content\":\"x\"}", 400, "ENCOUNTER_REQUIRED"),
                    new Case(patientId, note(encounterId, ""), 400, "CONTENT_REQUIRED"),
                    new Case(patientId, note(encounterId, " \n\t "), 400, "CONTENT_REQUIRED"),
                    new Case(patientId, note(encounterId, "ä".repeat(10_001)), 400, "CONTENT_TOO_LONG"),
                    new Case(patientId, "{\"encounterId\":\"" + encounterId + "\",\"content\":\"x\",\"practitionerId\":\""
                            + staffPractitionerId + "\"}", 400, "INVALID_REQUEST"),
                    new Case(unregistered, note(encounterId, TEXT), 404, null),
                    new Case("bad_id", note(encounterId, TEXT), 400, null))) {
                HttpResponse<String> response = post(doctor, "/api/patients/" + c.patient() + "/notes", c.body(), true);
                assertThat(response.statusCode()).as(c.body().substring(0, Math.min(60, c.body().length())))
                        .isEqualTo(c.status());
                if (c.code() != null) assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo(c.code());
            }
            HttpResponse<String> noPractitioner = post(unlinkedStaff, "/api/patients/" + patientId + "/notes",
                    note(encounterId, TEXT), true);
            assertThat(noPractitioner.statusCode()).isEqualTo(422);
            assertThat(json.readTree(noPractitioner.body()).get("code").asString()).isEqualTo("PRACTITIONER_NOT_LINKED");
            // Without a Practitioner, the notes can still be read.
            assertThat(list(unlinkedStaff, patientId)).isEmpty();
            assertThat(get(doctor, "/api/patients/" + unregistered + "/notes").statusCode()).isEqualTo(404);
            assertThat(notePosts()).isEqualTo(postsBefore);
            // No visit was created for a note.
            assertThat(fhir.requests()).noneMatch(line -> line.startsWith("POST /fhir/Encounter"));
        }
    }

    @Test
    void patientsReadOnlyTheirOwnNotesAfterApprovedAccessAndNeverWrite() throws Exception {
        String patientEmail = account(Role.PATIENT, patientPnr);
        String doctorEmail = linked(Role.DOCTOR, doctorPractitionerId);
        try (HttpClient patient = signedIn(patientEmail); HttpClient doctor = signedIn(doctorEmail)) {
            assertThat(post(doctor, "/api/patients/" + patientId + "/notes", note(encounterId, TEXT), true).statusCode())
                    .isEqualTo(201);
            assertThat(post(doctor, "/api/patients/" + otherPatientId + "/notes", note(otherEncounterId, "Annan patient"),
                    true).statusCode()).isEqualTo(201);
            // Writing notes never creates a journal access request: access stays patient-initiated.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_access_requests WHERE user_id = ?",
                    Integer.class, id(patientEmail))).isZero();

            // Before access is approved: no record, no notes.
            HttpResponse<String> unlinked = get(patient, "/api/patients/me/notes");
            assertThat(unlinked.statusCode()).isEqualTo(404);
            assertThat(json.readTree(unlinked.body()).get("code").asString()).isEqualTo("RECORD_NOT_LINKED");

            assertThat(post(patient, "/api/journal-access/me/request", "", true).statusCode()).isEqualTo(200);
            long requestId = jdbc.queryForObject("SELECT id FROM journal_access_requests WHERE user_id = ?", Long.class,
                    id(patientEmail));
            HttpResponse<String> pending = get(patient, "/api/patients/me/notes");
            assertThat(pending.statusCode()).isEqualTo(404);
            assertThat(post(doctor, "/api/journal-access-requests/" + requestId + "/approve", "", true).statusCode())
                    .isEqualTo(200);

            HttpResponse<String> own = get(patient, "/api/patients/me/notes?patientId=" + otherPatientId);
            assertThat(own.statusCode()).isEqualTo(200);
            JsonNode notes = json.readTree(own.body());
            assertThat(notes).hasSize(1);
            assertThat(notes.get(0).get("content").asString()).isEqualTo(TEXT);
            assertThat(notes.get(0).get("author").asString()).isEqualTo("Dr Ali Khan");
            assertThat(own.body()).doesNotContain("Annan patient");

            assertThat(get(patient, "/api/patients/" + otherPatientId + "/notes").statusCode()).isEqualTo(403);
            assertThat(get(patient, "/api/patients/" + patientId + "/notes").statusCode()).isEqualTo(403);
            long postsBefore = notePosts();
            assertThat(post(patient, "/api/patients/" + patientId + "/notes", note(encounterId, TEXT), true).statusCode())
                    .isEqualTo(403);
            assertThat(post(patient, "/api/patients/me/notes", note(encounterId, TEXT), true).statusCode())
                    .isEqualTo(403);
            assertThat(notePosts()).isEqualTo(postsBefore);
            // My Medical Record and own visits still work.
            assertThat(get(patient, "/api/patients/me").statusCode()).isEqualTo(200);
            assertThat(get(patient, "/api/patients/me/encounters").statusCode()).isEqualTo(200);
        }
    }

    @Test
    void importedPatientsGetVisitsAndNotesAndOtherFeaturesKeepWorking() throws Exception {
        String imported = patient("syn-note-imported-" + suffix, TestPersonnummer.next(), false);
        try (HttpClient doctor = signedIn(linked(Role.DOCTOR, doctorPractitionerId)); HttpClient anonymous = newClient()) {
            assertThat(post(doctor, "/api/patients/import", "{\"fhirPatientId\":\"" + imported + "\"}", true).statusCode())
                    .isEqualTo(201);
            HttpResponse<String> visit = post(doctor, "/api/patients/" + imported + "/encounters",
                    "{\"startTime\":\"2026-11-11T09:00:00+01:00\",\"practitionerId\":\"" + doctorPractitionerId
                            + "\",\"type\":\"CONSULTATION\"}", true);
            assertThat(visit.statusCode()).isEqualTo(201);
            String visitId = json.readTree(visit.body()).get("id").asString();

            HttpResponse<String> created = post(doctor, "/api/patients/" + imported + "/notes", note(visitId, TEXT), true);
            assertThat(created.statusCode()).isEqualTo(201);
            JsonNode notes = list(doctor, imported);
            assertThat(notes).hasSize(1);
            assertThat(notes.get(0).get("encounter").get("id").asString()).isEqualTo(visitId);
            assertThat(notes.get(0).get("encounter").get("type").asString()).isEqualTo("Consultation");

            // The profile, encounters and the clinician inbox still work.
            assertThat(get(doctor, "/api/patients/" + imported).statusCode()).isEqualTo(200);
            assertThat(get(doctor, "/api/patients/" + imported + "/encounters").statusCode()).isEqualTo(200);
            assertThat(get(doctor, "/api/messages/inbox").statusCode()).isEqualTo(200);

            // Anonymous requests and requests without CSRF are rejected.
            assertThat(get(anonymous, "/api/patients/" + imported + "/notes").statusCode()).isEqualTo(401);
            assertThat(get(anonymous, "/api/patients/me/notes").statusCode()).isEqualTo(401);
            long postsBefore = notePosts();
            assertThat(post(anonymous, "/api/patients/" + imported + "/notes", note(visitId, TEXT), true).statusCode())
                    .isEqualTo(401);
            assertThat(post(doctor, "/api/patients/" + imported + "/notes", note(visitId, TEXT), false).statusCode())
                    .isEqualTo(403);
            assertThat(notePosts()).isEqualTo(postsBefore);
        }
    }
}
