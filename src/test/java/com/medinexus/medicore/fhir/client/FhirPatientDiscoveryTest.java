package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.medinexus.medicore.fhir.config.FhirClientConfig;
import com.medinexus.medicore.fhir.support.LocalFhirServer;
import com.medinexus.medicore.patient.dto.PatientSearchCriteria;
import com.sun.net.httpserver.HttpServer;

/** Discovery search for importing existing Patients, against local servers only. Fictional data. */
@ExtendWith(OutputCaptureExtension.class)
class FhirPatientDiscoveryTest {

    private static final String SYSTEM = "http://electronichealth.se/identifier/personnummer";

    private LocalFhirServer localFhir;
    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (localFhir != null) localFhir.close();
        if (server != null) server.stop(0);
    }

    private ApplicationContextRunner context(String baseUrl) {
        return new ApplicationContextRunner()
                .withUserConfiguration(FhirClientConfig.class, FhirRequestExecutor.class, FhirPatientClient.class)
                .withPropertyValues("medinexus.fhir.base-url=" + baseUrl, "medinexus.fhir.connect-timeout=2s",
                        "medinexus.fhir.socket-timeout=5s", "medinexus.fhir.connection-request-timeout=2s");
    }

    private void seed(String id, String given, String family, String personnummer) {
        Patient patient = new Patient();
        patient.setId(id);
        patient.addName().setUse(HumanName.NameUse.OFFICIAL).setFamily(family).addGiven(given);
        if (personnummer != null) patient.addIdentifier().setSystem(SYSTEM).setValue(personnummer);
        localFhir.seed(patient);
    }

    @Test
    void searchesTheWholeServerByNameAndExactPersonnummerReadOnly(CapturedOutput output) throws IOException {
        localFhir = LocalFhirServer.start();
        seed("ext-1", "Ali", "Khan", "200412201971");
        seed("ext-2", "Ali", "Khan", null);
        seed("ext-3", "Sara", "Ahmed", "182003151237");
        context(localFhir.baseUrl()).run(ctx -> {
            FhirPatientClient client = ctx.getBean(FhirPatientClient.class);

            var byName = client.discoverPatients(PatientSearchCriteria.parse("Ali Khan"));
            // Both Patients with the same name are separate results; none is restricted to a registry.
            assertThat(byName.patients()).extracting(patient -> patient.getIdElement().getIdPart())
                    .containsExactly("ext-1", "ext-2");
            assertThat(byName.truncated()).isFalse();

            var byPersonnummer = client.discoverPatients(new PatientSearchCriteria("182003151237", List.of()));
            assertThat(byPersonnummer.patients()).extracting(patient -> patient.getIdElement().getIdPart())
                    .containsExactly("ext-3");
            assertThat(client.discoverPatients(new PatientSearchCriteria("199001012385", List.of())).patients()).isEmpty();
        });
        assertThat(localFhir.searchForms()).allSatisfy(form -> {
            assertThat(form).doesNotContainKey("_id");
            assertThat(form.get("_count")).containsExactly("20");
        });
        // Only searches: no create or update.
        assertThat(localFhir.requests()).allSatisfy(request -> assertThat(request).startsWith("POST /fhir/Patient/_search"));
        assertThat(output.getAll()).doesNotContain("200412201971", "Khan");
    }

    @Test
    void largeResultsAreBoundedToFivePagesAndMarkedTruncated() throws IOException {
        localFhir = LocalFhirServer.start();
        for (int i = 0; i < 130; i++) seed("many-" + i, "Fiktiv", "Mangdsson", null);
        context(localFhir.baseUrl()).run(ctx -> {
            var found = ctx.getBean(FhirPatientClient.class).discoverPatients(PatientSearchCriteria.parse("Mangdsson"));
            assertThat(found.patients()).hasSize(FhirPatientClient.DISCOVERY_MAX_RESULTS);
            assertThat(found.truncated()).isTrue();
        });
        // One search and at most four further pages.
        assertThat(localFhir.requests()).hasSize(FhirPatientClient.DISCOVERY_MAX_PAGES);

        localFhir.close();
        localFhir = LocalFhirServer.start();
        for (int i = 0; i < 40; i++) seed("some-" + i, "Fiktiv", "Fatalsson", null);
        context(localFhir.baseUrl()).run(ctx -> {
            var found = ctx.getBean(FhirPatientClient.class).discoverPatients(PatientSearchCriteria.parse("Fatalsson"));
            assertThat(found.patients()).hasSize(40);
            assertThat(found.truncated()).isFalse();
        });
    }

    /** A server that answers the search with the given bundle and records every request. */
    private String stub(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fhir/", exchange -> {
            try (exchange) {
                requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                exchange.getRequestBody().readAllBytes();
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/fhir";
    }

    private static String bundle(String entries, String nextUrl) {
        return "{\"resourceType\":\"Bundle\",\"type\":\"searchset\""
                + (nextUrl == null ? "" : ",\"link\":[{\"relation\":\"next\",\"url\":\"" + nextUrl + "\"}]")
                + ",\"entry\":[" + entries + "]}";
    }

    private static final String PATIENT = "{\"resource\":{\"resourceType\":\"Patient\",\"id\":\"ext-1\"},"
            + "\"search\":{\"mode\":\"match\"}}";

    @Test
    void unsafeOrInvalidResponsesFailTheWholeSearch() throws IOException {
        for (String body : List.of(
                bundle(PATIENT, "https://evil.example/fhir?_getpages=x"),
                bundle("{\"resource\":{\"resourceType\":\"Observation\",\"id\":\"o-1\",\"status\":\"final\","
                        + "\"code\":{\"text\":\"x\"}}}", null),
                bundle(PATIENT + "," + PATIENT, null),
                bundle("{\"resource\":{\"resourceType\":\"Patient\",\"id\":\"ext-1\"},\"search\":{\"mode\":\"include\"}}",
                        null),
                "{\"resourceType\":\"Bundle\",\"type\":\"collection\"}")) {
            String base = stub(200, body);
            context(base).run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirPatientClient.class)
                    .discoverPatients(PatientSearchCriteria.parse("Ali")))
                    .isInstanceOfSatisfying(FhirServerException.class,
                            failure -> assertThat(failure.type()).isEqualTo(FhirServerException.Type.INVALID_RESPONSE)));
            server.stop(0);
        }
        // An unsafe next link is never followed.
        assertThat(requests).noneMatch(request -> request.contains("evil"));
    }

    @Test
    void anUnavailableServerIsReported() throws IOException {
        String base = stub(503, "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\","
                + "\"code\":\"transient\"}]}");
        context(base).run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirPatientClient.class)
                .discoverPatients(PatientSearchCriteria.parse("Ali")))
                .isInstanceOfSatisfying(FhirServerException.class,
                        failure -> assertThat(failure.type()).isEqualTo(FhirServerException.Type.UNAVAILABLE)));
        assertThatThrownBy(() -> new FhirPatientClient(null, null).discoverPatients(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
