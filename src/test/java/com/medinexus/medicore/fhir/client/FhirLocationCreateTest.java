package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.medinexus.medicore.fhir.config.FhirClientConfig;
import com.medinexus.medicore.fhir.mapper.LocationMapper;
import com.medinexus.medicore.location.dto.LocationDto.Position;
import com.medinexus.medicore.location.dto.LocationWriteRequest;
import com.medinexus.medicore.location.dto.LocationWriteRequest.Mode;
import com.medinexus.medicore.location.dto.LocationWriteRequest.Status;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Identifier;
import com.sun.net.httpserver.HttpServer;

import ca.uhn.fhir.rest.client.api.IGenericClient;

/** Creation against a local HTTP server only; nothing is ever sent to an external FHIR server. */
@ExtendWith(OutputCaptureExtension.class)
class FhirLocationCreateTest {

    private HttpServer server;
    private String base;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> contentTypes = new CopyOnWriteArrayList<>();
    private int status = 201;
    private String location = "/fhir/Location/server-123/_history/1";
    private String responseBody = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            contentTypes.add(String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")));
            exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
            if (location != null) exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + location);
            if (responseBody.isEmpty()) {
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            } else {
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/fhir";
    }

    @AfterEach
    void stop() { server.stop(0); }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(FhirClientConfig.class,
                FhirRequestExecutor.class, FhirLocationClient.class, LocationMapper.class)
                .withPropertyValues("medinexus.fhir.base-url=" + base, "medinexus.fhir.connect-timeout=2s",
                        "medinexus.fhir.socket-timeout=3s", "medinexus.fhir.connection-request-timeout=2s");
    }

    private static LocationWriteRequest named(String name) {
        return new LocationWriteRequest(null, null, name, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void createsExactR4PayloadWithReferencesAndReturnsAssignedIdOnTheLocalServerOnly() {
        var request = new LocationWriteRequest(List.of(new Identifier("urn:synthetic:location", "LOC-1")), Status.ACTIVE,
                "Synthetic Ward", null, "Synthetic ward description", Mode.INSTANCE, null, null, null, null,
                new Position(new BigDecimal("18.0686000"), new BigDecimal("-59.3293"), new BigDecimal("12.0")),
                "synthetic-org", "synthetic-building");
        context().run(ctx -> {
            assertThat(ctx.getBean(IGenericClient.class).getServerBase()).startsWith("http://127.0.0.1:");
            assertThat(ctx.getBean(FhirLocationClient.class).createLocation(request)).isEqualTo("server-123");
            assertThat(requests).containsExactly("POST /fhir/Location");
            assertThat(contentTypes.getFirst()).startsWith("application/fhir+json");
            assertThat(bodies.getFirst()).isEqualTo("{\"resourceType\":\"Location\","
                    + "\"identifier\":[{\"system\":\"urn:synthetic:location\",\"value\":\"LOC-1\"}],\"status\":\"active\","
                    + "\"name\":\"Synthetic Ward\",\"description\":\"Synthetic ward description\",\"mode\":\"instance\","
                    + "\"position\":{\"longitude\":18.0686000,\"latitude\":-59.3293,\"altitude\":12.0},"
                    + "\"managingOrganization\":{\"reference\":\"Organization/synthetic-org\"},"
                    + "\"partOf\":{\"reference\":\"Location/synthetic-building\"}}");
        });
    }

    @Test
    void createsEmptyAndMinimalLocationsWithoutInventedFields() {
        context().run(ctx -> {
            var client = ctx.getBean(FhirLocationClient.class);
            assertThat(client.createLocation(named("Synthetic Ward"))).isEqualTo("server-123");
            assertThat(client.createLocation(new LocationWriteRequest(null, null, null, null, null, null, null, null,
                    null, null, null, null, null))).isEqualTo("server-123");
            assertThat(bodies).containsExactly("{\"resourceType\":\"Location\",\"name\":\"Synthetic Ward\"}",
                    "{\"resourceType\":\"Location\"}");
        });
    }

    @Test
    void invalidRequestsSendNothing() {
        var requestsToReject = Arrays.asList(null, named(" "),
                new LocationWriteRequest(List.of(new Identifier("relative", "1")), null, null, null, null, null, null,
                        null, null, null, null, null, null),
                new LocationWriteRequest(null, null, "N", null, null, null, null, null, null, null,
                        new Position(new BigDecimal("181"), BigDecimal.ZERO, null), null, null),
                new LocationWriteRequest(null, null, "N", null, null, null, null, null, null, null,
                        new Position(BigDecimal.ZERO, new BigDecimal("-90.01"), null), null, null),
                new LocationWriteRequest(null, null, "N", null, null, null, null, null, null, null,
                        new Position(null, BigDecimal.ZERO, null), null, null),
                new LocationWriteRequest(null, null, "N", null, null, null, null, null, null, null, null,
                        "Organization/synthetic-org", null),
                new LocationWriteRequest(null, null, "N", null, null, null, null, null, null, null, null,
                        null, "https://example.test/fhir/Location/1"));
        context().run(ctx -> {
            var client = ctx.getBean(FhirLocationClient.class);
            for (LocationWriteRequest request : requestsToReject) {
                assertThatThrownBy(() -> client.createLocation(request))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Invalid Location write request");
            }
            assertThat(requests).isEmpty();
        });
    }

    @ParameterizedTest
    @CsvSource({"400, CLIENT_ERROR", "409, CLIENT_ERROR", "422, CLIENT_ERROR", "500, SERVER_ERROR", "503, UNAVAILABLE"})
    void translatesServerErrorsWithoutRetrying(int failureStatus, FhirServerException.Type type) {
        status = failureStatus;
        location = null;
        responseBody = """
                {"resourceType":"OperationOutcome","issue":[{"severity":"error","code":"invalid","diagnostics":"SENSITIVE detail"}]}
                """;
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirLocationClient.class)
                .createLocation(named("Synthetic Ward")))
                .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                    assertThat(failure.operation()).isEqualTo("location-create");
                    assertThat(failure.type()).isEqualTo(type);
                    assertThat(failure.httpStatus()).hasValue(failureStatus);
                    assertThat(failure.issues()).containsExactly("error:invalid");
                    assertThat(failure.getMessage()).doesNotContain("SENSITIVE", "Synthetic Ward", "127.0.0.1");
                }));
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"/fhir/Organization/server-123/_history/1", "/fhir/Location/bad_id/_history/1"})
    void responsesThatDoNotConfirmAValidLocationIdAreInvalid(String responseLocation) {
        location = responseLocation;
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirLocationClient.class)
                .createLocation(named("Synthetic Ward")))
                .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                    assertThat(failure.operation()).isEqualTo("location-create");
                    assertThat(failure.type()).isEqualTo(FhirServerException.Type.INVALID_RESPONSE);
                }));
        assertThat(requests).hasSize(1);
    }

    @Test
    void successWithoutCreatedStatusIsInvalid() {
        status = 200;
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirLocationClient.class)
                .createLocation(named("Synthetic Ward")))
                .isInstanceOfSatisfying(FhirServerException.class,
                        failure -> assertThat(failure.type()).isEqualTo(FhirServerException.Type.INVALID_RESPONSE)));
        assertThat(requests).hasSize(1);
    }

    @Test
    void failureLogContainsNoLocationDataOrUrls(CapturedOutput output) {
        status = 422;
        location = null;
        responseBody = """
                {"resourceType":"OperationOutcome","issue":[{"severity":"error","code":"invalid","diagnostics":"SENSITIVE detail"}]}
                """;
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirLocationClient.class)
                .createLocation(named("Secret Synthetic Ward"))).isInstanceOf(FhirServerException.class));
        assertThat(output.getAll())
                .contains("FHIR request failed: operation=location-create, type=CLIENT_ERROR, httpStatus=422, "
                        + "issues=[error:invalid], cause=UnprocessableEntityException, durationMs=")
                .doesNotContain("Secret Synthetic Ward", "SENSITIVE", "127.0.0.1", "/Location");
    }
}
