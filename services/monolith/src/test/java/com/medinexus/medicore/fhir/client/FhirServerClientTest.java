package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.Enumerations.FHIRVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.medinexus.medicore.fhir.client.FhirServerException.Type;
import com.medinexus.medicore.fhir.config.FhirClientConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * FhirServerClient against a local HTTP server on 127.0.0.1 that answers like a
 * FHIR server (or like a broken one). No external server is contacted and
 * nothing is written.
 */
@ExtendWith(OutputCaptureExtension.class)
class FhirServerClientTest {

    private static final String CAPABILITY_STATEMENT = """
            {
              "resourceType": "CapabilityStatement",
              "status": "active",
              "date": "2026-01-01",
              "kind": "instance",
              "fhirVersion": "4.0.1",
              "format": ["application/fhir+json"],
              "software": { "name": "Local Test FHIR Server", "version": "1.2.3" },
              "rest": [ {
                "mode": "server",
                "resource": [ { "type": "Patient", "interaction": [ { "code": "read" } ] } ]
              } ]
            }
            """;

    /** Server-supplied text that must never reach exceptions or logs. */
    private static final String MARKER = "MARKER-SECRET Jane Doe 19900101-1234";

    private static final String OPERATION_OUTCOME = """
            {"resourceType": "OperationOutcome", "issue": [
              {"severity": "error", "code": "processing", "diagnostics": "%s"}]}
            """.formatted(MARKER);

    private final List<String> receivedRequests = new CopyOnWriteArrayList<>();
    private ExecutorService executor;
    private HttpServer server;
    private String serverAddress;

    @BeforeEach
    void startLocalFhirServer() throws IOException {
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        serverAddress = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopLocalFhirServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    /** The first path segment selects the behaviour, e.g. /status/404/fhir/metadata. */
    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        receivedRequests.add(exchange.getRequestMethod() + " " + path);
        String[] segments = path.split("/");
        String scenario = segments.length > 1 ? segments[1] : "";

        int status = 200;
        String contentType = "application/fhir+json;charset=UTF-8";
        String body = CAPABILITY_STATEMENT;
        switch (scenario) {
            case "fhir" -> {
                // regular, successful server
            }
            case "status" -> {
                status = Integer.parseInt(segments[2]);
                body = OPERATION_OUTCOME;
            }
            case "plain500" -> {
                status = 500;
                contentType = "text/plain;charset=UTF-8";
                body = MARKER + " java.lang.NullPointerException";
            }
            case "invalid" -> body = MARKER + " this is not JSON";
            case "html" -> {
                contentType = "text/html;charset=UTF-8";
                body = "<html>" + MARKER + " proxy login page</html>";
            }
            case "patient" -> body = "{\"resourceType\": \"Patient\", \"name\": [{\"family\": \"" + MARKER + "\"}]}";
            case "slow" -> {
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            default -> {
                status = 404;
                body = "{}";
            }
        }

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        try (OutputStream output = exchange.getResponseBody()) {
            exchange.sendResponseHeaders(status, bytes.length);
            output.write(bytes);
        } catch (IOException clientGone) {
            // The client may have timed out already.
        }
    }

    private ApplicationContextRunner contextRunner(String baseUrl, String socketTimeout) {
        return new ApplicationContextRunner()
                .withUserConfiguration(FhirClientConfig.class, FhirServerClient.class, FhirRequestExecutor.class)
                .withPropertyValues(
                        "medinexus.fhir.base-url=" + baseUrl,
                        "medinexus.fhir.connect-timeout=2s",
                        "medinexus.fhir.socket-timeout=" + socketTimeout,
                        "medinexus.fhir.connection-request-timeout=2s");
    }

    private void assertFetchFails(String baseUrl, String socketTimeout, Consumer<FhirServerException> assertions) {
        contextRunner(baseUrl, socketTimeout).run(context ->
                assertThatThrownBy(() -> context.getBean(FhirServerClient.class).fetchCapabilityStatement())
                        .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                            assertThat(failure.operation()).isEqualTo("capabilities");
                            assertThat(failure.getCause()).isNull();
                            assertThat(failure.getMessage())
                                    .doesNotContain("MARKER")
                                    .doesNotContain(serverAddress)
                                    .doesNotContain("/metadata");
                            assertions.accept(failure);
                        }));
    }

    @Test
    void initializationSendsNoRequests() {
        contextRunner(serverAddress + "/fhir", "5s").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(FhirServerClient.class);
            assertThat(receivedRequests).isEmpty();
        });
    }

    @Test
    void fetchCapabilityStatementRequestsAndParsesMetadata(CapturedOutput output) {
        contextRunner(serverAddress + "/fhir", "5s").run(context -> {
            CapabilityStatement capabilityStatement =
                    context.getBean(FhirServerClient.class).fetchCapabilityStatement();

            assertThat(receivedRequests).containsExactly("GET /fhir/metadata");
            assertThat(capabilityStatement.getFhirVersion()).isEqualTo(FHIRVersion._4_0_1);
            assertThat(capabilityStatement.getSoftware().getName()).isEqualTo("Local Test FHIR Server");
            assertThat(capabilityStatement.getSoftware().getVersion()).isEqualTo("1.2.3");
            assertThat(capabilityStatement.getRestFirstRep().getResource())
                    .extracting(resource -> resource.getType())
                    .containsExactly("Patient");
        });
        assertThat(output).doesNotContain("FHIR request failed");
    }

    @ParameterizedTest
    @CsvSource({
            "401, CLIENT_ERROR, AuthenticationException",
            "403, CLIENT_ERROR, ForbiddenOperationException",
            "404, CLIENT_ERROR, ResourceNotFoundException",
            "422, CLIENT_ERROR, UnprocessableEntityException",
            "500, SERVER_ERROR, InternalErrorException",
            "502, UNAVAILABLE, UnclassifiedServerFailureException",
            "503, UNAVAILABLE, UnclassifiedServerFailureException",
            "504, UNAVAILABLE, UnclassifiedServerFailureException"
    })
    void httpErrorsWithOperationOutcomeAreTranslated(int status, Type expectedType, String expectedCause) {
        assertFetchFails(serverAddress + "/status/" + status + "/fhir", "5s", failure -> {
            assertThat(failure.type()).isEqualTo(expectedType);
            assertThat(failure.httpStatus()).hasValue(status);
            assertThat(failure.issues()).containsExactly("error:processing");
            assertThat(failure.causeType()).isEqualTo(expectedCause);
        });
    }

    @Test
    void serverErrorWithoutOperationOutcomeIsTranslated() {
        assertFetchFails(serverAddress + "/plain500/fhir", "5s", failure -> {
            assertThat(failure.type()).isEqualTo(Type.SERVER_ERROR);
            assertThat(failure.httpStatus()).hasValue(500);
            assertThat(failure.issues()).isEmpty();
        });
    }

    @Test
    void readTimeoutIsTranslated() {
        long start = System.nanoTime();
        assertFetchFails(serverAddress + "/slow/fhir", "300ms", failure -> {
            assertThat(failure.type()).isEqualTo(Type.TIMEOUT);
            assertThat(failure.httpStatus()).isEmpty();
            assertThat(failure.causeType()).isEqualTo("SocketTimeoutException");
        });
        assertThat((System.nanoTime() - start) / 1_000_000).as("ends at the socket timeout").isLessThan(1_800);
    }

    @Test
    void refusedConnectionIsTranslated() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        assertFetchFails("http://127.0.0.1:" + closedPort + "/fhir", "5s", failure -> {
            assertThat(failure.type()).isEqualTo(Type.UNAVAILABLE);
            assertThat(failure.httpStatus()).isEmpty();
            assertThat(failure.causeType()).isEqualTo("ConnectException");
        });
    }

    @ParameterizedTest
    @CsvSource({"invalid, -1", "patient, -1", "html, 200"})
    void invalidOrNonFhirResponsesAreTranslated(String scenario, int expectedStatus) {
        assertFetchFails(serverAddress + "/" + scenario + "/fhir", "5s", failure -> {
            assertThat(failure.type()).isEqualTo(Type.INVALID_RESPONSE);
            if (expectedStatus < 0) {
                assertThat(failure.httpStatus()).isEmpty();
            } else {
                assertThat(failure.httpStatus()).hasValue(expectedStatus);
            }
        });
    }

    @Test
    void failureIsLoggedOnceWithSafeMetadataOnly(CapturedOutput output) {
        assertFetchFails(serverAddress + "/status/404/fhir", "5s", failure -> { });
        assertFetchFails(serverAddress + "/slow/fhir", "300ms", failure -> { });

        assertThat(output.getAll())
                .contains("FHIR request failed: operation=capabilities, type=CLIENT_ERROR, httpStatus=404, "
                        + "issues=[error:processing], cause=ResourceNotFoundException, durationMs=")
                .contains("FHIR request failed: operation=capabilities, type=TIMEOUT, httpStatus=-, "
                        + "issues=[], cause=SocketTimeoutException, durationMs=")
                .doesNotContain("MARKER")
                .doesNotContain(serverAddress)
                .doesNotContain("/metadata")
                .doesNotContain("Read timed out")
                .doesNotContain("at ca.uhn.fhir");
        assertThat(output.getAll().split("FHIR request failed", -1)).hasSize(3);
    }
}
