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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
import com.medinexus.medicore.auth.service.AccountFhirIdentityService;
import com.medinexus.medicore.auth.service.RegistrationService;
import com.medinexus.medicore.fhir.support.LocalFhirServer;
import com.medinexus.medicore.support.TestPersonnummer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * US-04.03 end to end: account personnummer, requests created only when the PATIENT asks (matched to a registered
 * record at once or later), DOCTOR review, atomic approval and the PATIENT's own record. Real HTTP with session
 * cookies and CSRF, PostgreSQL {@code medinexus_test} only and a
 * local FHIR test server; no external FHIR server is contacted and nothing is written to KTH.
 *
 * <p>All identities are fictional (19th-century personnummer from {@link TestPersonnummer}). Each test deletes
 * exactly the requests, registry entries and accounts it created.
 *
 * <p>{@code @DirtiesContext}: the context points at a local FHIR server that is stopped after the class, so it is
 * closed instead of cached; its connection pool would otherwise stay open for the rest of the test run.
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/medinexus_test")
class JournalAccessIntegrationTest {

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
    private final List<String> usedPersonnummer = new ArrayList<>();
    private final String password = "Synthetic-Passw0rd-" + UUID.randomUUID();

    @BeforeEach
    void onlyTheTestDatabase() {
        assertThat(datasourceUrl).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/medinexus_test");
    }

    @AfterEach
    void deleteEverythingThisTestCreated() {
        // Requests first: registered_patients is referenced with ON DELETE RESTRICT.
        for (String id : patientIds) jdbc.update("DELETE FROM journal_access_requests WHERE fhir_patient_id = ?", id);
        for (Long id : accounts) jdbc.update("DELETE FROM journal_access_requests WHERE user_id = ?", id);
        for (String id : patientIds) jdbc.update("DELETE FROM registered_patients WHERE fhir_patient_id = ?", id);
        for (Long id : accounts) jdbc.update("DELETE FROM registered_patients WHERE registered_by = ?", id);
        for (Long id : accounts) jdbc.update("DELETE FROM users WHERE id = ?", id);
    }

    // ---------- helpers ----------

    private String personnummer() {
        String value = TestPersonnummer.next();
        usedPersonnummer.add(value);
        return value;
    }

    private static String email(String prefix) {
        return "nit-us0403-" + prefix + "-" + UUID.randomUUID() + "@example.test";
    }

    /** An account created through the service (no HTTP), returning its e-mail. */
    private String account(Role role, String personnummer) {
        String email = email(role.name().toLowerCase());
        accounts.add(registration.register(email, password, role, "Fiktiv", "Testperson", personnummer).id());
        return email;
    }

    private long accountId(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private String linkedPatient(String email) {
        return jdbc.queryForObject("SELECT fhir_patient_id FROM users WHERE email = ?", String.class, email);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    private HttpClient signedIn(String email) throws Exception {
        HttpClient client = newClient();
        assertThat(post(client, "/api/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}").statusCode()).isEqualTo(200);
        return client;
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(HttpClient client, String path, String body) throws Exception {
        JsonNode token = json.readTree(get(client, "/api/auth/csrf").body());
        return client.send(request(path).header(token.get("headerName").asString(), token.get("token").asString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWithoutCsrf(HttpClient client, String path) throws Exception {
        return client.send(request(path).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Registers a Patient through MediCore (POST /api/patients) without a birth date; returns its FHIR ID. */
    private String registerPatient(HttpClient clinician, String personnummer, String family) throws Exception {
        HttpResponse<String> created = post(clinician, "/api/patients", """
                {"personnummer":"%s","family":"%s","given":["Fiktiv"],"gender":"OTHER"}
                """.formatted(personnummer, family));
        assertThat(created.statusCode()).isEqualTo(201);
        String id = json.readTree(created.body()).get("id").asString();
        patientIds.add(id);
        return id;
    }

    private String status(HttpClient patient) throws Exception {
        HttpResponse<String> response = get(patient, "/api/journal-access/me");
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).get("status").asString();
    }

    private List<Long> requestIds(String email, String status) {
        return jdbc.queryForList("SELECT id FROM journal_access_requests WHERE user_id = ? AND status = ? ORDER BY id",
                Long.class, accountId(email), status);
    }

    private int requestCount(String email) {
        return jdbc.queryForObject("SELECT count(*) FROM journal_access_requests WHERE user_id = ?", Integer.class,
                accountId(email));
    }

    private void assertNothingSensitiveLogged(CapturedOutput output) {
        for (String value : usedPersonnummer) {
            assertThat(output.getAll().contains(value)).as("log contains a personnummer").isFalse();
            assertThat(output.getAll().contains(value.substring(2))).as("log contains a personnummer").isFalse();
        }
        for (String id : patientIds) assertThat(output.getAll().contains(id)).as("log contains a FHIR ID").isFalse();
        assertThat(output.getAll().contains(password)).isFalse();
    }

    // ---------- account registration with personnummer ----------

    @Test
    void everyRoleRegistersWithAUniqueValidPersonnummer(CapturedOutput output) throws Exception {
        try (HttpClient client = newClient()) {
            for (Role role : Role.values()) {
                if (role.name().equals("ADMIN")) continue;
                String pnr = personnummer();
                String email = email("reg-" + role.name().toLowerCase());
                // The hyphenated form is accepted and stored as 12 digits.
                String hyphenated = pnr.substring(0, 8) + "-" + pnr.substring(8);
                HttpResponse<String> registered = post(client, "/api/auth/register", body(email, role, hyphenated));
                assertThat(registered.statusCode()).isEqualTo(201);
                accounts.add(accountId(email));
                assertThat(registered.body()).doesNotContain(pnr.substring(2, 8));
                assertThat(jdbc.queryForObject("SELECT personnummer FROM users WHERE email = ?", String.class, email))
                        .isEqualTo(pnr);
            }
            String taken = usedPersonnummer.getFirst();
            HttpResponse<String> duplicate = post(client, "/api/auth/register", body(email("dup"), Role.PATIENT, taken));
            assertThat(duplicate.statusCode()).isEqualTo(409);
            assertThat(json.readTree(duplicate.body()).get("code").asString()).isEqualTo("PERSONNUMMER_TAKEN");
            assertThat(duplicate.body()).doesNotContain(taken);

            // Impossible dates (30 February, month 13), future, too short or long, letters, empty.
            for (String invalid : List.of("180002301234", "180013011234", "29991231-0000", "18000230123",
                    "1800010112345", "ABCDEFGHIJKL", "abc", "")) {
                assertThat(post(client, "/api/auth/register", body(email("bad"), Role.PATIENT, invalid)).statusCode())
                        .as("status for an invalid personnummer").isEqualTo(400);
            }
            assertThat(post(client, "/api/auth/register", """
                    {"email":"%s","password":"%s","role":"PATIENT","givenName":"Fiktiv","familyName":"Testperson"}
                    """.formatted(email("missing"), password)).statusCode()).isEqualTo(400);
        }
        assertNothingSensitiveLogged(output);
    }

    private String body(String email, Role role, String personnummer) {
        return """
                {"email":"%s","password":"%s","role":"%s","givenName":"Fiktiv","familyName":"Testperson",
                 "personnummer":"%s"}
                """.formatted(email, password, role, personnummer);
    }

    @Test
    void anAccountCreatedBeforePersonnummerWereStoredStillSignsInButCannotAskForAccess() throws Exception {
        String email = account(Role.PATIENT, personnummer());
        jdbc.update("UPDATE users SET personnummer = NULL WHERE email = ?", email);
        try (HttpClient patient = signedIn(email)) {
            assertThat(status(patient)).isEqualTo("NONE");
            assertThat(get(patient, "/api/patients/me").statusCode()).isEqualTo(404);
            assertConflict(post(patient, "/api/journal-access/me/request", ""), "PERSONNUMMER_MISSING");
        }
        assertThat(requestCount(email)).isZero();
    }

    /** The PATIENT clicks "Request Journal Access". */
    private HttpResponse<String> askForAccess(HttpClient patient) throws Exception {
        return post(patient, "/api/journal-access/me/request", "");
    }

    private String askForAccessStatus(HttpClient patient) throws Exception {
        HttpResponse<String> response = askForAccess(patient);
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).get("status").asString();
    }

    private long pendingCount(HttpClient doctor) throws Exception {
        return json.readTree(get(doctor, "/api/journal-access-requests/pending/count").body()).get("pending").asLong();
    }

    // ---------- Nothing is created automatically ----------

    @Test
    void registrationsDashboardsAndSearchesNeverCreateARequest() throws Exception {
        String pnr = personnummer();
        String clinicianPnr = personnummer();
        try (HttpClient staff = signedIn(account(Role.STAFF, personnummer()));
                HttpClient doctor = signedIn(account(Role.DOCTOR, clinicianPnr)); HttpClient patient = newClient()) {
            // Patient record first, then the account (HTTP registration with automatic sign-in).
            registerPatient(staff, pnr, "Automatsson");
            String email = email("no-auto");
            assertThat(post(patient, "/api/auth/register", body(email, Role.PATIENT, pnr)).statusCode()).isEqualTo(201);
            accounts.add(accountId(email));
            assertThat(requestCount(email)).isZero();

            // The dashboard is loaded repeatedly and a clinician searches for the patient: still nothing.
            for (int i = 0; i < 3; i++) assertThat(status(patient)).isEqualTo("NONE");
            assertThat(post(doctor, "/api/patients/search", "{\"query\":\"" + pnr + "\"}").statusCode()).isEqualTo(200);
            assertThat(get(doctor, "/api/journal-access-requests").body()).doesNotContain(email);
            assertThat(requestCount(email)).isZero();
            assertThat(linkedPatient(email)).isNull();

            // Account first, then the record: registering the Patient creates nothing either.
            String laterPnr = personnummer();
            String laterEmail = account(Role.PATIENT, laterPnr);
            registerPatient(staff, laterPnr, "Senaresson");
            assertThat(requestCount(laterEmail)).isZero();

            // A clinician's own personnummer registered as a Patient: no request for the clinician.
            registerPatient(staff, clinicianPnr, "Klinisk");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_access_requests r JOIN users u"
                    + " ON u.id = r.user_id WHERE u.personnummer = ?", Integer.class, clinicianPnr)).isZero();
        }
    }

    // ---------- Scenario A: the record exists when the patient asks ----------

    @Test
    void aPatientWhoseRecordExistsAsksForAccessAndADoctorApproves(CapturedOutput output) throws Exception {
        String pnr = personnummer();
        // Matching and approval work with a wrong Luhn check digit: the checksum is never consulted.
        assertThat(TestPersonnummer.hasCorrectCheckDigit(pnr)).isFalse();
        String patientEmail = account(Role.PATIENT, pnr);
        String doctorEmail = account(Role.DOCTOR, personnummer());
        try (HttpClient patient = signedIn(patientEmail); HttpClient staff = signedIn(account(Role.STAFF, personnummer()));
                HttpClient doctor = signedIn(doctorEmail)) {
            String fhirId = registerPatient(staff, pnr, "Matchsson");
            assertThat(status(patient)).isEqualTo("NONE");
            assertThat(json.readTree(get(patient, "/api/patients/me").body()).get("code").asString())
                    .isEqualTo("RECORD_NOT_LINKED");
            long pendingBefore = pendingCount(doctor);

            // The explicit request finds the registered record: PENDING, but not linked.
            assertThat(askForAccessStatus(patient)).isEqualTo("PENDING");
            // Double clicks return the same request.
            assertThat(askForAccessStatus(patient)).isEqualTo("PENDING");
            assertThat(requestCount(patientEmail)).isEqualTo(1);
            assertThat(linkedPatient(patientEmail)).isNull();
            assertThat(status(patient)).isEqualTo("PENDING");
            assertThat(get(patient, "/api/patients/me").statusCode()).isEqualTo(404);
            assertThat(pendingCount(doctor)).isEqualTo(pendingBefore + 1);
            assertThat(json.readTree(get(doctor, "/api/patients/" + fhirId).body()).get("email").isNull()).isTrue();

            long requestId = requestIds(patientEmail, "PENDING").getFirst();
            assertThat(jdbc.queryForObject("SELECT fhir_patient_id FROM journal_access_requests WHERE id = ?",
                    String.class, requestId)).isEqualTo(fhirId);
            HttpResponse<String> list = get(doctor, "/api/journal-access-requests");
            assertThat(list.statusCode()).isEqualTo(200);
            assertThat(list.body()).doesNotContain(fhirId);
            JsonNode row = null;
            for (JsonNode candidate : json.readTree(list.body())) {
                if (candidate.get("id").asLong() == requestId) row = candidate;
            }
            assertThat(row).isNotNull();
            assertThat(row.get("personnummer").asString()).isEqualTo(pnr);
            assertThat(row.get("email").asString()).isEqualTo(patientEmail);
            assertThat(row.get("status").asString()).isEqualTo("PENDING");

            JsonNode detail = json.readTree(get(doctor, "/api/journal-access-requests/" + requestId).body());
            assertThat(detail.get("recordStatus").asString()).isEqualTo("AVAILABLE");
            assertThat(detail.get("personnummerMatches").asBoolean()).isTrue();
            assertThat(detail.get("record").get("familyName").asString()).isEqualTo("Matchsson");
            assertThat(detail.toString()).doesNotContain(fhirId);

            HttpResponse<String> approved = post(doctor, "/api/journal-access-requests/" + requestId + "/approve", "");
            assertThat(approved.statusCode()).isEqualTo(200);
            assertThat(json.readTree(approved.body()).get("status").asString()).isEqualTo("APPROVED");
            assertThat(linkedPatient(patientEmail)).isEqualTo(fhirId);
            assertThat(jdbc.queryForObject("SELECT reviewed_by FROM journal_access_requests WHERE id = ?", Long.class,
                    requestId)).isEqualTo(accountId(doctorEmail));
            assertThat(pendingCount(doctor)).isEqualTo(pendingBefore);

            assertThat(status(patient)).isEqualTo("APPROVED");
            // An approved account keeps its access; asking again changes nothing.
            assertThat(askForAccessStatus(patient)).isEqualTo("APPROVED");
            assertThat(requestCount(patientEmail)).isEqualTo(1);
            HttpResponse<String> own = get(patient, "/api/patients/me");
            assertThat(own.statusCode()).isEqualTo(200);
            JsonNode record = json.readTree(own.body());
            assertThat(record.get("email").asString()).isEqualTo(patientEmail);
            assertThat(record.get("identifiers").get(0).get("value").asString()).isEqualTo(pnr);
            assertThat(record.get("birthDate").asString().replace("-", "")).isEqualTo(pnr.substring(0, 8));
            assertThat(get(patient, "/api/patients/" + fhirId).statusCode()).isEqualTo(403);
            // DOCTOR/STAFF now see the linked account's e-mail on the profile.
            assertThat(json.readTree(get(staff, "/api/patients/" + fhirId).body()).get("email").asString())
                    .isEqualTo(patientEmail);

            assertConflict(post(doctor, "/api/journal-access-requests/" + requestId + "/approve", ""),
                    "REQUEST_NOT_PENDING");
        }
        assertThat(output.getAll()).contains("Journal access requested: requestId=")
                .contains("Journal access request approved: requestId=");
        assertNothingSensitiveLogged(output);
    }

    // ---------- Scenario B: the record is registered after the patient asked ----------

    @Test
    void aRequestWaitsForTheRecordAndIsMatchedWhenItIsRegistered(CapturedOutput output) throws Exception {
        String pnr = personnummer();
        assertThat(TestPersonnummer.hasCorrectCheckDigit(pnr)).isFalse();
        try (HttpClient staff = signedIn(account(Role.STAFF, personnummer()));
                HttpClient doctor = signedIn(account(Role.DOCTOR, personnummer())); HttpClient patient = newClient()) {
            String email = email("waiting");
            assertThat(post(patient, "/api/auth/register", body(email, Role.PATIENT, pnr)).statusCode()).isEqualTo(201);
            accounts.add(accountId(email));
            long pendingBefore = pendingCount(doctor);

            assertThat(askForAccessStatus(patient)).isEqualTo("AWAITING_RECORD");
            assertThat(askForAccessStatus(patient)).isEqualTo("AWAITING_RECORD");
            assertThat(status(patient)).isEqualTo("AWAITING_RECORD");
            assertThat(requestCount(email)).isEqualTo(1);
            long requestId = requestIds(email, "AWAITING_RECORD").getFirst();
            assertThat(jdbc.queryForObject("SELECT fhir_patient_id FROM journal_access_requests WHERE id = ?",
                    String.class, requestId)).isNull();

            // Doctors see it as waiting, but cannot decide it and it is not counted as actionable.
            assertThat(pendingCount(doctor)).isEqualTo(pendingBefore);
            JsonNode detail = json.readTree(get(doctor, "/api/journal-access-requests/" + requestId).body());
            assertThat(detail.get("status").asString()).isEqualTo("AWAITING_RECORD");
            assertThat(detail.get("recordStatus").asString()).isEqualTo("NOT_REGISTERED");
            assertThat(detail.get("record").isNull()).isTrue();
            assertConflict(post(doctor, "/api/journal-access-requests/" + requestId + "/approve", ""), "AWAITING_RECORD");
            assertConflict(post(doctor, "/api/journal-access-requests/" + requestId + "/reject", ""), "AWAITING_RECORD");
            assertThat(linkedPatient(email)).isNull();

            // A clinician registers the record: the waiting request is matched, without another click.
            String fhirId = registerPatient(staff, pnr, "Vantesson");
            assertThat(requestIds(email, "PENDING")).containsExactly(requestId);
            assertThat(jdbc.queryForObject("SELECT fhir_patient_id FROM journal_access_requests WHERE id = ?",
                    String.class, requestId)).isEqualTo(fhirId);
            assertThat(status(patient)).isEqualTo("PENDING");
            assertThat(pendingCount(doctor)).isEqualTo(pendingBefore + 1);
            assertThat(linkedPatient(email)).isNull();

            // Rejection: no access, and the request is never created again automatically or by asking again.
            assertThat(post(doctor, "/api/journal-access-requests/" + requestId + "/reject", "").statusCode())
                    .isEqualTo(200);
            assertThat(status(patient)).isEqualTo("REJECTED");
            assertThat(linkedPatient(email)).isNull();
            assertThat(get(patient, "/api/patients/me").statusCode()).isEqualTo(404);
            assertConflict(askForAccess(patient), "REQUEST_REJECTED");
            assertThat(status(patient)).isEqualTo("REJECTED");
            assertThat(requestCount(email)).isEqualTo(1);

            // A doctor reconsiders and approves.
            HttpResponse<String> reopened = post(doctor, "/api/journal-access-requests/" + requestId + "/reopen", "");
            assertThat(reopened.statusCode()).isEqualTo(200);
            long newId = json.readTree(reopened.body()).get("id").asLong();
            assertThat(status(patient)).isEqualTo("PENDING");
            assertThat(post(doctor, "/api/journal-access-requests/" + newId + "/approve", "").statusCode()).isEqualTo(200);
            assertThat(status(patient)).isEqualTo("APPROVED");
            assertThat(linkedPatient(email)).isEqualTo(fhirId);
            assertThat(requestIds(email, "REJECTED")).containsExactly(requestId);
            assertThat(requestIds(email, "APPROVED")).containsExactly(newId);
        }
        assertThat(output.getAll()).contains("Journal access request matched to a registered patient record: requestId=");
        assertNothingSensitiveLogged(output);
    }

    @Test
    void aFhirOutageKeepsTheExplicitRequestWhichIsMatchedLater() throws Exception {
        String pnr = personnummer();
        String email = account(Role.PATIENT, pnr);
        try (HttpClient staff = signedIn(account(Role.STAFF, personnummer())); HttpClient patient = signedIn(email)) {
            String fhirId = registerPatient(staff, pnr, "Avbrottsson");
            fhir.searchesUnavailable(true);
            try {
                // The record exists, but cannot be searched right now: the request is kept, waiting.
                assertThat(askForAccessStatus(patient)).isEqualTo("AWAITING_RECORD");
                assertThat(status(patient)).isEqualTo("AWAITING_RECORD");
            } finally {
                fhir.searchesUnavailable(false);
            }
            // Back up: the next dashboard visit matches it.
            assertThat(status(patient)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT fhir_patient_id FROM journal_access_requests WHERE user_id = ?",
                    String.class, accountId(email))).isEqualTo(fhirId);
            assertThat(requestCount(email)).isEqualTo(1);
        }
    }

    @Test
    void foreignAndNameOnlyMatchesNeverAttachAWaitingRequest() throws Exception {
        String pnr = personnummer();
        // On the (shared) FHIR server with the right personnummer, but never registered through MediCore.
        Patient foreign = new Patient();
        String foreignId = "syn-foreign-" + UUID.randomUUID().toString().substring(0, 8);
        foreign.setId(foreignId);
        foreign.addIdentifier().setSystem(SYSTEM).setValue(pnr);
        foreign.addName().setUse(HumanName.NameUse.OFFICIAL).setFamily("Testperson").addGiven("Fiktiv");
        fhir.seed(foreign);

        String email = account(Role.PATIENT, pnr);
        try (HttpClient patient = signedIn(email); HttpClient staff = signedIn(account(Role.STAFF, personnummer()))) {
            assertThat(askForAccessStatus(patient)).isEqualTo("AWAITING_RECORD");
            // Same name as the account, different personnummer: still waiting (never matched by name).
            registerPatient(staff, personnummer(), "Testperson");
            assertThat(status(patient)).isEqualTo("AWAITING_RECORD");
        }
        assertThat(fhir.searchForms()).allSatisfy(form -> assertThat(String.join(",", form.getOrDefault("_id",
                List.of()))).doesNotContain(foreignId));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_access_requests WHERE fhir_patient_id = ?",
                Integer.class, foreignId)).isZero();
    }

    @Test
    void concurrentClicksCreateExactlyOneRequest() throws Exception {
        String email = account(Role.PATIENT, personnummer());
        try (HttpClient patient = signedIn(email); ExecutorService pool = Executors.newFixedThreadPool(5)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                JsonNode token = json.readTree(get(patient, "/api/auth/csrf").body());
                HttpRequest ask = request("/api/journal-access/me/request")
                        .header(token.get("headerName").asString(), token.get("token").asString())
                        .POST(HttpRequest.BodyPublishers.noBody()).build();
                results.add(pool.submit(() -> {
                    start.await();
                    return patient.send(ask, HttpResponse.BodyHandlers.ofString()).statusCode();
                }));
            }
            start.countDown();
            for (Future<Integer> result : results) assertThat(result.get()).isEqualTo(200);
        }
        assertThat(requestCount(email)).isEqualTo(1);
        assertThat(requestIds(email, "AWAITING_RECORD")).hasSize(1);
    }

    // ---------- approval conflicts ----------

    @Test
    void approvalFailsSafelyWhenTheRecordChangedOrIsLinkedElsewhere() throws Exception {
        try (HttpClient staff = signedIn(account(Role.STAFF, personnummer()));
                HttpClient doctor = signedIn(account(Role.DOCTOR, personnummer()))) {
            // 1. The FHIR Patient's personnummer no longer matches the account.
            String pnr = personnummer();
            String mismatchEmail = account(Role.PATIENT, pnr);
            String fhirId = registerPatient(staff, pnr, "Andrad");
            long requestId = requestOf(mismatchEmail);
            Patient changed = (Patient) fhir.stored("Patient", fhirId);
            changed.getIdentifierFirstRep().setValue(personnummer());
            fhir.seed(changed);
            assertConflict(post(doctor, "/api/journal-access-requests/" + requestId + "/approve", ""),
                    "PERSONNUMMER_MISMATCH");
            assertThat(json.readTree(get(doctor, "/api/journal-access-requests/" + requestId).body())
                    .get("personnummerMatches").asBoolean()).isFalse();
            assertUnchanged(mismatchEmail, requestId);

            // 2. The FHIR Patient was deleted on the server.
            String gonePnr = personnummer();
            String goneEmail = account(Role.PATIENT, gonePnr);
            String goneId = registerPatient(staff, gonePnr, "Borta");
            long goneRequest = requestOf(goneEmail);
            fhir.remove("Patient", goneId);
            assertThat(json.readTree(get(doctor, "/api/journal-access-requests/" + goneRequest).body())
                    .get("recordStatus").asString()).isEqualTo("NOT_FOUND");
            assertConflict(post(doctor, "/api/journal-access-requests/" + goneRequest + "/approve", ""),
                    "FHIR_PATIENT_MISSING");
            assertUnchanged(goneEmail, goneRequest);

            // 3. The FHIR Patient was linked to another account by the operator in the meantime.
            String takenPnr = personnummer();
            String waitingEmail = account(Role.PATIENT, takenPnr);
            String takenId = registerPatient(staff, takenPnr, "Upptagen");
            long takenRequest = requestOf(waitingEmail);
            identities.assign(accountId(account(Role.PATIENT, personnummer())), takenId);
            assertConflict(post(doctor, "/api/journal-access-requests/" + takenRequest + "/approve", ""),
                    "LINK_CONFLICT");
            assertUnchanged(waitingEmail, takenRequest);

            // 4. Asking for a record that already belongs to another account is refused and creates nothing.
            String ownedPnr = personnummer();
            String lateEmail = account(Role.PATIENT, ownedPnr);
            String ownedId = registerPatient(staff, ownedPnr, "Agd");
            identities.assign(accountId(account(Role.PATIENT, personnummer())), ownedId);
            try (HttpClient late = signedIn(lateEmail)) {
                assertConflict(askForAccess(late), "LINK_CONFLICT");
            }
            assertThat(requestCount(lateEmail)).isZero();

            // Unknown and malformed request IDs.
            assertThat(post(doctor, "/api/journal-access-requests/999999999/approve", "").statusCode()).isEqualTo(404);
            assertThat(get(doctor, "/api/journal-access-requests/999999999").statusCode()).isEqualTo(404);
            assertThat(get(doctor, "/api/journal-access-requests/abc").statusCode()).isEqualTo(400);
        }
    }

    /** The patient asks for access (their record is registered): the PENDING request's ID. */
    private long requestOf(String email) throws Exception {
        try (HttpClient patient = signedIn(email)) {
            assertThat(askForAccessStatus(patient)).isEqualTo("PENDING");
        }
        return requestIds(email, "PENDING").getFirst();
    }

    private void assertConflict(HttpResponse<String> response, String code) {
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo(code);
    }

    /** A failed approval changes nothing: still PENDING, unreviewed and the account unlinked. */
    private void assertUnchanged(String email, long requestId) {
        assertThat(jdbc.queryForObject("SELECT status FROM journal_access_requests WHERE id = ?", String.class,
                requestId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT reviewed_by IS NULL AND reviewed_at IS NULL FROM journal_access_requests"
                + " WHERE id = ?", Boolean.class, requestId)).isTrue();
        assertThat(linkedPatient(email)).isNull();
    }

    @Test
    void concurrentApprovalsLinkTheAccountExactlyOnce() throws Exception {
        String pnr = personnummer();
        String patientEmail = account(Role.PATIENT, pnr);
        List<HttpClient> doctors = new ArrayList<>();
        try (HttpClient staff = signedIn(account(Role.STAFF, personnummer()));
                ExecutorService pool = Executors.newFixedThreadPool(4)) {
            String fhirId = registerPatient(staff, pnr, "Samtidig");
            long requestId = requestOf(patientEmail);
            for (int i = 0; i < 4; i++) doctors.add(signedIn(account(Role.DOCTOR, personnummer())));

            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (HttpClient doctor : doctors) {
                JsonNode token = json.readTree(get(doctor, "/api/auth/csrf").body());
                HttpRequest approve = request("/api/journal-access-requests/" + requestId + "/approve")
                        .header(token.get("headerName").asString(), token.get("token").asString())
                        .POST(HttpRequest.BodyPublishers.noBody()).build();
                Callable<Integer> call = () -> {
                    start.await();
                    return doctor.send(approve, HttpResponse.BodyHandlers.ofString()).statusCode();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) statuses.add(result.get());

            assertThat(statuses).containsOnly(200, 409);
            assertThat(statuses).filteredOn(code -> code == 200).hasSize(1);
            assertThat(linkedPatient(patientEmail)).isEqualTo(fhirId);
            assertThat(requestIds(patientEmail, "APPROVED")).containsExactly(requestId);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE fhir_patient_id = ?", Integer.class,
                    fhirId)).isEqualTo(1);
        } finally {
            doctors.forEach(HttpClient::close);
        }
    }

    // ---------- database rules (V8) ----------

    @Test
    void theDatabaseEnforcesTheRequestStatesAndOneOpenRequestPerAccount() throws Exception {
        long account = accountId(account(Role.PATIENT, personnummer()));
        long other = accountId(account(Role.PATIENT, personnummer()));
        String fhirId;
        try (HttpClient staff = signedIn(account(Role.STAFF, personnummer()))) {
            fhirId = registerPatient(staff, personnummer(), "Regel");
        }
        String insert = "INSERT INTO journal_access_requests (user_id, fhir_patient_id, status) VALUES (?, ?, ?)";
        // Waiting without a record, pending only with one.
        assertRejected(() -> jdbc.update(insert, account, fhirId, "AWAITING_RECORD"));
        assertRejected(() -> jdbc.update(insert, account, null, "PENDING"));
        assertRejected(() -> jdbc.update(insert, account, null, "UNKNOWN"));
        // Decided requests always have a record and a review time.
        assertRejected(() -> jdbc.update("INSERT INTO journal_access_requests (user_id, fhir_patient_id, status,"
                + " reviewed_at) VALUES (?, NULL, 'REJECTED', now())", account));
        assertRejected(() -> jdbc.update(insert, account, fhirId, "APPROVED"));

        // One open request per account, also while it has no record (NULLs would not collide in a plain index).
        jdbc.update(insert, account, null, "AWAITING_RECORD");
        assertRejected(() -> jdbc.update(insert, account, null, "AWAITING_RECORD"));
        assertRejected(() -> jdbc.update(insert, account, fhirId, "PENDING"));
        // Other accounts and decided history are not affected.
        jdbc.update(insert, other, fhirId, "PENDING");
        jdbc.update("INSERT INTO journal_access_requests (user_id, fhir_patient_id, status, reviewed_at)"
                + " VALUES (?, ?, 'REJECTED', now())", account, fhirId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_access_requests WHERE user_id IN (?, ?)",
                Integer.class, account, other)).isEqualTo(3);
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable insert) {
        org.assertj.core.api.Assertions.assertThatThrownBy(insert)
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ---------- roles and CSRF ----------

    @Test
    void onlyDoctorsReviewAndOnlyPatientsAskHaveAStatusAndAnOwnRecord() throws Exception {
        String pnr = personnummer();
        String patientEmail = account(Role.PATIENT, pnr);
        String otherEmail = account(Role.PATIENT, personnummer());
        try (HttpClient staff = signedIn(account(Role.STAFF, personnummer()));
                HttpClient doctor = signedIn(account(Role.DOCTOR, personnummer()));
                HttpClient patient = signedIn(patientEmail); HttpClient anonymous = newClient()) {
            registerPatient(staff, pnr, "Roll");
            // Without a CSRF token the patient cannot ask.
            assertThat(postWithoutCsrf(patient, "/api/journal-access/me/request").statusCode()).isEqualTo(403);
            assertThat(requestCount(patientEmail)).isZero();
            // A body naming another account, personnummer or record is ignored: the request is the session's own.
            HttpResponse<String> asked = post(patient, "/api/journal-access/me/request",
                    "{\"userId\":" + accountId(otherEmail) + ",\"personnummer\":\"182511044568\",\"fhirPatientId\":\"x\"}");
            assertThat(asked.statusCode()).isEqualTo(200);
            assertThat(requestCount(patientEmail)).isEqualTo(1);
            assertThat(requestCount(otherEmail)).isZero();
            long requestId = requestIds(patientEmail, "PENDING").getFirst();
            String base = "/api/journal-access-requests";

            for (HttpClient notDoctor : List.of(staff, patient)) {
                assertThat(get(notDoctor, base).statusCode()).isEqualTo(403);
                assertThat(get(notDoctor, base + "/pending/count").statusCode()).isEqualTo(403);
                assertThat(get(notDoctor, base + "/" + requestId).statusCode()).isEqualTo(403);
                for (String action : List.of("approve", "reject", "reopen")) {
                    assertThat(post(notDoctor, base + "/" + requestId + "/" + action, "").statusCode()).isEqualTo(403);
                }
            }
            for (HttpClient clinician : List.of(staff, doctor)) {
                assertThat(get(clinician, "/api/journal-access/me").statusCode()).isEqualTo(403);
                assertThat(post(clinician, "/api/journal-access/me/request", "").statusCode()).isEqualTo(403);
                assertThat(get(clinician, "/api/patients/me").statusCode()).isEqualTo(403);
            }
            assertThat(get(anonymous, base).statusCode()).isEqualTo(401);
            assertThat(get(anonymous, "/api/journal-access/me").statusCode()).isEqualTo(401);
            assertThat(post(anonymous, "/api/journal-access/me/request", "").statusCode()).isEqualTo(401);
            assertThat(get(anonymous, "/api/patients/me").statusCode()).isEqualTo(401);
            // Without a CSRF token even a DOCTOR cannot decide.
            assertThat(postWithoutCsrf(doctor, base + "/" + requestId + "/approve").statusCode()).isEqualTo(403);

            assertUnchanged(patientEmail, requestId);
        }
    }
}
