package com.medinexus.medicore.journalaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.medinexus.medicore.clinicalapi.ClinicalClient;
import com.medinexus.medicore.patient.validation.Personnummer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * NIT-331: the whole journal-access flow over real HTTP against the real "identity" schema, with only the Clinical
 * API (C1, C2) mocked. Drives the endpoints the way MediPortal does: register, sign in with the cookie, request,
 * review, approve, and the patient's next status read. Proves what the mocked unit tests can't: the SQL behind
 * "one open request per account", the conditional approve, the account link, and the token refresh afterwards.
 *
 * <p>Needs the Identity database (DB_URL, IDENTITY_DB_PASSWORD); skipped without it. Uses fresh e-mails, personnummer
 * and FHIR ids each run and deletes its rows afterwards, so it can also run against a developer database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "IDENTITY_DB_PASSWORD", matches = ".+")
class JournalAccessFlowIntegrationTest {

    private static final String PASSWORD = "Flow-Passw0rd";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtDecoder decoder;

    @MockitoBean
    private ClinicalClient clinical;

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> createdEmails = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String email : createdEmails) {
            jdbc.update("DELETE FROM journal_access_requests WHERE user_id IN (SELECT id FROM users WHERE email = ?)",
                    email);
            jdbc.update("DELETE FROM users WHERE email = ?", email);
        }
    }

    /** A browser: its own cookie jar. */
    private record Browser(HttpClient http, CookieManager cookies) {
        String token() {
            return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("medicore_token"))
                    .map(HttpCookie::getValue).findFirst().orElse(null);
        }
    }

    private static Browser browser() {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        return new Browser(HttpClient.newBuilder().cookieHandler(cookies).build(), cookies);
    }

    private HttpResponse<String> send(Browser browser, String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return browser.http().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode read(HttpResponse<String> response) {
        return json.readTree(response.body());
    }

    /** A valid, fictional personnummer that no account has yet. */
    private static String freshPersonnummer() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        while (true) {
            String base = String.format("1990%02d%02d%03d", random.nextInt(1, 13), random.nextInt(1, 29),
                    random.nextInt(0, 1000));
            for (int check = 0; check <= 9; check++) {
                String candidate = base + check;
                if (candidate.equals(Personnummer.normalize(candidate, LocalDate.now()))) return candidate;
            }
        }
    }

    private Browser registerAndSignIn(String role, String personnummer) throws Exception {
        String email = role.toLowerCase() + "-" + UUID.randomUUID() + "@example.test";
        createdEmails.add(email);
        Browser browser = browser();
        HttpResponse<String> registered = send(browser, "POST", "/api/auth/register", """
                {"email":"%s","password":"%s","role":"%s","givenName":"Flow","familyName":"%s","personnummer":"%s"}
                """.formatted(email, PASSWORD, role, role, personnummer));
        assertThat(registered.statusCode()).as("register " + role).isEqualTo(201);
        // Patients are signed in by registration; clinicians sign in explicitly.
        HttpResponse<String> login = send(browser, "POST", "/api/auth/login",
                "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD));
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(browser.token()).isNotNull();
        return browser;
    }

    @Test
    void aPatientRequestsADoctorApprovesAndThePatientGetsAccessWithoutSigningInAgain() throws Exception {
        String personnummer = freshPersonnummer();
        String fhirId = "flow-" + UUID.randomUUID().toString().substring(0, 8);
        Browser patient = registerAndSignIn("PATIENT", personnummer);
        Browser doctor = registerAndSignIn("DOCTOR", freshPersonnummer());

        // 1. No request yet.
        assertThat(read(send(patient, "GET", "/api/journal-access/me", null)).get("status").asString())
                .isEqualTo("NONE");

        // 2. Clinical has no record with this personnummer yet: the request waits for one.
        when(clinical.registeredRecords(personnummer)).thenReturn(List.of());
        HttpResponse<String> requested = send(patient, "POST", "/api/journal-access/me/request", null);
        assertThat(requested.statusCode()).isEqualTo(200);
        assertThat(read(requested).get("status").asString()).isEqualTo("AWAITING_RECORD");

        // Asking again while it is open returns the same request; the database holds exactly one.
        assertThat(read(send(patient, "POST", "/api/journal-access/me/request", null)).get("status").asString())
                .isEqualTo("AWAITING_RECORD");
        Long patientId = jdbc.queryForObject("SELECT id FROM users WHERE personnummer = ?", Long.class, personnummer);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_access_requests WHERE user_id = ?", Long.class,
                patientId)).isEqualTo(1L);

        // 3. A record is registered in Clinical; the patient's next status read attaches it.
        when(clinical.registeredRecords(personnummer))
                .thenReturn(List.of(new ClinicalClient.RegisteredRecord(fhirId, "VALID", personnummer)));
        assertThat(read(send(patient, "GET", "/api/journal-access/me", null)).get("status").asString())
                .isEqualTo("PENDING");

        // 4. The doctor finds it in the review list. A patient cannot review or approve.
        JsonNode list = read(send(doctor, "GET", "/api/journal-access-requests", null));
        JsonNode mine = null;
        for (JsonNode row : list) {
            if (personnummer.equals(row.get("personnummer").asString())) mine = row;
        }
        assertThat(mine).as("the request in the doctor's list").isNotNull();
        assertThat(mine.get("status").asString()).isEqualTo("PENDING");
        long requestId = mine.get("id").asLong();
        assertThat(send(patient, "GET", "/api/journal-access-requests", null).statusCode()).isEqualTo(403);
        assertThat(send(patient, "POST", "/api/journal-access-requests/" + requestId + "/approve", null).statusCode())
                .isEqualTo(403);

        // 5. The doctor reviews the record (C2) and approves.
        when(clinical.patientRecord(fhirId)).thenReturn(Optional.of(new ClinicalClient.PatientRecord(fhirId,
                List.of("Flow"), "PATIENT", "VALID", personnummer, "female", "1990-01-01")));
        JsonNode detail = read(send(doctor, "GET", "/api/journal-access-requests/" + requestId, null));
        assertThat(detail.get("recordStatus").asString()).isEqualTo("AVAILABLE");
        assertThat(detail.get("personnummerMatches").asBoolean()).isTrue();
        HttpResponse<String> approved = send(doctor, "POST",
                "/api/journal-access-requests/" + requestId + "/approve", null);
        assertThat(approved.statusCode()).isEqualTo(200);
        assertThat(read(approved).get("status").asString()).isEqualTo("APPROVED");

        // Approving again is refused; the account is linked exactly once.
        HttpResponse<String> again = send(doctor, "POST", "/api/journal-access-requests/" + requestId + "/approve",
                null);
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(read(again).get("code").asString()).isEqualTo("REQUEST_NOT_PENDING");
        assertThat(jdbc.queryForObject("SELECT fhir_patient_id FROM users WHERE id = ?", String.class, patientId))
                .isEqualTo(fhirId);

        // 6. The patient's old cookie has no FHIR id; the next status read says APPROVED and renews the cookie
        //    with the fhirPatientId claim, so their own record opens without signing in again (NIT-327).
        assertThat(decoder.decode(patient.token()).hasClaim("fhirPatientId")).isFalse();
        assertThat(read(send(patient, "GET", "/api/journal-access/me", null)).get("status").asString())
                .isEqualTo("APPROVED");
        assertThat(decoder.decode(patient.token()).getClaimAsString("fhirPatientId")).isEqualTo(fhirId);
    }

    @Test
    void aRecordLinkedToAnotherAccountIsNeverOfferedAgain() throws Exception {
        String personnummer = freshPersonnummer();
        String fhirId = "flow-" + UUID.randomUUID().toString().substring(0, 8);
        Browser patient = registerAndSignIn("PATIENT", personnummer);
        // Another account already owns this FHIR Patient.
        registerAndSignIn("PATIENT", freshPersonnummer());
        String otherEmail = createdEmails.get(createdEmails.size() - 1);
        jdbc.update("UPDATE users SET fhir_patient_id = ? WHERE email = ?", fhirId, otherEmail);

        when(clinical.registeredRecords(anyString()))
                .thenReturn(List.of(new ClinicalClient.RegisteredRecord(fhirId, "VALID", personnummer)));
        HttpResponse<String> refused = send(patient, "POST", "/api/journal-access/me/request", null);
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(read(refused).get("code").asString()).isEqualTo("LINK_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_access_requests jar JOIN users u "
                + "ON u.id = jar.user_id WHERE u.personnummer = ?", Long.class, personnummer)).isZero();
    }
}
