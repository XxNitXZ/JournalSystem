package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
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
import com.medinexus.medicore.fhir.mapper.OrganizationMapper;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Identifier;
import com.medinexus.medicore.organization.dto.OrganizationWriteRequest;
import com.medinexus.medicore.organization.dto.OrganizationWriteRequest.Address;
import com.medinexus.medicore.organization.dto.OrganizationWriteRequest.AddressUse;
import com.medinexus.medicore.organization.dto.OrganizationWriteRequest.ContactPoint;
import com.medinexus.medicore.organization.dto.OrganizationWriteRequest.ContactPointSystem;
import com.medinexus.medicore.organization.dto.OrganizationWriteRequest.ContactPointUse;
import com.sun.net.httpserver.HttpServer;

import ca.uhn.fhir.rest.client.api.IGenericClient;

/** Creation against a local HTTP server only; nothing is ever sent to an external FHIR server. */
@ExtendWith(OutputCaptureExtension.class)
class FhirOrganizationCreateTest {

    private HttpServer server;
    private String base;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> contentTypes = new CopyOnWriteArrayList<>();
    private int status = 201;
    private String location = "/fhir/Organization/server-123/_history/1";
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
                FhirRequestExecutor.class, FhirOrganizationClient.class, OrganizationMapper.class)
                .withPropertyValues("medinexus.fhir.base-url=" + base, "medinexus.fhir.connect-timeout=2s",
                        "medinexus.fhir.socket-timeout=3s", "medinexus.fhir.connection-request-timeout=2s");
    }

    private static OrganizationWriteRequest named(String name) {
        return new OrganizationWriteRequest(null, null, null, name, null, null, null, null);
    }

    @Test
    void createsExactR4PayloadAndReturnsAssignedIdOnTheLocalServerOnly() {
        var request = new OrganizationWriteRequest(List.of(new Identifier("urn:synthetic:org", "ORG-1")), true, null,
                "Synthetic Clinic", List.of("Clinic A"),
                List.of(new ContactPoint(ContactPointSystem.PHONE, "+46000000000", ContactPointUse.WORK, 1)),
                List.of(new Address(AddressUse.WORK, null, null, List.of("Synthetic street 1"), "Synthetic City",
                        null, null, null, "SE")), "synthetic-parent");
        context().run(ctx -> {
            assertThat(ctx.getBean(IGenericClient.class).getServerBase()).startsWith("http://127.0.0.1:");
            assertThat(ctx.getBean(FhirOrganizationClient.class).createOrganization(request)).isEqualTo("server-123");
            assertThat(requests).containsExactly("POST /fhir/Organization");
            assertThat(contentTypes.getFirst()).startsWith("application/fhir+json");
            assertThat(bodies.getFirst()).isEqualTo("{\"resourceType\":\"Organization\","
                    + "\"identifier\":[{\"system\":\"urn:synthetic:org\",\"value\":\"ORG-1\"}],\"active\":true,"
                    + "\"name\":\"Synthetic Clinic\",\"alias\":[\"Clinic A\"],"
                    + "\"telecom\":[{\"system\":\"phone\",\"value\":\"+46000000000\",\"use\":\"work\",\"rank\":1}],"
                    + "\"address\":[{\"use\":\"work\",\"line\":[\"Synthetic street 1\"],\"city\":\"Synthetic City\",\"country\":\"SE\"}],"
                    + "\"partOf\":{\"reference\":\"Organization/synthetic-parent\"}}");
        });
    }

    @Test
    void createsMinimalOrganizationWithoutInventedFields() {
        context().run(ctx -> {
            assertThat(ctx.getBean(FhirOrganizationClient.class).createOrganization(named("Synthetic Clinic"))).isEqualTo("server-123");
            assertThat(bodies).containsExactly("{\"resourceType\":\"Organization\",\"name\":\"Synthetic Clinic\"}");
        });
    }

    @Test
    void invalidRequestsSendNothing() {
        var requestsToReject = Arrays.asList(null, named(null), named(" "),
                new OrganizationWriteRequest(List.of(new Identifier("relative", "1")), null, null, "N", null, null, null, null),
                new OrganizationWriteRequest(null, null, null, "N", null,
                        List.of(new ContactPoint(null, "+46000000000", null, null)), null, null),
                new OrganizationWriteRequest(null, null, null, "N", null, null, null, "Organization/parent"));
        context().run(ctx -> {
            var client = ctx.getBean(FhirOrganizationClient.class);
            for (OrganizationWriteRequest request : requestsToReject) {
                assertThatThrownBy(() -> client.createOrganization(request))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Invalid Organization write request");
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
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirOrganizationClient.class)
                .createOrganization(named("Synthetic Clinic")))
                .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                    assertThat(failure.operation()).isEqualTo("organization-create");
                    assertThat(failure.type()).isEqualTo(type);
                    assertThat(failure.httpStatus()).hasValue(failureStatus);
                    assertThat(failure.issues()).containsExactly("error:invalid");
                    assertThat(failure.getMessage()).doesNotContain("SENSITIVE", "Synthetic Clinic", "127.0.0.1");
                }));
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"/fhir/Patient/server-123/_history/1", "/fhir/Organization/bad_id/_history/1"})
    void responsesThatDoNotConfirmAValidOrganizationIdAreInvalid(String responseLocation) {
        location = responseLocation;
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirOrganizationClient.class)
                .createOrganization(named("Synthetic Clinic")))
                .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                    assertThat(failure.operation()).isEqualTo("organization-create");
                    assertThat(failure.type()).isEqualTo(FhirServerException.Type.INVALID_RESPONSE);
                }));
        assertThat(requests).hasSize(1);
    }

    @Test
    void successWithoutCreatedStatusIsInvalid() {
        status = 200;
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirOrganizationClient.class)
                .createOrganization(named("Synthetic Clinic")))
                .isInstanceOfSatisfying(FhirServerException.class,
                        failure -> assertThat(failure.type()).isEqualTo(FhirServerException.Type.INVALID_RESPONSE)));
        assertThat(requests).hasSize(1);
    }

    @Test
    void failureLogContainsNoOrganizationDataOrUrls(CapturedOutput output) {
        status = 422;
        location = null;
        responseBody = """
                {"resourceType":"OperationOutcome","issue":[{"severity":"error","code":"invalid","diagnostics":"SENSITIVE detail"}]}
                """;
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirOrganizationClient.class)
                .createOrganization(named("Secret Synthetic Clinic"))).isInstanceOf(FhirServerException.class));
        assertThat(output.getAll())
                .contains("FHIR request failed: operation=organization-create, type=CLIENT_ERROR, httpStatus=422, "
                        + "issues=[error:invalid], cause=UnprocessableEntityException, durationMs=")
                .doesNotContain("Secret Synthetic Clinic", "SENSITIVE", "127.0.0.1", "/Organization");
    }
}
