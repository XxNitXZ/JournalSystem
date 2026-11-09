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
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.auth.service.RegistrationService;
import com.medinexus.medicore.fhir.support.LocalFhirServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Patient search end to end: real HTTP with session cookies and CSRF, PostgreSQL ({@code medinexus_test} only)
 * and a local FHIR test server that also holds Patients of "other users" of a shared server.
 *
 * <p>Synthetic data only: personnummer from Skatteverket's published test personnummer. Runs only when
 * {@code DB_URL} names the {@code medinexus_test} database. Accounts and registry entries created here are
 * deleted afterwards; Patients exist only on the local FHIR server, which is discarded after the class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/medinexus_test")
class PatientSearchIntegrationTest {

    private static final String SYSTEM = "http://electronichealth.se/identifier/personnummer";
    private static final String ANNA_1 = "199001012385";
    private static final String ANNA_2 = "198506152381";
    private static final String AKE = "200002292381";
    private static final String FOREIGN = "197303159250";

    private static LocalFhirServer fhir;

    @DynamicPropertySource
    static void fhirServer(DynamicPropertyRegistry registry) throws IOException {
        if (fhir == null) {
            fhir = LocalFhirServer.start();
            // Another user of the shared server has a Patient with the same name.
            Patient foreign = new Patient();
            foreign.setId("foreign-anna");
            foreign.addIdentifier().setSystem(SYSTEM).setValue(FOREIGN);
            foreign.addName().setUse(HumanName.NameUse.OFFICIAL).setFamily("Testsson").addGiven("Anna");
            fhir.seed(foreign);
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

    private String account(Role role) {
        String email = "nit-search-" + role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.test";
        createdAccounts.add(registration.register(email, password, role, "Synthetic", "Testperson", TestPersonnummer.next()).id());
        return email;
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    private HttpResponse<String> post(HttpClient client, String path, String body, boolean withCsrf) throws Exception {
        HttpRequest.Builder builder = request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (withCsrf) {
            JsonNode token = json.readTree(client.send(request("/api/auth/csrf").GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            builder.header(token.get("headerName").asString(), token.get("token").asString());
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpClient signedIn(String email) throws Exception {
        HttpClient client = newClient();
        assertThat(post(client, "/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}", true)
                .statusCode()).isEqualTo(200);
        return client;
    }

    private String register(HttpClient client, String personnummer, String given, String family, String birthDate)
            throws Exception {
        HttpResponse<String> created = post(client, "/api/patients", """
                {"personnummer":"%s","family":"%s","given":["%s"],"gender":"OTHER","birthDate":"%s"}
                """.formatted(personnummer, family, given, birthDate), true);
        assertThat(created.statusCode()).isEqualTo(201);
        return json.readTree(created.body()).get("id").asString();
    }

    private List<String> search(HttpClient client, String query) throws Exception {
        HttpResponse<String> response = post(client, "/api/patients/search", "{\"query\":\"" + query + "\"}", true);
        assertThat(response.statusCode()).as("search status").isEqualTo(200);
        List<String> ids = new ArrayList<>();
        json.readTree(response.body()).forEach(patient -> ids.add(patient.get("id").asString()));
        return ids;
    }

    @Test
    void searchFindsEveryOwnMatchButNeverPatientsOfOtherServerUsers(CapturedOutput output) throws Exception {
        try (HttpClient staff = signedIn(account(Role.STAFF)); HttpClient doctor = signedIn(account(Role.DOCTOR))) {
            String anna1 = register(staff, ANNA_1, "Anna", "Testsson", "1990-01-01");
            String anna2 = register(staff, ANNA_2, "Anna", "Testsson", "1985-06-15");
            String ake = register(doctor, AKE, "Åke", "Öberg", "2000-02-29");
            int requestsBefore = fhir.requests().size();

            // Identical names are returned separately, sorted by birth date; the other user's Anna is not.
            assertThat(search(doctor, "Anna Testsson")).containsExactly(anna2, anna1);
            assertThat(search(doctor, "anna")).containsExactly(anna2, anna1);
            assertThat(search(staff, "Testsson")).containsExactly(anna2, anna1);
            assertThat(search(staff, "ake oberg")).containsExactly(ake);
            assertThat(search(doctor, "Nobody")).isEmpty();

            // Exact personnummer, with or without hyphen; the other user's personnummer finds nothing.
            assertThat(search(doctor, ANNA_1)).containsExactly(anna1);
            assertThat(search(doctor, "19850615-2381")).containsExactly(anna2);
            assertThat(search(doctor, FOREIGN)).isEmpty();

            // The result shows name and personnummer side by side.
            JsonNode first = json.readTree(post(doctor, "/api/patients/search", "{\"query\":\"" + ANNA_1 + "\"}", true).body()).get(0);
            assertThat(first.get("names").get(0).get("family").asString()).isEqualTo("Testsson");
            assertThat(first.get("identifiers").get(0).get("value").asString()).isEqualTo(ANNA_1);

            // Every FHIR request was a POST _search without query string, restricted to MediCore's IDs.
            List<String> searchRequests = fhir.requests().subList(requestsBefore, fhir.requests().size());
            assertThat(searchRequests).isNotEmpty().allMatch(request -> request.equals("POST /fhir/Patient/_search"));
            assertThat(fhir.searchForms()).allSatisfy(form -> assertThat(form.get("_id").getFirst().split(","))
                    .contains(anna1, anna2, ake).doesNotContain("foreign-anna"));
        }
        for (String forbidden : List.of(ANNA_1, ANNA_2, AKE, FOREIGN, "Testsson", "Öberg", password)) {
            assertThat(output.getAll().contains(forbidden)).as("log contains patient data").isFalse();
        }
        assertThat(output.getAll()).contains("Patient search: accountId=");
    }

    @Test
    void onlyDoctorsAndStaffWithCsrfCanSearchAndBadQueriesAreRejected() throws Exception {
        int requestsBefore = fhir.requests().size();
        try (HttpClient patient = signedIn(account(Role.PATIENT)); HttpClient doctor = signedIn(account(Role.DOCTOR));
                HttpClient anonymous = newClient()) {
            assertThat(post(anonymous, "/api/patients/search", "{\"query\":\"Anna\"}", true).statusCode()).isEqualTo(401);
            assertThat(post(patient, "/api/patients/search", "{\"query\":\"Anna\"}", true).statusCode()).isEqualTo(403);
            assertThat(post(doctor, "/api/patients/search", "{\"query\":\"Anna\"}", false).statusCode()).isEqualTo(403);
            for (String invalid : List.of("", "a", "Anna1", "199002301234", "x".repeat(101))) {
                assertThat(post(doctor, "/api/patients/search", "{\"query\":\"" + invalid + "\"}", true).statusCode())
                        .as("status for an invalid query").isEqualTo(400);
            }
            // GET with the query in the URL is not a search.
            assertThat(doctor.send(request("/api/patients?query=Anna").GET().build(), HttpResponse.BodyHandlers.ofString())
                    .statusCode()).isEqualTo(403);
        }
        assertThat(fhir.requests()).hasSize(requestsBefore);
    }

    @Test
    void aFutureBirthDateIsRejectedEvenWithAValidCheckDigit() throws Exception {
        int requestsBefore = fhir.requests().size();
        try (HttpClient staff = signedIn(account(Role.STAFF))) {
            // A valid date and check digit, but the birth date (2099) is in the future.
            HttpResponse<String> rejected = post(staff, "/api/patients", """
                    {"personnummer":"209901011230","family":"Testsson","given":["Anna"],"gender":"OTHER","birthDate":"2099-01-01"}
                    """, true);
            assertThat(rejected.statusCode()).isEqualTo(400);
            assertThat(rejected.body()).isEmpty();
        }
        assertThat(fhir.requests()).hasSize(requestsBefore);
    }
}
