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

import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.medinexus.medicore.support.TestPersonnummer;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.auth.service.AccountFhirIdentityService;
import com.medinexus.medicore.auth.service.RegistrationService;
import com.medinexus.medicore.fhir.support.LocalFhirServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * US-04.02 end to end: real HTTP with session cookies, real accounts in PostgreSQL ({@code medinexus_test}
 * only) and a local FHIR test server holding synthetic Patients; no external FHIR server.
 *
 * <p>Runs only when {@code DB_URL} names the {@code medinexus_test} database. Each test deletes exactly the
 * accounts it created; Patients exist only on the local FHIR server, which is discarded after the class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/medinexus_test")
class PatientProfileIntegrationTest {

    private static final String SYSTEM = "http://electronichealth.se/identifier/personnummer";
    /** Synthetic test personnummer (Skatteverket's test series). */
    private static final String PERSONNUMMER = "196407133252";
    private static final String PATIENT_ID = "syn-profile-" + UUID.randomUUID().toString().substring(0, 8);
    /** On the same (shared) FHIR server, but neither registered by MediCore nor linked to an account. */
    private static final String FOREIGN_ID = "syn-foreign-" + UUID.randomUUID().toString().substring(0, 8);
    /** Not registered by MediCore, but linked to a PATIENT account by the operator command. */
    private static final String LINKED_ID = "syn-linked-" + UUID.randomUUID().toString().substring(0, 8);

    private static LocalFhirServer fhir;

    @DynamicPropertySource
    static void fhirServer(DynamicPropertyRegistry registry) throws IOException {
        if (fhir == null) {
            fhir = LocalFhirServer.start();
            Patient patient = new Patient();
            patient.setId(PATIENT_ID);
            patient.addIdentifier().setSystem(SYSTEM).setValue(PERSONNUMMER);
            patient.addName().setUse(HumanName.NameUse.OFFICIAL).setFamily("Profilsson").addGiven("Syntet");
            patient.setGender(AdministrativeGender.OTHER);
            patient.getBirthDateElement().setValueAsString("1964-07-13");
            fhir.seed(patient);
            for (String id : List.of(FOREIGN_ID, LINKED_ID)) {
                Patient other = new Patient();
                other.setId(id);
                other.addName().setUse(HumanName.NameUse.OFFICIAL).setFamily("Profilsson").addGiven("Annan");
                fhir.seed(other);
            }
        }
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

    /** The seeded Patient counts as registered by MediCore (registry entry without a registering account). */
    @BeforeEach
    void registerTheSeededPatient() {
        jdbc.update("INSERT INTO registered_patients (fhir_patient_id) VALUES (?) ON CONFLICT DO NOTHING", PATIENT_ID);
    }

    @AfterEach
    void deleteCreatedAccountsAndRegistryEntries() {
        jdbc.update("DELETE FROM registered_patients WHERE fhir_patient_id IN (?, ?)", PATIENT_ID, "syn-missing-404");
        createdAccounts.forEach(id -> jdbc.update("DELETE FROM registered_patients WHERE registered_by = ?", id));
        createdAccounts.forEach(users::deleteById);
    }

    private String account(Role role) {
        String email = "nit-us0402-" + role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.test";
        createdAccounts.add(registration.register(email, password, role, "Synthetic", "Testperson", TestPersonnummer.next()).id());
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
        JsonNode token = json.readTree(client.send(request("/api/auth/csrf").GET().build(),
                HttpResponse.BodyHandlers.ofString()).body());
        HttpResponse<String> login = client.send(request("/api/auth/login")
                .header(token.get("headerName").asString(), token.get("token").asString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        return client;
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertNoPatientDataLogged(CapturedOutput output) {
        for (String forbidden : List.of(PERSONNUMMER, "6407133252", "Profilsson", PATIENT_ID, password)) {
            assertThat(output.getAll().contains(forbidden)).as("log contains patient data or credentials").isFalse();
        }
    }

    @Test
    void doctorsAndStaffViewTheProfileReadDirectlyFromFhir(CapturedOutput output) throws Exception {
        for (Role role : List.of(Role.DOCTOR, Role.STAFF)) {
            int requestsBefore = fhir.requests().size();
            try (HttpClient client = signedIn(account(role))) {
                HttpResponse<String> response = get(client, "/api/patients/" + PATIENT_ID);
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
                JsonNode profile = json.readTree(response.body());
                assertThat(profile.get("id").asString()).isEqualTo(PATIENT_ID);
                assertThat(profile.get("identifiers").get(0).get("system").asString()).isEqualTo(SYSTEM);
                assertThat(profile.get("identifiers").get(0).get("value").asString()).isEqualTo(PERSONNUMMER);
                assertThat(profile.get("names").get(0).get("family").asString()).isEqualTo("Profilsson");
                assertThat(profile.get("gender").asString()).isEqualTo("other");
                assertThat(profile.get("birthDate").asString()).isEqualTo("1964-07-13");
            }
            // Exactly one read per view, nothing cached and nothing written.
            assertThat(fhir.requests().subList(requestsBefore, fhir.requests().size()))
                    .containsExactly("GET /fhir/Patient/" + PATIENT_ID);
        }
        assertThat(output.getAll()).contains("Patient profile viewed: accountId=");
        assertNoPatientDataLogged(output);
    }

    @Test
    void aPatientRegisteredThroughMediCoreCanBeViewed() throws Exception {
        try (HttpClient staff = signedIn(account(Role.STAFF))) {
            JsonNode token = json.readTree(get(staff, "/api/auth/csrf").body());
            HttpResponse<String> created = staff.send(request("/api/patients")
                    .header(token.get("headerName").asString(), token.get("token").asString())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"personnummer":"198506152381","family":"Testsson","given":["Tolvan"],
                             "gender":"MALE","birthDate":"1985-06-15"}
                            """)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(created.statusCode()).isEqualTo(201);
            String location = created.headers().firstValue("Location").orElseThrow();

            HttpResponse<String> profile = get(staff, location);
            assertThat(profile.statusCode()).isEqualTo(200);
            // The profile is the registered Patient plus the e-mail of a linked account (none here).
            tools.jackson.databind.node.ObjectNode shown = (tools.jackson.databind.node.ObjectNode) json.readTree(profile.body());
            assertThat(shown.get("email").isNull()).isTrue();
            shown.remove("email");
            assertThat(shown.get("version").asString()).isEqualTo("1");
            shown.remove("version");
            assertThat(shown.get("phoneNumber").isNull()).isTrue();
            shown.remove("phoneNumber");
            assertThat(shown).isEqualTo(json.readTree(created.body()));
        }
    }

    @Test
    void missingPatientsAre404AndInvalidIdsAre400(CapturedOutput output) throws Exception {
        try (HttpClient doctor = signedIn(account(Role.DOCTOR))) {
            int requestsBefore = fhir.requests().size();
            // Not known to MediCore: 404 without any FHIR request.
            HttpResponse<String> unknown = get(doctor, "/api/patients/syn-unknown-404");
            assertThat(unknown.statusCode()).isEqualTo(404);
            assertThat(unknown.body()).isEmpty();
            assertThat(fhir.requests()).hasSize(requestsBefore);
            // Registered by MediCore but gone from the FHIR server: 404 after exactly one read.
            jdbc.update("INSERT INTO registered_patients (fhir_patient_id) VALUES (?)", "syn-missing-404");
            HttpResponse<String> missing = get(doctor, "/api/patients/syn-missing-404");
            assertThat(missing.statusCode()).isEqualTo(404);
            assertThat(missing.body()).isEmpty();
            assertThat(fhir.requests()).hasSize(requestsBefore + 1);

            for (String invalid : List.of("bad_id", "x".repeat(65), "%C3%A4")) {
                HttpResponse<String> response = get(doctor, "/api/patients/" + invalid);
                assertThat(response.statusCode()).as("status for an invalid ID").isEqualTo(400);
                assertThat(response.body()).isEmpty();
            }
            // Invalid IDs never reach the FHIR server.
            assertThat(fhir.requests()).hasSize(requestsBefore + 1);
        }
        assertThat(output.getAll().contains("syn-missing-404")).as("log contains the requested ID").isFalse();
    }

    @Test
    void patientsOfOtherServerUsersAreNeverShownButAccountLinkedPatientsAre() throws Exception {
        String patientEmail = account(Role.PATIENT);
        identities.assign(users.findByEmail(patientEmail).orElseThrow().getId(), LINKED_ID);
        try (HttpClient doctor = signedIn(account(Role.DOCTOR))) {
            int requestsBefore = fhir.requests().size();
            // Exists on the shared server, but does not belong to MediCore: 404 and never read.
            HttpResponse<String> foreign = get(doctor, "/api/patients/" + FOREIGN_ID);
            assertThat(foreign.statusCode()).isEqualTo(404);
            assertThat(fhir.requests()).hasSize(requestsBefore);
            // Linked to a MediCore account (operator identity provisioning) without a registry entry: still shown.
            HttpResponse<String> linked = get(doctor, "/api/patients/" + LINKED_ID);
            assertThat(linked.statusCode()).isEqualTo(200);
            assertThat(json.readTree(linked.body()).get("id").asString()).isEqualTo(LINKED_ID);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM registered_patients WHERE fhir_patient_id = ?",
                Integer.class, LINKED_ID)).isZero();
    }

    @Test
    void anonymousPatientAndListRequestsNeverReachFhir() throws Exception {
        int requestsBefore = fhir.requests().size();
        // A PATIENT account linked to this very Patient: self-view belongs to US-04.03 and is still denied.
        String patientEmail = account(Role.PATIENT);
        identities.assign(users.findByEmail(patientEmail).orElseThrow().getId(), PATIENT_ID);
        int afterLinking = fhir.requests().size();
        assertThat(afterLinking).isEqualTo(requestsBefore + 1);

        try (HttpClient anonymous = newClient(); HttpClient patient = signedIn(patientEmail);
                HttpClient doctor = signedIn(account(Role.DOCTOR))) {
            assertThat(get(anonymous, "/api/patients/" + PATIENT_ID).statusCode()).isEqualTo(401);
            assertThat(get(patient, "/api/patients/" + PATIENT_ID).statusCode()).isEqualTo(403);
            // No patient list or search.
            assertThat(get(doctor, "/api/patients").statusCode()).isEqualTo(403);
            assertThat(get(doctor, "/api/patients?name=Profilsson").statusCode()).isEqualTo(403);
        }
        assertThat(fhir.requests()).hasSize(afterLinking);
    }

    @Test
    void theCurrentDatabaseRoleDecidesNotTheRoleInTheSession() throws Exception {
        String email = account(Role.DOCTOR);
        int requestsBefore = fhir.requests().size();
        try (HttpClient client = signedIn(email)) {
            User account = users.findByEmail(email).orElseThrow();
            account.setRole(Role.PATIENT);
            users.saveAndFlush(account);
            assertThat(get(client, "/api/patients/" + PATIENT_ID).statusCode()).isEqualTo(403);
        }
        assertThat(fhir.requests()).hasSize(requestsBefore);
    }
}
