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

import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Organization;
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
 * US-05.05 end to end: DOCTOR and STAFF create and list Encounters of registered Patients, a PATIENT reads only their
 * own after approved journal access. Real HTTP with session cookies and CSRF, PostgreSQL {@code medinexus_test}
 * only and a local FHIR test server (nothing is written to KTH). Fictional data only. Each test deletes exactly the
 * accounts, registry entries and requests it created; Encounters exist only on the local FHIR server.
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/medinexus_test")
class EncounterIntegrationTest {

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
    private String practitionerId;
    private String foreignPractitionerId;

    @BeforeEach
    void seedFhirAndCheckTheDatabase() {
        assertThat(datasourceUrl).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/medinexus_test");
        suffix = UUID.randomUUID().toString().substring(0, 8);
        patientPnr = TestPersonnummer.next();
        patientId = patient("syn-enc-" + suffix, patientPnr, "Besokare");
        otherPatientId = patient("syn-enc-other-" + suffix, TestPersonnummer.next(), "Annan");
        practitionerId = practitioner("syn-pract-" + suffix, "Ali", "Khan");
        foreignPractitionerId = practitioner("syn-pract-foreign-" + suffix, "Okand", "Extern");
    }

    @AfterEach
    void deleteEverythingThisTestCreated() {
        for (String id : registered) jdbc.update("DELETE FROM journal_access_requests WHERE fhir_patient_id = ?", id);
        for (Long id : accounts) jdbc.update("DELETE FROM journal_access_requests WHERE user_id = ?", id);
        for (String id : registered) jdbc.update("DELETE FROM registered_patients WHERE fhir_patient_id = ?", id);
        for (Long id : accounts) jdbc.update("DELETE FROM users WHERE id = ?", id);
    }

    private String patient(String id, String pnr, String family) {
        Patient patient = new Patient();
        patient.setId(id);
        patient.addIdentifier().setSystem(SYSTEM).setValue(pnr);
        patient.addName().setUse(HumanName.NameUse.OFFICIAL).setFamily(family).addGiven("Fiktiv");
        patient.getBirthDateElement().setValueAsString(pnr.substring(0, 4) + "-" + pnr.substring(4, 6) + "-"
                + pnr.substring(6, 8));
        fhir.seed(patient);
        jdbc.update("INSERT INTO registered_patients (fhir_patient_id) VALUES (?)", id);
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

    private String account(Role role, String pnr) {
        String email = "nit-us0505-" + role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.test";
        accounts.add(registration.register(email, password, role, "Konto", "Namn", pnr).id());
        return email;
    }

    private long id(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    /** A DOCTOR whose account is linked to the Practitioner by the operator command. */
    private String linkedDoctor() {
        String email = account(Role.DOCTOR, TestPersonnummer.next());
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
        return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(HttpClient client, String path, String body, boolean withCsrf) throws Exception {
        HttpRequest.Builder builder = request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (withCsrf) {
            JsonNode token = json.readTree(get(client, "/api/auth/csrf").body());
            builder.header(token.get("headerName").asString(), token.get("token").asString());
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String visit(String start, String practitioner, String extra) {
        return "{\"startTime\":\"" + start + "\",\"practitionerId\":\"" + practitioner + "\",\"type\":\"CONSULTATION\""
                + (extra == null ? "" : "," + extra) + "}";
    }

    private long posts() {
        return fhir.requests().stream().filter(line -> line.startsWith("POST /fhir/Encounter")).count();
    }

    private JsonNode list(HttpClient client, String patient) throws Exception {
        HttpResponse<String> response = get(client, "/api/patients/" + patient + "/encounters");
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    @Test
    void doctorsAndStaffCreateVisitsThatReferenceThePatientPractitionerAndTime(CapturedOutput output) throws Exception {
        int requestsBefore = jdbc.queryForObject("SELECT count(*) FROM journal_access_requests", Integer.class);
        try (HttpClient doctor = signedIn(linkedDoctor());
                HttpClient staff = signedIn(account(Role.STAFF, TestPersonnummer.next()))) {
            assertThat(list(doctor, patientId)).isEmpty();

            HttpResponse<String> first = post(doctor, "/api/patients/" + patientId + "/encounters",
                    visit("2026-11-05T09:15+01:00", practitionerId, null), true);
            assertThat(first.statusCode()).isEqualTo(201);
            JsonNode created = json.readTree(first.body());
            assertThat(created.get("start").asString()).isEqualTo("2026-11-05T09:15:00+01:00");
            assertThat(created.get("status").asString()).isEqualTo("finished");
            assertThat(created.get("practitioners").get(0).asString()).isEqualTo("Dr Ali Khan");
            assertThat(first.body()).doesNotContain(patientId, practitionerId);

            // Stored in FHIR with the Patient from the path, the Practitioner, the time and a valid status.
            Encounter stored = (Encounter) fhir.stored("Encounter", created.get("id").asString());
            assertThat(stored.getSubject().getReference()).isEqualTo("Patient/" + patientId);
            assertThat(stored.getParticipantFirstRep().getIndividual().getReference())
                    .isEqualTo("Practitioner/" + practitionerId);
            assertThat(stored.getParticipantFirstRep().getTypeFirstRep().getCodingFirstRep().getCode()).isEqualTo("ATND");
            assertThat(stored.getPeriod().getStartElement().getValueAsString()).isEqualTo("2026-11-05T09:15:00+01:00");
            assertThat(stored.getStatus()).isEqualTo(Encounter.EncounterStatus.FINISHED);
            assertThat(stored.getClass_().getCode()).isEqualTo("AMB");
            assertThat(stored.getTypeFirstRep().getCodingFirstRep().getCode()).isEqualTo("11429006");
            assertThat(stored.hasServiceProvider()).isFalse();
            assertThat(stored.hasLocation()).isFalse();

            // STAFF documents a later visit with the doctor's Practitioner.
            assertThat(post(staff, "/api/patients/" + patientId + "/encounters",
                    visit("2026-11-10T14:30:00+01:00", practitionerId, "\"type\":\"FOLLOW_UP\"")
                            .replace("\"type\":\"CONSULTATION\",", ""), true).statusCode()).isEqualTo(201);

            // Newest first, for both roles; another Patient's visit never appears.
            Encounter foreign = new Encounter();
            foreign.setId("syn-enc-foreign-" + suffix);
            foreign.setStatus(Encounter.EncounterStatus.FINISHED);
            foreign.setClass_(new Coding("http://terminology.hl7.org/CodeSystem/v3-ActCode", "AMB", null));
            foreign.setSubject(new Reference("Patient/" + otherPatientId));
            foreign.getPeriod().setStartElement(new org.hl7.fhir.r4.model.DateTimeType("2026-11-11T08:00:00Z"));
            fhir.seed(foreign);
            for (HttpClient clinician : List.of(doctor, staff)) {
                JsonNode visits = list(clinician, patientId);
                assertThat(visits).hasSize(2);
                assertThat(visits.get(0).get("start").asString()).isEqualTo("2026-11-10T14:30:00+01:00");
                assertThat(visits.get(0).get("type").asString()).isEqualTo("Follow-up encounter");
                assertThat(visits.get(1).get("type").asString()).isEqualTo("Consultation");
            }
            assertThat(list(doctor, otherPatientId)).hasSize(1);
        }
        // Nothing about Encounters is stored in PostgreSQL.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_access_requests", Integer.class))
                .isEqualTo(requestsBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name ILIKE '%encounter%'",
                Integer.class)).isZero();
        assertThat(output.getAll()).contains("Encounter created: accountId=").doesNotContain(patientId, patientPnr);
    }

    @Test
    void organizationAndLocationAreOptionalAndMustExist() throws Exception {
        Organization organization = new Organization().setName("Vårdcentral Fiktiv");
        organization.setId("syn-org-" + suffix);
        fhir.seed(organization);
        Location location = new Location().setName("Mottagning 3");
        location.setId("syn-loc-" + suffix);
        fhir.seed(location);
        try (HttpClient doctor = signedIn(linkedDoctor())) {
            HttpResponse<String> created = post(doctor, "/api/patients/" + patientId + "/encounters",
                    visit("2026-11-09T10:00:00Z", practitionerId, "\"organizationId\":\"syn-org-" + suffix
                            + "\",\"locationId\":\"syn-loc-" + suffix + "\""), true);
            assertThat(created.statusCode()).isEqualTo(201);
            Encounter stored = (Encounter) fhir.stored("Encounter", json.readTree(created.body()).get("id").asString());
            assertThat(stored.getServiceProvider().getReference()).isEqualTo("Organization/syn-org-" + suffix);
            assertThat(stored.getLocationFirstRep().getLocation().getReference()).isEqualTo("Location/syn-loc-" + suffix);
            JsonNode listed = list(doctor, patientId).get(0);
            assertThat(listed.get("organization").asString()).isEqualTo("Vårdcentral Fiktiv");
            assertThat(listed.get("location").asString()).isEqualTo("Mottagning 3");

            long postsBefore = posts();
            HttpResponse<String> missing = post(doctor, "/api/patients/" + patientId + "/encounters",
                    visit("2026-11-09T10:00:00Z", practitionerId, "\"organizationId\":\"syn-org-missing\""), true);
            assertThat(missing.statusCode()).isEqualTo(422);
            assertThat(json.readTree(missing.body()).get("code").asString()).isEqualTo("ORGANIZATION_NOT_FOUND");
            assertThat(posts()).isEqualTo(postsBefore);
        }
    }

    @Test
    void invalidVisitsUnknownPatientsAndForeignPractitionersAreRejectedBeforeAnyWrite() throws Exception {
        String foreignPatient = "syn-enc-unregistered-" + suffix;
        Patient unregistered = new Patient();
        unregistered.setId(foreignPatient);
        fhir.seed(unregistered);
        try (HttpClient doctor = signedIn(linkedDoctor())) {
            long postsBefore = posts();
            record Case(String path, String body, int status, String code) {
            }
            for (Case c : List.of(
                    new Case(patientId, visit("2026-11-09T10:00", practitionerId, null), 400, "START_TIME_INVALID"),
                    new Case(patientId, visit("2030-01-01T10:00:00Z", practitionerId, null), 400, "START_TIME_IN_FUTURE"),
                    new Case(patientId, visit("2026-11-09T10:00:00Z", foreignPractitionerId, null), 400,
                            "PRACTITIONER_NOT_AVAILABLE"),
                    new Case(patientId, "{\"startTime\":\"2026-11-09T10:00:00Z\",\"type\":\"CONSULTATION\"}", 400,
                            "PRACTITIONER_REQUIRED"),
                    new Case(patientId, visit("2026-11-09T10:00:00Z", practitionerId, "\"status\":\"ARRIVED\""), 400,
                            "STATUS_INVALID"),
                    new Case(patientId, visit("2026-11-09T10:00:00Z", practitionerId, "\"patientId\":\"" + otherPatientId
                            + "\""), 400, "INVALID_REQUEST"),
                    new Case(foreignPatient, visit("2026-11-09T10:00:00Z", practitionerId, null), 404, null),
                    new Case("bad_id", visit("2026-11-09T10:00:00Z", practitionerId, null), 400, null))) {
                HttpResponse<String> response = post(doctor, "/api/patients/" + c.path() + "/encounters", c.body(), true);
                assertThat(response.statusCode()).as(c.body()).isEqualTo(c.status());
                if (c.code() != null) assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo(c.code());
            }
            assertThat(get(doctor, "/api/patients/" + foreignPatient + "/encounters").statusCode()).isEqualTo(404);
            assertThat(posts()).isEqualTo(postsBefore);
        }
    }

    @Test
    void patientsReadOnlyTheirOwnVisitsAfterApprovedAccessAndNeverCreate() throws Exception {
        String patientEmail = account(Role.PATIENT, patientPnr);
        String doctorEmail = linkedDoctor();
        try (HttpClient patient = signedIn(patientEmail); HttpClient doctor = signedIn(doctorEmail)) {
            // Before access is approved: no record, no visits.
            HttpResponse<String> unlinked = get(patient, "/api/patients/me/encounters");
            assertThat(unlinked.statusCode()).isEqualTo(404);
            assertThat(json.readTree(unlinked.body()).get("code").asString()).isEqualTo("RECORD_NOT_LINKED");

            assertThat(post(doctor, "/api/patients/" + patientId + "/encounters",
                    visit("2026-11-08T11:00:00+01:00", practitionerId, null), true).statusCode()).isEqualTo(201);
            assertThat(post(doctor, "/api/patients/" + otherPatientId + "/encounters",
                    visit("2026-11-08T12:00:00+01:00", practitionerId, null), true).statusCode()).isEqualTo(201);

            // The patient asks for access and a doctor approves.
            assertThat(post(patient, "/api/journal-access/me/request", "", true).statusCode()).isEqualTo(200);
            long requestId = jdbc.queryForObject("SELECT id FROM journal_access_requests WHERE user_id = ?", Long.class,
                    id(patientEmail));
            assertThat(post(doctor, "/api/journal-access-requests/" + requestId + "/approve", "", true).statusCode())
                    .isEqualTo(200);

            HttpResponse<String> own = get(patient, "/api/patients/me/encounters?patientId=" + otherPatientId);
            assertThat(own.statusCode()).isEqualTo(200);
            JsonNode visits = json.readTree(own.body());
            assertThat(visits).hasSize(1);
            assertThat(visits.get(0).get("start").asString()).isEqualTo("2026-11-08T11:00:00+01:00");
            assertThat(visits.get(0).get("practitioners").get(0).asString()).isEqualTo("Dr Ali Khan");

            // Never another Patient's visits, never by ID, never a create.
            assertThat(get(patient, "/api/patients/" + otherPatientId + "/encounters").statusCode()).isEqualTo(403);
            assertThat(get(patient, "/api/patients/" + patientId + "/encounters").statusCode()).isEqualTo(403);
            long postsBefore = posts();
            assertThat(post(patient, "/api/patients/" + patientId + "/encounters",
                    visit("2026-11-08T11:00:00+01:00", practitionerId, null), true).statusCode()).isEqualTo(403);
            assertThat(post(patient, "/api/patients/me/encounters",
                    visit("2026-11-08T11:00:00+01:00", practitionerId, null), true).statusCode()).isEqualTo(403);
            assertThat(get(patient, "/api/practitioners").statusCode()).isEqualTo(403);
            assertThat(posts()).isEqualTo(postsBefore);
            // My Medical Record still works.
            assertThat(get(patient, "/api/patients/me").statusCode()).isEqualTo(200);
        }
    }

    @Test
    void cliniciansChooseOnlyPractitionersLinkedToMediCoreAccounts() throws Exception {
        String doctorEmail = linkedDoctor();
        try (HttpClient doctor = signedIn(doctorEmail);
                HttpClient staff = signedIn(account(Role.STAFF, TestPersonnummer.next()));
                HttpClient anonymous = newClient()) {
            JsonNode forDoctor = json.readTree(get(doctor, "/api/practitioners").body());
            JsonNode own = null;
            for (JsonNode option : forDoctor) if (practitionerId.equals(option.get("id").asString())) own = option;
            assertThat(own).isNotNull();
            assertThat(own.get("name").asString()).isEqualTo("Dr Ali Khan");
            assertThat(own.get("own").asBoolean()).isTrue();
            assertThat(forDoctor.get(0).get("id").asString()).isEqualTo(practitionerId);
            JsonNode forStaff = json.readTree(get(staff, "/api/practitioners").body());
            assertThat(forStaff.toString()).contains(practitionerId).doesNotContain(foreignPractitionerId);
            for (JsonNode option : forStaff) assertThat(option.get("own").asBoolean()).isFalse();

            assertThat(get(anonymous, "/api/practitioners").statusCode()).isEqualTo(401);
            assertThat(get(anonymous, "/api/patients/" + patientId + "/encounters").statusCode()).isEqualTo(401);
            assertThat(post(anonymous, "/api/patients/" + patientId + "/encounters",
                    visit("2026-11-09T10:00:00Z", practitionerId, null), true).statusCode()).isEqualTo(401);
            assertThat(post(doctor, "/api/patients/" + patientId + "/encounters",
                    visit("2026-11-09T10:00:00Z", practitionerId, null), false).statusCode()).isEqualTo(403);
        }
    }
}
