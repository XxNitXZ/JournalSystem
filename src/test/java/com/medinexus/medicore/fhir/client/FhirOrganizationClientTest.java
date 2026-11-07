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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.medinexus.medicore.fhir.config.FhirClientConfig;
import com.medinexus.medicore.fhir.mapper.OrganizationMapper;
import com.sun.net.httpserver.HttpServer;

/** Organization reads against a local HTTP server with synthetic data only. */
@ExtendWith(OutputCaptureExtension.class)
class FhirOrganizationClientTest {

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
                body = "{\"resourceType\":\"Patient\",\"id\":\"wrong-type\"}";
            } else if (path.endsWith("/broken")) {
                body = "{\"resourceType\":\"Organization\",\"name\":";
            } else {
                body = """
                        {"resourceType":"Organization","id":"synthetic-org","meta":{"versionId":"4"},
                         "identifier":[{"system":"urn:synthetic:org","value":"ORG-1"}],"active":true,
                         "name":"Synthetic Clinic","alias":["Clinic A"],
                         "telecom":[{"system":"phone","value":"+46000000000","use":"work"}],
                         "address":[{"use":"work","line":["Synthetic street 1"],"city":"Synthetic City"}],
                         "partOf":{"reference":"Organization/synthetic-parent"},
                         "contact":[{"name":{"text":"Synthetic Contact Person"}}]}
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
                        FhirOrganizationClient.class, OrganizationMapper.class)
                .withPropertyValues("medinexus.fhir.base-url=http://127.0.0.1:" + server.getAddress().getPort() + "/fhir",
                        "medinexus.fhir.connect-timeout=2s", "medinexus.fhir.socket-timeout=5s",
                        "medinexus.fhir.connection-request-timeout=2s");
    }

    @Test
    void retrievesAndMapsSyntheticOrganizationWithExactlyOneGet(CapturedOutput output) {
        contextRunner().run(context -> {
            var organization = context.getBean(FhirOrganizationClient.class).readOrganization("synthetic-org");
            var dto = context.getBean(OrganizationMapper.class).toDto(organization);
            assertThat(dto.id()).isEqualTo("synthetic-org");
            assertThat(dto.versionId()).isEqualTo("4");
            assertThat(dto.name()).isEqualTo("Synthetic Clinic");
            assertThat(dto.active()).isTrue();
            assertThat(dto.identifiers()).singleElement().satisfies(identifier -> assertThat(identifier.value()).isEqualTo("ORG-1"));
            assertThat(dto.telecoms()).singleElement().satisfies(telecom -> assertThat(telecom.system()).isEqualTo("phone"));
            assertThat(dto.addresses().getFirst().lines()).containsExactly("Synthetic street 1");
            assertThat(dto.partOf().reference()).isEqualTo("Organization/synthetic-parent");
            assertThat(organization.getContactFirstRep().getName().getText()).isEqualTo("Synthetic Contact Person");
            assertThat(requests).containsExactly("GET /fhir/Organization/synthetic-org");
        });
        assertThat(output.getAll()).doesNotContain("FHIR request failed");
    }

    @Test
    void rejectsInvalidIdsBeforeSendingRequests() {
        contextRunner().run(context -> {
            FhirOrganizationClient client = context.getBean(FhirOrganizationClient.class);
            for (String id : new String[] {null, "", " ", " id", "Organization/123", "https://example.test/Organization/1",
                    "../Organization/1", "id?x=1", "id#fragment", "id_name", "ä", "x".repeat(65)}) {
                assertThatThrownBy(() -> client.readOrganization(id))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("A valid logical FHIR Organization ID is required");
            }
            assertThat(requests).isEmpty();
        });
    }

    @ParameterizedTest
    @CsvSource({"missing, 404, CLIENT_ERROR", "gone, 410, CLIENT_ERROR", "failure, 500, SERVER_ERROR",
            "unavailable, 503, UNAVAILABLE"})
    void preservesTranslatedExternalErrors(String id, int status, FhirServerException.Type type) {
        contextRunner().run(context -> {
            assertThatThrownBy(() -> context.getBean(FhirOrganizationClient.class).readOrganization(id))
                    .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                        assertThat(failure.operation()).isEqualTo("organization-read");
                        assertThat(failure.httpStatus()).hasValue(status);
                        assertThat(failure.type()).isEqualTo(type);
                        assertThat(failure.issues()).containsExactly("error:processing");
                        assertThat(failure.getCause()).isNull();
                        assertThat(failure.getMessage()).doesNotContain("SENSITIVE", "/Organization/", "127.0.0.1");
                    });
            assertThat(requests).containsExactly("GET /fhir/Organization/" + id);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-type", "broken"})
    void malformedOrWrongResourcesAreInvalidResponses(String id) {
        contextRunner().run(context -> {
            assertThatThrownBy(() -> context.getBean(FhirOrganizationClient.class).readOrganization(id))
                    .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                        assertThat(failure.operation()).isEqualTo("organization-read");
                        assertThat(failure.type()).isEqualTo(FhirServerException.Type.INVALID_RESPONSE);
                        assertThat(failure.getCause()).isNull();
                    });
            assertThat(requests).containsExactly("GET /fhir/Organization/" + id);
        });
    }

    @Test
    void failureIsLoggedOnceWithoutIdentifiersUrlsOrServerDetails(CapturedOutput output) {
        contextRunner().run(context -> assertThatThrownBy(() ->
                context.getBean(FhirOrganizationClient.class).readOrganization("secret-org-77-missing"))
                .isInstanceOf(FhirServerException.class));

        assertThat(output.getAll())
                .contains("FHIR request failed: operation=organization-read, type=CLIENT_ERROR, httpStatus=404, "
                        + "issues=[error:processing], cause=ResourceNotFoundException, durationMs=")
                .doesNotContain("secret-org-77")
                .doesNotContain("SENSITIVE")
                .doesNotContain("127.0.0.1")
                .doesNotContain("/Organization/");
        assertThat(output.getAll().split("FHIR request failed", -1)).hasSize(2);
    }
}
