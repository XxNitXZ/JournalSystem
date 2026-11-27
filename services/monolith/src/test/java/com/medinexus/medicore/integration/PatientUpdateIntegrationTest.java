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

import org.hl7.fhir.r4.model.Address;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.StringType;
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
 * US-04.04 end to end: DOCTOR and STAFF update a registered Patient's gender in FHIR (names, personnummer and
 * birth date are immutable), and patient profiles show contact details only from the linked PostgreSQL
 * account. Real HTTP with session cookies and CSRF, PostgreSQL {@code medinexus_test} only and a local FHIR test server (nothing is written
 * to KTH). Fictional data only. Each test deletes exactly the accounts, registry entries and requests it created.
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/medinexus_test")
class PatientUpdateIntegrationTest {

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
    private final List<String> patientIds = new ArrayList<>();
    private final String password = "Synthetic-Passw0rd-" + UUID.randomUUID();
    private String personnummer;
    private String patientId;
    private long patientsAtStart;

    @BeforeEach
    void onlyTheTestDatabaseAndOneRegisteredPatient() {
        assertThat(datasourceUrl).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/medinexus_test");
        personnummer = TestPersonnummer.next();
        patientId = seed("syn-upd-" + UUID.randomUUID().toString().substring(0, 8), personnummer);
        jdbc.update("INSERT INTO registered_patients (fhir_patient_id) VALUES (?)", patientId);
        patientsAtStart = fhir.count("Patient");
    }

    @AfterEach
    void deleteEverythingThisTestCreated() {
        for (String id : patientIds) jdbc.update("DELETE FROM journal_access_requests WHERE fhir_patient_id = ?", id);
        for (Long id : accounts) jdbc.update("DELETE FROM journal_access_requests WHERE user_id = ?", id);
        for (String id : patientIds) jdbc.update("DELETE FROM registered_patients WHERE fhir_patient_id = ?", id);
        for (Long id : accounts) jdbc.update("DELETE FROM users WHERE id = ?", id);
    }

    /** A Patient with more than MediCore writes (telecom, address, extension, a second name). */
    private String seed(String id, String pnr) {
        Patient patient = new Patient();
        patient.setId(id);
        patient.addIdentifier().setSystem(SYSTEM).setValue(pnr);
        patient.addName().setUse(HumanName.NameUse.OFFICIAL).setFamily("Uppdatersson").addGiven("Fiktiv");
        patient.addName().setUse(HumanName.NameUse.NICKNAME).addGiven("Fikke");
        patient.setGender(AdministrativeGender.MALE);
        patient.getBirthDateElement().setValueAsString(pnr.substring(0, 4) + "-" + pnr.substring(4, 6) + "-"
                + pnr.substring(6, 8));
        patient.addTelecom().setSystem(ContactPoint.ContactPointSystem.PHONE).setValue("+46 70 000 00 00");
        patient.addAddress(new Address().setCity("Stockholm"));
        patient.addExtension("urn:example:extension", new StringType("kept"));
        fhir.seed(patient);
        patientIds.add(id);
        return id;
    }

    private String account(Role role) {
        String email = "nit-us0404-" + role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.test";
        accounts.add(registration.register(email, password, role, "Konto", "Namn", TestPersonnummer.next()).id());
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
        assertThat(send(client, "POST", "/api/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}", null, true).statusCode()).isEqualTo(200);
        return client;
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(HttpClient client, String method, String path, String body, String ifMatch,
            boolean withCsrf) throws Exception {
        HttpRequest.Builder builder = request(path).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (withCsrf) {
            JsonNode token = json.readTree(get(client, "/api/auth/csrf").body());
            builder.header(token.get("headerName").asString(), token.get("token").asString());
        }
        if (ifMatch != null) builder.header("If-Match", ifMatch);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> update(HttpClient client, String id, String version, String body) throws Exception {
        return send(client, "PATCH", "/api/patients/" + id, body, version == null ? null : "\"" + version + "\"", true);
    }

    private String version(HttpClient client) throws Exception {
        HttpResponse<String> profile = get(client, "/api/patients/" + patientId);
        assertThat(profile.statusCode()).isEqualTo(200);
        return json.readTree(profile.body()).get("version").asString();
    }

    private static String gender(String gender) {
        return "{\"gender\":\"" + gender + "\"}";
    }

    private Patient stored() {
        return (Patient) fhir.stored("Patient", patientId);
    }

    private long puts() {
        return fhir.requests().stream().filter(line -> line.startsWith("PUT ")).count();
    }

    /** Names, personnummer, birth date, telecom and everything but the gender are as seeded; no Patient created. */
    private void assertImmutableAndUnrelatedDataUnchanged() {
        Patient patient = stored();
        assertThat(patient.getIdentifier()).singleElement().satisfies(identifier -> {
            assertThat(identifier.getSystem()).isEqualTo(SYSTEM);
            assertThat(identifier.getValue()).isEqualTo(personnummer);
        });
        assertThat(patient.getBirthDateElement().getValueAsString().replace("-", "")).isEqualTo(personnummer.substring(0, 8));
        assertThat(patient.getNameFirstRep().getFamily()).isEqualTo("Uppdatersson");
        assertThat(patient.getNameFirstRep().getGiven()).extracting(StringType::getValue).containsExactly("Fiktiv");
        assertThat(patient.getName().get(1).getGiven().getFirst().getValue()).isEqualTo("Fikke");
        assertThat(patient.getTelecom()).singleElement()
                .satisfies(telecom -> assertThat(telecom.getValue()).isEqualTo("+46 70 000 00 00"));
        assertThat(patient.getAddressFirstRep().getCity()).isEqualTo("Stockholm");
        assertThat(patient.getExtensionByUrl("urn:example:extension").getValue().primitiveValue()).isEqualTo("kept");
        assertThat(patient.getIdElement().getIdPart()).isEqualTo(patientId);
        // No Patient was created: still the number there was when this test started.
        assertThat(fhir.count("Patient")).isEqualTo(patientsAtStart);
    }

    @Test
    void doctorsAndStaffChangeOnlyTheGenderInFhir(CapturedOutput output) throws Exception {
        try (HttpClient doctor = signedIn(account(Role.DOCTOR)); HttpClient staff = signedIn(account(Role.STAFF))) {
            assertThat(version(doctor)).isEqualTo("1");

            HttpResponse<String> first = update(doctor, patientId, "1", gender("FEMALE"));
            assertThat(first.statusCode()).isEqualTo(200);
            JsonNode updated = json.readTree(first.body());
            assertThat(updated.get("version").asString()).isEqualTo("2");
            assertThat(updated.get("gender").asString()).isEqualTo("female");
            assertThat(updated.get("names").get(0).get("family").asString()).isEqualTo("Uppdatersson");
            assertThat(updated.get("identifiers").get(0).get("value").asString()).isEqualTo(personnummer);
            assertThat(stored().getGender()).isEqualTo(AdministrativeGender.FEMALE);
            assertThat(fhir.ifMatches()).last().isEqualTo("W/\"1\"");
            assertImmutableAndUnrelatedDataUnchanged();

            HttpResponse<String> second = update(staff, patientId, version(staff), gender("OTHER"));
            assertThat(second.statusCode()).isEqualTo(200);
            assertThat(json.readTree(get(staff, "/api/patients/" + patientId).body()).get("gender").asString())
                    .isEqualTo("other");
            // The search still finds the patient by the unchanged name.
            assertThat(send(doctor, "POST", "/api/patients/search", "{\"query\":\"Uppdatersson\"}", null, true).body())
                    .contains(patientId);
            assertImmutableAndUnrelatedDataUnchanged();
        }
        assertThat(output.getAll()).contains("Patient updated: accountId=");
        assertThat(output.getAll()).doesNotContain(personnummer, patientId);
    }

    @Test
    void namesPersonnummerBirthDateIdAndContactDetailsCannotBeChanged() throws Exception {
        try (HttpClient doctor = signedIn(account(Role.DOCTOR))) {
            long putsBefore = puts();
            for (String field : List.of("\"givenName\":\"Ali\"", "\"familyName\":\"Khan\"",
                    "\"personnummer\":\"200512241971\"", "\"birthDate\":\"1999-01-01\"", "\"id\":\"syn-other\"",
                    "\"fhirPatientId\":\"syn-other\"", "\"email\":\"x@example.test\"", "\"phoneNumber\":\"0701234567\"",
                    "\"identifiers\":[{\"system\":\"" + SYSTEM + "\",\"value\":\"200512241971\"}]")) {
                HttpResponse<String> response = update(doctor, patientId, "1", gender("MALE").replace("}", "," + field + "}"));
                assertThat(response.statusCode()).as(field).isEqualTo(400);
                assertThat(response.body()).isEmpty();
            }
            for (String body : List.of(gender("male"), gender(""), "{}", "{\"gender\":1}")) {
                assertThat(update(doctor, patientId, "1", body).statusCode()).as(body).isEqualTo(400);
            }
            assertThat(puts()).isEqualTo(putsBefore);
            assertThat(stored().getGender()).isEqualTo(AdministrativeGender.MALE);
            assertImmutableAndUnrelatedDataUnchanged();
        }
    }

    @Test
    void patientsAnonymousUsersAndRequestsWithoutCsrfCannotUpdate() throws Exception {
        String patientEmail = account(Role.PATIENT);
        // Even the PATIENT linked to this very record cannot edit it.
        identities.assign(jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, patientEmail), patientId);
        long putsBefore = puts();
        try (HttpClient patient = signedIn(patientEmail); HttpClient anonymous = newClient();
                HttpClient doctor = signedIn(account(Role.DOCTOR))) {
            assertThat(update(patient, patientId, "1", gender("FEMALE")).statusCode()).isEqualTo(403);
            assertThat(send(patient, "PATCH", "/api/patients/me", gender("FEMALE"), "\"1\"", true).statusCode())
                    .isEqualTo(403);
            assertThat(update(anonymous, patientId, "1", gender("FEMALE")).statusCode()).isEqualTo(401);
            assertThat(send(doctor, "PATCH", "/api/patients/" + patientId, gender("FEMALE"), "\"1\"", false)
                    .statusCode()).isEqualTo(403);
        }
        assertThat(puts()).isEqualTo(putsBefore);
        assertThat(stored().getGender()).isEqualTo(AdministrativeGender.MALE);
    }

    @Test
    void concurrentEditsAreDetectedAndNeverOverwritten() throws Exception {
        try (HttpClient first = signedIn(account(Role.DOCTOR)); HttpClient second = signedIn(account(Role.STAFF))) {
            String seenByFirst = version(first);
            String seenBySecond = version(second);
            assertThat(update(first, patientId, seenByFirst, gender("FEMALE")).statusCode()).isEqualTo(200);

            HttpResponse<String> late = update(second, patientId, seenBySecond, gender("OTHER"));
            assertThat(late.statusCode()).isEqualTo(409);
            assertThat(json.readTree(late.body()).get("code").asString()).isEqualTo("VERSION_CONFLICT");
            assertThat(stored().getGender()).isEqualTo(AdministrativeGender.FEMALE);

            HttpResponse<String> blind = update(second, patientId, null, gender("OTHER"));
            assertThat(blind.statusCode()).isEqualTo(428);
            assertThat(json.readTree(blind.body()).get("code").asString()).isEqualTo("VERSION_REQUIRED");
            assertThat(stored().getGender()).isEqualTo(AdministrativeGender.FEMALE);
        }
    }

    @Test
    void unknownForeignAndUnavailablePatientsAreErrorsNotSuccesses() throws Exception {
        String foreign = seed("syn-foreign-" + UUID.randomUUID().toString().substring(0, 8), TestPersonnummer.next());
        try (HttpClient doctor = signedIn(account(Role.DOCTOR))) {
            long putsBefore = puts();
            assertThat(update(doctor, foreign, "1", gender("FEMALE")).statusCode()).isEqualTo(404);
            assertThat(update(doctor, "syn-unknown-404", "1", gender("FEMALE")).statusCode()).isEqualTo(404);
            assertThat(update(doctor, "bad_id", "1", gender("FEMALE")).statusCode()).isEqualTo(400);
            assertThat(puts()).isEqualTo(putsBefore);
            assertThat(((Patient) fhir.stored("Patient", foreign)).getGender()).isEqualTo(AdministrativeGender.MALE);

            String gone = seed("syn-gone-" + UUID.randomUUID().toString().substring(0, 8), TestPersonnummer.next());
            jdbc.update("INSERT INTO registered_patients (fhir_patient_id) VALUES (?)", gone);
            fhir.remove("Patient", gone);
            assertThat(update(doctor, gone, "1", gender("FEMALE")).statusCode()).isEqualTo(404);

            fhir.updatesUnavailable(true);
            try {
                assertThat(update(doctor, patientId, "1", gender("FEMALE")).statusCode()).isEqualTo(503);
            } finally {
                fhir.updatesUnavailable(false);
            }
            assertThat(stored().getGender()).isEqualTo(AdministrativeGender.MALE);
            assertThat(stored().getMeta().getVersionId()).isEqualTo("1");
        }
    }

    /** A PATIENT account for this record with a phone number, linked through an approved journal access request. */
    private long linkedPatientAccount(String email, String phoneNumber, HttpClient doctor) throws Exception {
        long accountId = registration.register(email, password, Role.PATIENT, "Kontonamn", "Behalls", personnummer,
                phoneNumber).id();
        accounts.add(accountId);
        try (HttpClient patient = signedIn(email)) {
            assertThat(send(patient, "POST", "/api/journal-access/me/request", "", null, true).statusCode()).isEqualTo(200);
        }
        long requestId = jdbc.queryForObject("SELECT id FROM journal_access_requests WHERE user_id = ?", Long.class,
                accountId);
        assertThat(send(doctor, "POST", "/api/journal-access-requests/" + requestId + "/approve", "", null, true)
                .statusCode()).isEqualTo(200);
        return accountId;
    }

    @Test
    void anUpdateKeepsTheAccountLinkAndJournalAccessAndCreatesNoRequest() throws Exception {
        String patientEmail = "nit-us0404-linked-" + UUID.randomUUID() + "@example.test";
        try (HttpClient doctor = signedIn(account(Role.DOCTOR))) {
            long accountId = linkedPatientAccount(patientEmail, null, doctor);
            long requestId = jdbc.queryForObject("SELECT id FROM journal_access_requests WHERE user_id = ?", Long.class,
                    accountId);

            assertThat(update(doctor, patientId, version(doctor), gender("OTHER")).statusCode()).isEqualTo(200);

            assertThat(jdbc.queryForObject("SELECT fhir_patient_id FROM users WHERE id = ?", String.class, accountId))
                    .isEqualTo(patientId);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_access_requests WHERE user_id = ?",
                    Integer.class, accountId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM journal_access_requests WHERE id = ?", String.class,
                    requestId)).isEqualTo("APPROVED");
            assertThat(jdbc.queryForObject("SELECT given_name || ' ' || family_name FROM users WHERE id = ?",
                    String.class, accountId)).isEqualTo("Kontonamn Behalls");
            try (HttpClient patient = signedIn(patientEmail)) {
                assertThat(json.readTree(get(patient, "/api/journal-access/me").body()).get("status").asString())
                        .isEqualTo("APPROVED");
                HttpResponse<String> own = get(patient, "/api/patients/me");
                assertThat(own.statusCode()).isEqualTo(200);
                assertThat(json.readTree(own.body()).get("gender").asString()).isEqualTo("other");
                assertThat(json.readTree(own.body()).get("names").get(0).get("family").asString())
                        .isEqualTo("Uppdatersson");
            }
        }
    }

    // ---------- contact details: PostgreSQL only ----------

    @Test
    void doctorsAndStaffSeeContactDetailsOnlyFromTheLinkedAccount() throws Exception {
        String patientEmail = "nit-us0404-contact-" + UUID.randomUUID() + "@example.test";
        long putsBefore = puts();
        try (HttpClient doctor = signedIn(account(Role.DOCTOR)); HttpClient staff = signedIn(account(Role.STAFF))) {
            // Without a linked account: nothing, although the FHIR Patient has a telecom entry.
            JsonNode unlinked = json.readTree(get(doctor, "/api/patients/" + patientId).body());
            assertThat(unlinked.get("email").isNull()).isTrue();
            assertThat(unlinked.get("phoneNumber").isNull()).isTrue();
            assertThat(unlinked.toString()).doesNotContain("+46 70 000 00 00");

            linkedPatientAccount(patientEmail, "070-111 22 33", doctor);
            for (HttpClient clinician : List.of(doctor, staff)) {
                JsonNode linked = json.readTree(get(clinician, "/api/patients/" + patientId).body());
                assertThat(linked.get("email").asString()).isEqualTo(patientEmail);
                assertThat(linked.get("phoneNumber").asString()).isEqualTo("0701112233");
            }

            // The account holder changes the contact details; the next profile read shows them.
            String newEmail = "nit-us0404-contact2-" + UUID.randomUUID() + "@example.test";
            try (HttpClient patient = signedIn(patientEmail)) {
                assertThat(send(patient, "PATCH", "/api/auth/profile", "{\"phoneNumber\":\"+46 8 123 456\",\"email\":\""
                        + newEmail + "\",\"currentPassword\":\"" + password + "\"}", null, true).statusCode())
                        .isEqualTo(200);
                JsonNode own = json.readTree(get(patient, "/api/patients/me").body());
                assertThat(own.get("email").asString()).isEqualTo(newEmail);
                assertThat(own.get("phoneNumber").asString()).isEqualTo("+468123456");
            }
            JsonNode updated = json.readTree(get(staff, "/api/patients/" + patientId).body());
            assertThat(updated.get("email").asString()).isEqualTo(newEmail);
            assertThat(updated.get("phoneNumber").asString()).isEqualTo("+468123456");

            // Contact details are never written to FHIR, and another Patient never shows this account's details.
            assertThat(stored().getTelecom()).singleElement()
                    .satisfies(telecom -> assertThat(telecom.getValue()).isEqualTo("+46 70 000 00 00"));
            assertThat(puts()).isEqualTo(putsBefore);
            String otherPatient = seed("syn-other-" + UUID.randomUUID().toString().substring(0, 8), TestPersonnummer.next());
            jdbc.update("INSERT INTO registered_patients (fhir_patient_id) VALUES (?)", otherPatient);
            JsonNode other = json.readTree(get(doctor, "/api/patients/" + otherPatient).body());
            assertThat(other.get("email").isNull()).isTrue();
            assertThat(other.get("phoneNumber").isNull()).isTrue();
        }
    }
}
