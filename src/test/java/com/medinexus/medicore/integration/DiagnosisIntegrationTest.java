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

import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Condition;
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
 * US-05.02 end to end: DOCTOR and STAFF with a linked Practitioner record diagnoses (FHIR Conditions) for registered
 * and imported Patients, optionally on a visit; existing Conditions created elsewhere are listed too; a PATIENT reads
 * only their own after approved journal access. Real HTTP with session cookies and CSRF, PostgreSQL
 * {@code medinexus_test} only and a local FHIR test server (nothing is written to KTH). Fictional data only.
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/medinexus_test")
class DiagnosisIntegrationTest {

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
        patientId = patient("syn-dx-" + suffix, patientPnr, true);
        otherPatientId = patient("syn-dx-other-" + suffix, TestPersonnummer.next(), true);
        doctorPractitionerId = practitioner("syn-dx-dr-" + suffix, "Ali", "Khan");
        staffPractitionerId = practitioner("syn-dx-staff-" + suffix, "Sara", "Lind");
        encounterId = encounter("syn-dx-enc-" + suffix, patientId);
        otherEncounterId = encounter("syn-dx-enc-other-" + suffix, otherPatientId);
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
        patient.addName().setUse(HumanName.NameUse.OFFICIAL).setFamily("Diagnos").addGiven("Fiktiv");
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

    private static String encounter(String id, String patient) {
        Encounter encounter = new Encounter();
        encounter.setId(id);
        encounter.setStatus(Encounter.EncounterStatus.FINISHED);
        encounter.setClass_(new Coding("http://terminology.hl7.org/CodeSystem/v3-ActCode", "AMB", null));
        encounter.setSubject(new Reference("Patient/" + patient));
        encounter.getPeriod().setStartElement(new org.hl7.fhir.r4.model.DateTimeType("2026-11-10T14:30:00+01:00"));
        encounter.addType().setText("Consultation");
        fhir.seed(encounter);
        return id;
    }

    /** A Condition that exists on the server already, created elsewhere: coding only, no statuses or dates. */
    private static String existingCondition(String id, String patient) {
        Condition condition = new Condition();
        condition.setId(id);
        condition.setSubject(new Reference("Patient/" + patient));
        condition.setCode(new CodeableConcept(new Coding("http://snomed.info/sct", "195967001", "Asthma")));
        fhir.seed(condition);
        return id;
    }

    private String account(Role role, String pnr) {
        String email = "nit-us0502-" + role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.test";
        accounts.add(registration.register(email, password, role, "Konto", "Namn", pnr).id());
        return email;
    }

    private long id(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

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

    private String diagnosis(String text, String code, String system, String encounter) {
        var node = json.createObjectNode().put("diagnosis", text);
        if (code != null) node.put("code", code).put("codeSystem", system);
        if (encounter != null) node.put("encounterId", encounter);
        return node.toString();
    }

    private long conditionPosts() {
        return fhir.requests().stream().filter(line -> line.startsWith("POST /fhir/Condition")).count();
    }

    private JsonNode list(HttpClient client, String patient) throws Exception {
        HttpResponse<String> response = get(client, "/api/patients/" + patient + "/conditions");
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    @Test
    void doctorsAndStaffRecordDiagnosesThatAreFhirConditionsOnly(CapturedOutput output) throws Exception {
        int requestsBefore = jdbc.queryForObject("SELECT count(*) FROM journal_access_requests", Integer.class);
        existingCondition("syn-dx-foreign-" + suffix, otherPatientId);
        try (HttpClient doctor = signedIn(linked(Role.DOCTOR, doctorPractitionerId));
                HttpClient staff = signedIn(linked(Role.STAFF, staffPractitionerId))) {
            assertThat(list(doctor, patientId)).isEmpty();

            HttpResponse<String> first = post(doctor, "/api/patients/" + patientId + "/conditions",
                    json.createObjectNode().put("diagnosis", "Typ 2-diabetes").put("code", "44054006")
                            .put("codeSystem", "SNOMED_CT").put("onsetDate", "2026-10-01")
                            .put("encounterId", encounterId).toString(), true);
            assertThat(first.statusCode()).isEqualTo(201);
            JsonNode created = json.readTree(first.body());
            assertThat(created.get("diagnosis").asString()).isEqualTo("Typ 2-diabetes");
            assertThat(created.get("recorder").asString()).isEqualTo("Dr Ali Khan");
            assertThat(created.get("encounter").get("type").asString()).isEqualTo("Consultation");

            Condition stored = (Condition) fhir.stored("Condition", created.get("id").asString());
            assertThat(stored.getSubject().getReference()).isEqualTo("Patient/" + patientId);
            assertThat(stored.getCode().getText()).isEqualTo("Typ 2-diabetes");
            assertThat(stored.getCode().getCodingFirstRep().getSystem()).isEqualTo("http://snomed.info/sct");
            assertThat(stored.getCode().getCodingFirstRep().getCode()).isEqualTo("44054006");
            assertThat(stored.getClinicalStatus().getCodingFirstRep().getCode()).isEqualTo("active");
            assertThat(stored.getVerificationStatus().getCodingFirstRep().getCode()).isEqualTo("confirmed");
            assertThat(stored.getCategoryFirstRep().getCodingFirstRep().getCode()).isEqualTo("encounter-diagnosis");
            assertThat(stored.getOnsetDateTimeType().getValueAsString()).isEqualTo("2026-10-01");
            assertThat(stored.getRecordedDateElement().getValueAsString()).isEqualTo(created.get("recordedDate").asString());
            assertThat(stored.getEncounter().getReference()).isEqualTo("Encounter/" + encounterId);
            assertThat(stored.getRecorder().getReference()).isEqualTo("Practitioner/" + doctorPractitionerId);

            Thread.sleep(1_100);
            HttpResponse<String> second = post(staff, "/api/patients/" + patientId + "/conditions",
                    diagnosis("Misstänkt migrän", null, null, null), true);
            assertThat(second.statusCode()).isEqualTo(201);
            Condition textOnly = (Condition) fhir.stored("Condition", json.readTree(second.body()).get("id").asString());
            assertThat(textOnly.getCode().hasCoding()).isFalse();
            assertThat(textOnly.getCategoryFirstRep().getCodingFirstRep().getCode()).isEqualTo("problem-list-item");
            assertThat(textOnly.getRecorder().getReference()).isEqualTo("Practitioner/" + staffPractitionerId);

            for (HttpClient clinician : List.of(doctor, staff)) {
                JsonNode diagnoses = list(clinician, patientId);
                assertThat(diagnoses).hasSize(2);
                assertThat(diagnoses.get(0).get("diagnosis").asString()).isEqualTo("Misstänkt migrän");
                assertThat(diagnoses.get(0).get("recorder").asString()).isEqualTo("Dr Sara Lind");
                assertThat(diagnoses.get(1).get("code").get("code").asString()).isEqualTo("44054006");
                assertThat(diagnoses.toString()).doesNotContain("Asthma");
            }
        }
        // No diagnosis data in PostgreSQL.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND"
                + " (table_name ILIKE '%condition%' OR table_name ILIKE '%diagnos%')", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_access_requests", Integer.class))
                .isEqualTo(requestsBefore);
        assertThat(output.getAll()).contains("Diagnosis recorded: accountId=")
                .doesNotContain("Typ 2-diabetes", "Misstänkt", patientPnr);
    }

    @Test
    void invalidDiagnosesForeignVisitsAndCliniciansWithoutPractitionerAreRejectedBeforeAnyWrite() throws Exception {
        String unregistered = patient("syn-dx-unregistered-" + suffix, TestPersonnummer.next(), false);
        try (HttpClient doctor = signedIn(linked(Role.DOCTOR, doctorPractitionerId));
                HttpClient unlinkedStaff = signedIn(account(Role.STAFF, TestPersonnummer.next()))) {
            long postsBefore = conditionPosts();
            record Case(String patient, String body, int status, String code) {
            }
            for (Case c : List.of(
                    new Case(patientId, diagnosis("Astma", null, null, otherEncounterId), 422, "ENCOUNTER_NOT_FOR_PATIENT"),
                    new Case(patientId, diagnosis("Astma", null, null, "syn-dx-enc-missing"), 422, "ENCOUNTER_NOT_FOUND"),
                    new Case(patientId, "{\"diagnosis\":\" \"}", 400, "DIAGNOSIS_REQUIRED"),
                    new Case(patientId, diagnosis("Astma", "44054007", "SNOMED_CT", null), 400, "CODE_INVALID"),
                    new Case(patientId, diagnosis("Astma", "J45", "LOCAL", null), 400, "CODE_SYSTEM_INVALID"),
                    new Case(patientId, "{\"diagnosis\":\"Astma\",\"clinicalStatus\":\"CURED\"}", 400,
                            "CLINICAL_STATUS_INVALID"),
                    new Case(patientId, "{\"diagnosis\":\"Astma\",\"onsetDate\":\"2099-01-01\"}", 400, "ONSET_IN_FUTURE"),
                    new Case(patientId, "{\"diagnosis\":\"Astma\",\"subject\":\"Patient/" + otherPatientId + "\"}", 400,
                            "INVALID_REQUEST"),
                    new Case(unregistered, diagnosis("Astma", null, null, null), 404, null),
                    new Case("bad_id", diagnosis("Astma", null, null, null), 400, null))) {
                HttpResponse<String> response = post(doctor, "/api/patients/" + c.patient() + "/conditions", c.body(), true);
                assertThat(response.statusCode()).as(c.body()).isEqualTo(c.status());
                if (c.code() != null) assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo(c.code());
            }
            HttpResponse<String> noPractitioner = post(unlinkedStaff, "/api/patients/" + patientId + "/conditions",
                    diagnosis("Astma", null, null, null), true);
            assertThat(noPractitioner.statusCode()).isEqualTo(422);
            assertThat(json.readTree(noPractitioner.body()).get("code").asString()).isEqualTo("PRACTITIONER_NOT_LINKED");
            assertThat(list(unlinkedStaff, patientId)).isEmpty();
            assertThat(get(doctor, "/api/patients/" + unregistered + "/conditions").statusCode()).isEqualTo(404);
            assertThat(conditionPosts()).isEqualTo(postsBefore);
            assertThat(fhir.requests()).noneMatch(line -> line.startsWith("POST /fhir/Encounter"));
        }
    }

    @Test
    void patientsReadOnlyTheirOwnDiagnosesAfterApprovedAccessAndNeverWrite() throws Exception {
        String patientEmail = account(Role.PATIENT, patientPnr);
        String doctorEmail = linked(Role.DOCTOR, doctorPractitionerId);
        try (HttpClient patient = signedIn(patientEmail); HttpClient doctor = signedIn(doctorEmail)) {
            assertThat(post(doctor, "/api/patients/" + patientId + "/conditions",
                    diagnosis("Hypertoni", "I10", "ICD_10", encounterId), true).statusCode()).isEqualTo(201);
            assertThat(post(doctor, "/api/patients/" + otherPatientId + "/conditions",
                    diagnosis("Annan patients diagnos", null, null, null), true).statusCode()).isEqualTo(201);
            // Recording diagnoses never creates a journal access request.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_access_requests WHERE user_id = ?",
                    Integer.class, id(patientEmail))).isZero();

            HttpResponse<String> unlinked = get(patient, "/api/patients/me/conditions");
            assertThat(unlinked.statusCode()).isEqualTo(404);
            assertThat(json.readTree(unlinked.body()).get("code").asString()).isEqualTo("RECORD_NOT_LINKED");

            assertThat(post(patient, "/api/journal-access/me/request", "", true).statusCode()).isEqualTo(200);
            long requestId = jdbc.queryForObject("SELECT id FROM journal_access_requests WHERE user_id = ?", Long.class,
                    id(patientEmail));
            assertThat(post(doctor, "/api/journal-access-requests/" + requestId + "/approve", "", true).statusCode())
                    .isEqualTo(200);

            HttpResponse<String> own = get(patient, "/api/patients/me/conditions?patientId=" + otherPatientId);
            assertThat(own.statusCode()).isEqualTo(200);
            JsonNode diagnoses = json.readTree(own.body());
            assertThat(diagnoses).hasSize(1);
            assertThat(diagnoses.get(0).get("diagnosis").asString()).isEqualTo("Hypertoni");
            assertThat(diagnoses.get(0).get("code").get("code").asString()).isEqualTo("I10");
            assertThat(own.body()).doesNotContain("Annan patients diagnos");

            assertThat(get(patient, "/api/patients/" + otherPatientId + "/conditions").statusCode()).isEqualTo(403);
            assertThat(get(patient, "/api/patients/" + patientId + "/conditions").statusCode()).isEqualTo(403);
            long postsBefore = conditionPosts();
            assertThat(post(patient, "/api/patients/" + patientId + "/conditions", diagnosis("Astma", null, null, null),
                    true).statusCode()).isEqualTo(403);
            assertThat(post(patient, "/api/patients/me/conditions", diagnosis("Astma", null, null, null), true)
                    .statusCode()).isEqualTo(403);
            assertThat(conditionPosts()).isEqualTo(postsBefore);
            assertThat(get(patient, "/api/patients/me").statusCode()).isEqualTo(200);
            assertThat(get(patient, "/api/patients/me/encounters").statusCode()).isEqualTo(200);
            assertThat(get(patient, "/api/patients/me/notes").statusCode()).isEqualTo(200);
        }
    }

    @Test
    void importedPatientsShowExistingConditionsAndGetNewOnesWithoutChangingThem() throws Exception {
        String imported = patient("syn-dx-imported-" + suffix, TestPersonnummer.next(), false);
        String existing = existingCondition("syn-dx-existing-" + suffix, imported);
        try (HttpClient doctor = signedIn(linked(Role.DOCTOR, doctorPractitionerId)); HttpClient anonymous = newClient()) {
            assertThat(post(doctor, "/api/patients/import", "{\"fhirPatientId\":\"" + imported + "\"}", true).statusCode())
                    .isEqualTo(201);
            JsonNode before = list(doctor, imported);
            assertThat(before).hasSize(1);
            assertThat(before.get(0).get("diagnosis").asString()).isEqualTo("Asthma");
            assertThat(before.get(0).get("code").get("systemName").asString()).isEqualTo("SNOMED CT");
            assertThat(before.get(0).get("clinicalStatus").isNull()).isTrue();
            assertThat(before.get(0).get("recordedDate").isNull()).isTrue();

            HttpResponse<String> visit = post(doctor, "/api/patients/" + imported + "/encounters",
                    "{\"startTime\":\"2026-11-11T09:00:00+01:00\",\"practitionerId\":\"" + doctorPractitionerId
                            + "\",\"type\":\"CONSULTATION\"}", true);
            assertThat(visit.statusCode()).isEqualTo(201);
            String visitId = json.readTree(visit.body()).get("id").asString();
            assertThat(post(doctor, "/api/patients/" + imported + "/conditions",
                    diagnosis("Akut bronkit", "J20.9", "ICD_10", visitId), true).statusCode()).isEqualTo(201);
            assertThat(post(doctor, "/api/patients/" + imported + "/notes",
                    "{\"encounterId\":\"" + visitId + "\",\"content\":\"Anteckning\"}", true).statusCode()).isEqualTo(201);

            JsonNode after = list(doctor, imported);
            assertThat(after).hasSize(2);
            assertThat(after.get(0).get("diagnosis").asString()).isEqualTo("Akut bronkit");
            assertThat(after.get(0).get("encounter").get("id").asString()).isEqualTo(visitId);
            assertThat(after.get(1).get("diagnosis").asString()).isEqualTo("Asthma");
            // The existing Condition was never changed.
            assertThat(fhir.stored("Condition", existing).getMeta().getVersionId()).isEqualTo("1");
            assertThat(fhir.requests()).noneMatch(line -> line.startsWith("PUT ") || line.startsWith("PATCH ")
                    || line.startsWith("DELETE "));

            // Profile, encounters, notes and the clinician inbox still work.
            assertThat(get(doctor, "/api/patients/" + imported).statusCode()).isEqualTo(200);
            assertThat(get(doctor, "/api/patients/" + imported + "/encounters").statusCode()).isEqualTo(200);
            assertThat(get(doctor, "/api/patients/" + imported + "/notes").statusCode()).isEqualTo(200);
            assertThat(get(doctor, "/api/messages/inbox").statusCode()).isEqualTo(200);

            assertThat(get(anonymous, "/api/patients/" + imported + "/conditions").statusCode()).isEqualTo(401);
            assertThat(get(anonymous, "/api/patients/me/conditions").statusCode()).isEqualTo(401);
            long postsBefore = conditionPosts();
            assertThat(post(anonymous, "/api/patients/" + imported + "/conditions", diagnosis("Astma", null, null, null),
                    true).statusCode()).isEqualTo(401);
            assertThat(post(doctor, "/api/patients/" + imported + "/conditions", diagnosis("Astma", null, null, null),
                    false).statusCode()).isEqualTo(403);
            assertThat(conditionPosts()).isEqualTo(postsBefore);
        }
    }
}
