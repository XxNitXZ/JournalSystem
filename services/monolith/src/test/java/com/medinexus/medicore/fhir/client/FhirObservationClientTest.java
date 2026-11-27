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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.medinexus.medicore.fhir.config.FhirClientConfig;
import com.medinexus.medicore.fhir.mapper.ObservationMapper;
import com.sun.net.httpserver.HttpServer;

class FhirObservationClientTest {

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(exchange.getRequestMethod() + " " + path);
            int status = path.endsWith("/missing") ? 404 : path.endsWith("/failure") ? 500
                    : path.endsWith("/unavailable") ? 503 : 200;
            String body = status == 200 ? """
                    {"resourceType":"Observation","id":"synthetic-1",
                     "status":"final","code":{"text":"Synthetic measurement"},
                     "subject":{"reference":"Patient/synthetic-patient"},
                     "valueQuantity":{"value":12.5,"unit":"mg","system":"http://unitsofmeasure.org","code":"mg"}}
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
                        FhirObservationClient.class, ObservationMapper.class)
                .withPropertyValues("medinexus.fhir.base-url=http://127.0.0.1:" + server.getAddress().getPort() + "/fhir",
                        "medinexus.fhir.connect-timeout=2s", "medinexus.fhir.socket-timeout=5s",
                        "medinexus.fhir.connection-request-timeout=2s");
    }

    @Test
    void retrievesAndMapsSyntheticObservationWithExactlyOneGet() {
        contextRunner().run(context -> {
            var observation = context.getBean(FhirObservationClient.class).readObservation("synthetic-1");
            var dto = context.getBean(ObservationMapper.class).toDto(observation);
            assertThat(dto.id()).isEqualTo("synthetic-1");
            assertThat(dto.status()).isEqualTo("final");
            assertThat(dto.subject().reference()).isEqualTo("Patient/synthetic-patient");
            assertThat(dto.code().text()).isEqualTo("Synthetic measurement");
            assertThat(dto.value().type().name()).isEqualTo("QUANTITY");
            assertThat(requests).containsExactly("GET /fhir/Observation/synthetic-1");
        });
    }

    @Test
    void rejectsInvalidIdsBeforeSendingRequests() {
        contextRunner().run(context -> {
            FhirObservationClient client = context.getBean(FhirObservationClient.class);
            for (String id : new String[] {null, "", " ", " id", "Observation/123", "https://example.test/Observation/1",
                    "../Observation/1", "id?x=1", "id#fragment", "id_name", "ä", "x".repeat(65)}) {
                assertThatThrownBy(() -> client.readObservation(id))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("A valid logical FHIR Observation ID is required");
            }
            assertThat(requests).isEmpty();
        });
    }

    @ParameterizedTest
    @CsvSource({"missing, 404, CLIENT_ERROR", "failure, 500, SERVER_ERROR", "unavailable, 503, UNAVAILABLE"})
    void preservesTranslatedExternalErrors(String id, int status, FhirServerException.Type type) {
        contextRunner().run(context -> {
            assertThatThrownBy(() -> context.getBean(FhirObservationClient.class).readObservation(id))
                    .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                        assertThat(failure.operation()).isEqualTo("observation-read");
                        assertThat(failure.httpStatus()).hasValue(status);
                        assertThat(failure.type()).isEqualTo(type);
                        assertThat(failure.issues()).containsExactly("error:processing");
                        assertThat(failure.getCause()).isNull();
                        assertThat(failure.getMessage()).doesNotContain("SENSITIVE", "/Observation/", "127.0.0.1");
                    });
            assertThat(requests).containsExactly("GET /fhir/Observation/" + id);
        });
    }
}
