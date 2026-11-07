package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.medinexus.medicore.fhir.config.FhirClientConfig;
import com.medinexus.medicore.fhir.mapper.LocationMapper;
import com.medinexus.medicore.location.dto.LocationDto.Position;
import com.sun.net.httpserver.HttpServer;

/** Location reads against a local HTTP server with synthetic data only. */
@ExtendWith(OutputCaptureExtension.class)
class FhirLocationClientTest {

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(exchange.getRequestMethod() + " " + path);
            int status = path.endsWith("missing") ? 404 : path.endsWith("/gone") ? 410 : path.endsWith("/failure") ? 500
                    : path.endsWith("/unavailable") ? 503 : 200;
            String body;
            if (status != 200) {
                body = """
                        {"resourceType":"OperationOutcome","issue":[
                         {"severity":"error","code":"processing","diagnostics":"SENSITIVE server detail"}]}
                        """;
            } else if (path.endsWith("/wrong-type")) {
                body = "{\"resourceType\":\"Organization\",\"id\":\"wrong-type\",\"name\":\"N\"}";
            } else if (path.endsWith("/broken")) {
                body = "{\"resourceType\":\"Location\",\"name\":";
            } else {
                body = """
                        {"resourceType":"Location","id":"synthetic-loc","meta":{"versionId":"4"},
                         "status":"active","operationalStatus":{"system":"http://terminology.hl7.org/CodeSystem/v2-0116","code":"U"},
                         "name":"Synthetic Ward","mode":"instance",
                         "telecom":[{"system":"phone","value":"+46000000002","use":"work"}],
                         "address":{"use":"work","line":["Synthetic street 2"],"city":"Synthetic City"},
                         "position":{"longitude":18.0686000,"latitude":59.3293000},
                         "managingOrganization":{"reference":"Organization/synthetic-org","display":"Synthetic Clinic"},
                         "partOf":{"reference":"Location/synthetic-building"},
                         "hoursOfOperation":[{"allDay":true}]}
                        """;
            }
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
                        FhirLocationClient.class, LocationMapper.class)
                .withPropertyValues("medinexus.fhir.base-url=http://127.0.0.1:" + server.getAddress().getPort() + "/fhir",
                        "medinexus.fhir.connect-timeout=2s", "medinexus.fhir.socket-timeout=5s",
                        "medinexus.fhir.connection-request-timeout=2s");
    }

    @Test
    void retrievesAndMapsSyntheticLocationWithExactlyOneGet(CapturedOutput output) {
        contextRunner().run(context -> {
            var location = context.getBean(FhirLocationClient.class).readLocation("synthetic-loc");
            var dto = context.getBean(LocationMapper.class).toDto(location);
            assertThat(dto.id()).isEqualTo("synthetic-loc");
            assertThat(dto.versionId()).isEqualTo("4");
            assertThat(dto.status()).isEqualTo("active");
            assertThat(dto.operationalStatus().code()).isEqualTo("U");
            assertThat(dto.name()).isEqualTo("Synthetic Ward");
            assertThat(dto.mode()).isEqualTo("instance");
            assertThat(dto.telecoms()).singleElement().satisfies(telecom -> assertThat(telecom.system()).isEqualTo("phone"));
            assertThat(dto.address().lines()).containsExactly("Synthetic street 2");
            assertThat(dto.position()).isEqualTo(new Position(new BigDecimal("18.0686000"), new BigDecimal("59.3293000"), null));
            assertThat(dto.managingOrganization().reference()).isEqualTo("Organization/synthetic-org");
            assertThat(dto.managingOrganization().display()).isEqualTo("Synthetic Clinic");
            assertThat(dto.partOf().reference()).isEqualTo("Location/synthetic-building");
            assertThat(location.getHoursOfOperationFirstRep().getAllDay()).isTrue();
            assertThat(requests).containsExactly("GET /fhir/Location/synthetic-loc");
        });
        assertThat(output.getAll()).doesNotContain("FHIR request failed");
    }

    @Test
    void rejectsInvalidIdsBeforeSendingRequests() {
        contextRunner().run(context -> {
            FhirLocationClient client = context.getBean(FhirLocationClient.class);
            for (String id : new String[] {null, "", " ", " id", "Location/123", "https://example.test/Location/1",
                    "../Location/1", "id?x=1", "id#fragment", "id_name", "ä", "x".repeat(65)}) {
                assertThatThrownBy(() -> client.readLocation(id))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("A valid logical FHIR Location ID is required");
            }
            assertThat(requests).isEmpty();
        });
    }

    @ParameterizedTest
    @CsvSource({"missing, 404, CLIENT_ERROR", "gone, 410, CLIENT_ERROR", "failure, 500, SERVER_ERROR",
            "unavailable, 503, UNAVAILABLE"})
    void preservesTranslatedExternalErrors(String id, int status, FhirServerException.Type type) {
        contextRunner().run(context -> {
            assertThatThrownBy(() -> context.getBean(FhirLocationClient.class).readLocation(id))
                    .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                        assertThat(failure.operation()).isEqualTo("location-read");
                        assertThat(failure.httpStatus()).hasValue(status);
                        assertThat(failure.type()).isEqualTo(type);
                        assertThat(failure.issues()).containsExactly("error:processing");
                        assertThat(failure.getCause()).isNull();
                        assertThat(failure.getMessage()).doesNotContain("SENSITIVE", "/Location/", "127.0.0.1");
                    });
            assertThat(requests).containsExactly("GET /fhir/Location/" + id);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-type", "broken"})
    void malformedOrWrongResourcesAreInvalidResponses(String id) {
        contextRunner().run(context -> {
            assertThatThrownBy(() -> context.getBean(FhirLocationClient.class).readLocation(id))
                    .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                        assertThat(failure.operation()).isEqualTo("location-read");
                        assertThat(failure.type()).isEqualTo(FhirServerException.Type.INVALID_RESPONSE);
                        assertThat(failure.getCause()).isNull();
                    });
            assertThat(requests).containsExactly("GET /fhir/Location/" + id);
        });
    }

    @Test
    void failureIsLoggedOnceWithoutIdentifiersUrlsOrServerDetails(CapturedOutput output) {
        contextRunner().run(context -> assertThatThrownBy(() ->
                context.getBean(FhirLocationClient.class).readLocation("secret-loc-77-missing"))
                .isInstanceOf(FhirServerException.class));

        assertThat(output.getAll())
                .contains("FHIR request failed: operation=location-read, type=CLIENT_ERROR, httpStatus=404, "
                        + "issues=[error:processing], cause=ResourceNotFoundException, durationMs=")
                .doesNotContain("secret-loc-77")
                .doesNotContain("SENSITIVE")
                .doesNotContain("127.0.0.1")
                .doesNotContain("/Location/");
        assertThat(output.getAll().split("FHIR request failed", -1)).hasSize(2);
    }
}
