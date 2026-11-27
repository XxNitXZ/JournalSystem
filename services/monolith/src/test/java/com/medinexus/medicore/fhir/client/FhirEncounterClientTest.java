package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.medinexus.medicore.fhir.config.FhirClientConfig;
import com.medinexus.medicore.fhir.mapper.EncounterMapper;
import com.sun.net.httpserver.HttpServer;

@ExtendWith(OutputCaptureExtension.class)
class FhirEncounterClientTest {

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(exchange.getRequestMethod() + " " + path);
            int status = path.endsWith("missing") ? 404 : path.endsWith("/failure") ? 500
                    : path.endsWith("/unavailable") ? 503 : 200;
            String body = status == 200 ? """
                    {"resourceType":"Encounter","id":"synthetic-1","status":"in-progress",
                     "class":{"system":"http://terminology.hl7.org/CodeSystem/v3-ActCode","code":"AMB"},
                     "subject":{"reference":"Patient/synthetic-patient"},
                     "participant":[{"individual":{"reference":"Practitioner/synthetic-practitioner"}}],
                     "period":{"start":"2026-06-15T09:00:00+02:00"},
                     "serviceProvider":{"reference":"Organization/synthetic-clinic"}}
                    """ : """
                    {"resourceType":"OperationOutcome","issue":[
                     {"severity":"error","code":"processing","diagnostics":"SENSITIVE server detail"}]}
                    """;
            exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(FhirClientConfig.class, FhirRequestExecutor.class,
                        FhirEncounterClient.class, EncounterMapper.class)
                .withPropertyValues("medinexus.fhir.base-url=http://127.0.0.1:" + server.getAddress().getPort() + "/fhir",
                        "medinexus.fhir.connect-timeout=2s", "medinexus.fhir.socket-timeout=5s",
                        "medinexus.fhir.connection-request-timeout=2s");
    }

    @Test
    void retrievesAndMapsSyntheticEncounterWithExactlyOneGet(CapturedOutput output) {
        contextRunner().run(context -> {
            var encounter = context.getBean(FhirEncounterClient.class).readEncounter("synthetic-1");
            var dto = context.getBean(EncounterMapper.class).toDto(encounter);
            assertThat(dto.id()).isEqualTo("synthetic-1");
            assertThat(dto.status()).isEqualTo("in-progress");
            assertThat(dto.encounterClass().code()).isEqualTo("AMB");
            assertThat(dto.subject().reference()).isEqualTo("Patient/synthetic-patient");
            assertThat(dto.participants().getFirst().individual().reference()).isEqualTo("Practitioner/synthetic-practitioner");
            assertThat(dto.period().start()).isEqualTo("2026-06-15T09:00:00+02:00");
            assertThat(dto.serviceProvider().reference()).isEqualTo("Organization/synthetic-clinic");
            assertThat(requests).containsExactly("GET /fhir/Encounter/synthetic-1");
        });
        assertThat(output.getAll()).doesNotContain("FHIR request failed");
    }

    @Test
    void rejectsInvalidIdsBeforeSendingRequests() {
        contextRunner().run(context -> {
            FhirEncounterClient client = context.getBean(FhirEncounterClient.class);
            for (String id : new String[] {null, "", " ", " id", "Encounter/123", "https://example.test/Encounter/1",
                    "../Encounter/1", "id?x=1", "id#fragment", "id_name", "ä", "x".repeat(65)}) {
                assertThatThrownBy(() -> client.readEncounter(id))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("A valid logical FHIR Encounter ID is required");
            }
            assertThat(requests).isEmpty();
        });
    }

    @ParameterizedTest
    @CsvSource({"missing, 404, CLIENT_ERROR", "failure, 500, SERVER_ERROR", "unavailable, 503, UNAVAILABLE"})
    void preservesTranslatedExternalErrors(String id, int status, FhirServerException.Type type) {
        contextRunner().run(context -> {
            assertThatThrownBy(() -> context.getBean(FhirEncounterClient.class).readEncounter(id))
                    .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                        assertThat(failure.operation()).isEqualTo("encounter-read");
                        assertThat(failure.httpStatus()).hasValue(status);
                        assertThat(failure.type()).isEqualTo(type);
                        assertThat(failure.issues()).containsExactly("error:processing");
                        assertThat(failure.getCause()).isNull();
                        assertThat(failure.getMessage()).doesNotContain("SENSITIVE", "/Encounter/", "127.0.0.1");
                    });
            assertThat(requests).containsExactly("GET /fhir/Encounter/" + id);
        });
    }

    @Test
    void failureIsLoggedOnceWithoutIdentifiersUrlsOrServerDetails(CapturedOutput output) {
        contextRunner().run(context -> assertThatThrownBy(() ->
                context.getBean(FhirEncounterClient.class).readEncounter("secret-encounter-77-missing"))
                .isInstanceOf(FhirServerException.class));

        assertThat(output.getAll())
                .contains("FHIR request failed: operation=encounter-read, type=CLIENT_ERROR, httpStatus=404, "
                        + "issues=[error:processing], cause=ResourceNotFoundException, durationMs=")
                .doesNotContain("secret-encounter-77")
                .doesNotContain("SENSITIVE")
                .doesNotContain("127.0.0.1")
                .doesNotContain("/Encounter/");
        assertThat(output.getAll().split("FHIR request failed", -1)).hasSize(2);
    }
}
