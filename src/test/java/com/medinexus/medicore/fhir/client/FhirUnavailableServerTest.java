package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.ConnectionPoolTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;

import com.medinexus.medicore.fhir.config.FhirClientConfig;
import com.medinexus.medicore.fhir.mapper.ConditionMapper;
import com.medinexus.medicore.fhir.mapper.EncounterMapper;
import com.medinexus.medicore.fhir.mapper.LocationMapper;
import com.medinexus.medicore.fhir.mapper.ObservationMapper;
import com.medinexus.medicore.fhir.mapper.OrganizationMapper;
import com.medinexus.medicore.fhir.support.LocalFhirServer;
import com.medinexus.medicore.location.dto.LocationWriteRequest;
import com.medinexus.medicore.medicalrecord.dto.CreateConditionRequest;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest;
import com.medinexus.medicore.medicalrecord.dto.CreateObservationRequest;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Coding;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.medinexus.medicore.organization.dto.OrganizationWriteRequest;
import com.medinexus.medicore.patient.dto.CreatePatientRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ca.uhn.fhir.rest.client.exceptions.FhirClientConnectionException;

/**
 * NIT-138: every FHIR client operation against an unavailable, slow or failing server. Local servers on
 * 127.0.0.1 and synthetic data only; nothing is sent to an external FHIR server and no database is used.
 *
 * <p>All identifiers and names below contain "secret"/"Secret" so that the tests can prove that neither
 * exception messages nor log output contain IDs, names, URLs, ports or server diagnostics.
 */
@ExtendWith(OutputCaptureExtension.class)
class FhirUnavailableServerTest {

    private static final int SOCKET_TIMEOUT_MILLIS = 300;
    private static final int HANG_MILLIS = 3_000;
    private static final String SECRET_PATIENT = "secret-patient-77";
    /** Synthetic test personnummer (Skatteverket's test series); must never appear in messages or logs. */
    private static final String SYNTHETIC_PERSONNUMMER = "199001012385";

    /** One client operation, its fixed operation name and the HTTP requests it sends first. */
    record Operation(String name, String firstMethod, boolean write, Consumer<ApplicationContext> call) {
    }

    private enum Behaviour { OK, HANG, STATUS, DROP }

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private ExecutorService executor;
    private HttpServer server;
    private LocalFhirServer localFhir;
    private Behaviour reads = Behaviour.OK;
    private Behaviour writes = Behaviour.OK;
    private int status = 503;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
        if (localFhir != null) localFhir.close();
    }

    private static Concept code() {
        return new Concept("Secret Synthetic code", List.of(new Coding("urn:synthetic:code", null, "secret-code", null, null)));
    }

    static Stream<Named<Operation>> operations() {
        return Stream.of(
                op("patient-read", "GET", false, ctx -> ctx.getBean(FhirPatientClient.class).readPatient(SECRET_PATIENT)),
                op("patient-create", "POST", true, ctx -> ctx.getBean(FhirPatientClient.class).createPatient(
                        new CreatePatientRequest(SYNTHETIC_PERSONNUMMER, "Secretsson", List.of("Secret"),
                                CreatePatientRequest.Gender.UNKNOWN, java.time.LocalDate.of(1990, 1, 1)))),
                op("patient-search", "POST", false, ctx -> ctx.getBean(FhirPatientClient.class).searchPatients(
                        com.medinexus.medicore.patient.dto.PatientSearchCriteria.parse("Secretsson"),
                        List.of(SECRET_PATIENT))),
                op("practitioner-read", "GET", false, ctx -> ctx.getBean(FhirPractitionerClient.class)
                        .readPractitioner("secret-practitioner-77")),
                op("observation-read", "GET", false, ctx -> ctx.getBean(FhirObservationClient.class)
                        .readObservation("secret-observation-77")),
                op("observation-create", "POST", true, ctx -> ctx.getBean(FhirObservationClient.class).createObservation(
                        new CreateObservationRequest(SECRET_PATIENT, CreateObservationRequest.Status.FINAL, code(),
                                new CreateObservationRequest.QuantityValue(new BigDecimal("72"), "/min",
                                        "http://unitsofmeasure.org", "/min", null), null))),
                op("observation-search", "GET", false, ctx -> ctx.getBean(FhirObservationClient.class).findByPatientId(SECRET_PATIENT)),
                op("condition-read", "GET", false, ctx -> ctx.getBean(FhirConditionClient.class).readCondition("secret-condition-77")),
                op("condition-create", "POST", true, ctx -> ctx.getBean(FhirConditionClient.class).createCondition(
                        new CreateConditionRequest(SECRET_PATIENT, code(), CreateConditionRequest.ClinicalStatus.ACTIVE,
                                CreateConditionRequest.VerificationStatus.CONFIRMED, List.of(), null, null, null))),
                op("condition-search", "GET", false, ctx -> ctx.getBean(FhirConditionClient.class).findByPatientId(SECRET_PATIENT)),
                op("encounter-read", "GET", false, ctx -> ctx.getBean(FhirEncounterClient.class).readEncounter("secret-encounter-77")),
                op("encounter-create", "POST", true, ctx -> ctx.getBean(FhirEncounterClient.class).createEncounter(
                        new CreateEncounterRequest(SECRET_PATIENT, CreateEncounterRequest.Status.FINISHED,
                                CreateEncounterRequest.EncounterClass.AMB, null, null, null, null, null, null,
                                List.of(new CreateEncounterRequest.Participant("secret-practitioner-77", null))))),
                op("encounter-search", "GET", false, ctx -> ctx.getBean(FhirEncounterClient.class).findByPatientId(SECRET_PATIENT).encounters()),
                op("organization-read", "GET", false, ctx -> ctx.getBean(FhirOrganizationClient.class)
                        .readOrganization("secret-org-77")),
                op("organization-create", "POST", true, ctx -> ctx.getBean(FhirOrganizationClient.class)
                        .createOrganization(organization())),
                op("organization-update", "GET", true, ctx -> ctx.getBean(FhirOrganizationClient.class)
                        .updateOrganization("secret-org-77", "1", organization())),
                op("location-read", "GET", false, ctx -> ctx.getBean(FhirLocationClient.class).readLocation("secret-loc-77")),
                op("location-create", "POST", true, ctx -> ctx.getBean(FhirLocationClient.class).createLocation(location())),
                op("location-update", "GET", true, ctx -> ctx.getBean(FhirLocationClient.class)
                        .updateLocation("secret-loc-77", "1", location())));
    }

    static Stream<Named<Operation>> writeOperations() {
        return operations().filter(operation -> operation.getPayload().write());
    }

    static Stream<Named<Operation>> getOperations() {
        return operations().filter(operation -> operation.getPayload().firstMethod().equals("GET"));
    }

    static Stream<Named<Operation>> readOperations() {
        return operations().filter(operation -> operation.getPayload().name().endsWith("-read"));
    }

    private static Named<Operation> op(String name, String method, boolean write, Consumer<ApplicationContext> call) {
        return Named.of(name, new Operation(name, method, write, call));
    }

    private static OrganizationWriteRequest organization() {
        return new OrganizationWriteRequest(null, true, null, "Secret Synthetic Clinic", null, null, null, "secret-parent-77");
    }

    private static LocationWriteRequest location() {
        return new LocationWriteRequest(null, LocationWriteRequest.Status.ACTIVE, "Secret Synthetic Ward", null, null,
                null, null, null, null, null, null, "secret-org-77", null);
    }

    private ApplicationContextRunner context(String baseUrl) {
        return new ApplicationContextRunner().withUserConfiguration(FhirClientConfig.class, FhirRequestExecutor.class,
                FhirPatientClient.class, FhirPractitionerClient.class, FhirObservationClient.class, ObservationMapper.class,
                FhirConditionClient.class, ConditionMapper.class, FhirEncounterClient.class, EncounterMapper.class,
                FhirOrganizationClient.class, OrganizationMapper.class, FhirLocationClient.class, LocationMapper.class)
                .withPropertyValues("medinexus.fhir.base-url=" + baseUrl, "medinexus.fhir.connect-timeout=1s",
                        "medinexus.fhir.socket-timeout=" + SOCKET_TIMEOUT_MILLIS + "ms",
                        "medinexus.fhir.connection-request-timeout=1s");
    }

    /** A local server whose reads and writes can be made to succeed, hang beyond the socket timeout or fail. */
    private String startServer() throws IOException {
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/fhir/", this::handle);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/fhir";
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            requests.add(method + " " + exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            Behaviour behaviour = method.equals("GET") ? reads : writes;
            if (behaviour == Behaviour.DROP) {
                return;
            }
            if (behaviour == Behaviour.HANG) {
                try {
                    Thread.sleep(HANG_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            String body;
            int code;
            if (behaviour == Behaviour.STATUS) {
                code = status;
                body = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"transient\","
                        + "\"diagnostics\":\"SENSITIVE detail about Secret Synthetic Clinic and secret-patient-77\"}]}";
            } else {
                // A current, versioned resource for the read phase of an update (or for any successful read).
                String[] path = exchange.getRequestURI().getPath().substring("/fhir/".length()).split("/");
                code = 200;
                body = "{\"resourceType\":\"" + path[0] + "\",\"id\":\"" + path[path.length - 1]
                        + "\",\"meta\":{\"versionId\":\"1\"},\"name\":\"Secret Synthetic Clinic\"}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    /** A base URL on 127.0.0.1 whose port was just released, so connections are refused. */
    private static String refusedBaseUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/fhir";
        }
    }

    private static void assertFails(ApplicationContext context, Operation operation, FhirServerException.Type type,
            Integer httpStatus, List<String> issues) {
        assertThatThrownBy(() -> operation.call().accept(context))
                .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                    assertThat(failure.operation()).isEqualTo(operation.name());
                    assertThat(failure.type()).isEqualTo(type);
                    if (httpStatus == null) assertThat(failure.httpStatus()).isEmpty();
                    else assertThat(failure.httpStatus()).hasValue(httpStatus);
                    assertThat(failure.issues()).isEqualTo(issues);
                    assertThat(failure.getCause()).isNull();
                    assertSafe(failure.getMessage());
                });
    }

    /** No identifiers, names, URLs, hosts or server diagnostics; checked without printing the text on failure. */
    private static void assertSafe(String text) {
        for (String forbidden : List.of("secret", "Secret", "SENSITIVE", "127.0.0.1", "localhost", "/fhir", "http://",
                SYNTHETIC_PERSONNUMMER, "9001012385")) {
            assertThat(text.contains(forbidden)).as("contains forbidden text '%s'", forbidden).isFalse();
        }
    }

    /** Exactly one safe WARN line for the operation and nothing else that could carry request details. */
    private static void assertLoggedOnceSafely(CapturedOutput output, Operation operation, String type, String status) {
        String all = output.getAll();
        assertThat(all).contains("FHIR request failed: operation=" + operation.name() + ", type=" + type
                + ", httpStatus=" + status + ", ");
        assertThat(all.split("FHIR request failed", -1)).hasSize(2);
        assertSafe(all);
        assertThat(all.contains("RetryExec") || all.contains("Retrying request")).as("automatic retry logged").isFalse();
    }

    @ParameterizedTest
    @MethodSource("operations")
    void connectionRefusedIsUnavailableWithoutRetry(Operation operation, CapturedOutput output) throws IOException {
        context(refusedBaseUrl()).run(ctx -> assertFails(ctx, operation, FhirServerException.Type.UNAVAILABLE, null, List.of()));
        assertLoggedOnceSafely(output, operation, "UNAVAILABLE", "-");
    }

    @ParameterizedTest
    @MethodSource("operations")
    void unresolvableHostIsUnavailable(Operation operation, CapturedOutput output) {
        // ".invalid" can never resolve (RFC 2606), so no connection is ever attempted.
        context("http://fhir-server.invalid/fhir").run(ctx ->
                assertFails(ctx, operation, FhirServerException.Type.UNAVAILABLE, null, List.of()));
        assertLoggedOnceSafely(output, operation, "UNAVAILABLE", "-");
        assertThat(output.getAll().contains("fhir-server.invalid")).isFalse();
    }

    @ParameterizedTest
    @MethodSource("operations")
    void readTimeoutIsTimeoutAfterExactlyOneRequest(Operation operation, CapturedOutput output) throws IOException {
        reads = Behaviour.HANG;
        writes = Behaviour.HANG;
        String base = startServer();
        long start = System.nanoTime();
        context(base).run(ctx -> assertThatThrownBy(() -> operation.call().accept(ctx))
                .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                    assertThat(failure.operation()).isEqualTo(operation.name());
                    assertThat(failure.type()).isEqualTo(FhirServerException.Type.TIMEOUT);
                    assertThat(failure.httpStatus()).isEmpty();
                    assertThat(failure.causeType()).isEqualTo("SocketTimeoutException");
                    assertSafe(failure.getMessage());
                }));
        // The client gave up after the socket timeout, long before the server would have answered.
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(HANG_MILLIS);
        // Updates fail in their read phase: no PUT is sent. Nothing is retried.
        assertThat(requests).hasSize(1).allMatch(request -> request.startsWith(operation.firstMethod() + " /fhir/"));
        assertLoggedOnceSafely(output, operation, "TIMEOUT", "-");
    }

    @ParameterizedTest
    @MethodSource("writeOperations")
    void writeThatTimesOutIsSentExactlyOnce(Operation operation, CapturedOutput output) throws IOException {
        writes = Behaviour.HANG;
        String base = startServer();
        context(base).run(ctx -> assertThatThrownBy(() -> operation.call().accept(ctx))
                .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                    assertThat(failure.type()).isEqualTo(FhirServerException.Type.TIMEOUT);
                    assertThat(failure.causeType()).isEqualTo("SocketTimeoutException");
                }));
        // The write may have been committed by the server; the client reports TIMEOUT and never resends it.
        List<String> writeRequests = requests.stream().filter(request -> !request.startsWith("GET ")).toList();
        assertThat(writeRequests).singleElement().satisfies(request -> assertThat(request).matches("(POST|PUT) /fhir/.*"));
        assertThat(requests).hasSize(operation.firstMethod().equals("GET") ? 2 : 1);
        assertLoggedOnceSafely(output, operation, "TIMEOUT", "-");
    }

    @ParameterizedTest
    @MethodSource("operations")
    void serviceUnavailableIsTranslatedWithoutDiagnosticsOrRetry(Operation operation, CapturedOutput output)
            throws IOException {
        reads = Behaviour.STATUS;
        writes = Behaviour.STATUS;
        String base = startServer();
        context(base).run(ctx -> assertFails(ctx, operation, FhirServerException.Type.UNAVAILABLE, 503,
                List.of("error:transient")));
        assertThat(requests).hasSize(1);
        assertLoggedOnceSafely(output, operation, "UNAVAILABLE", "503");
    }

    @ParameterizedTest
    @MethodSource("readOperations")
    void goneResourcesAreClientErrors(Operation operation, CapturedOutput output) throws IOException {
        reads = Behaviour.STATUS;
        status = 410;
        String base = startServer();
        context(base).run(ctx -> assertFails(ctx, operation, FhirServerException.Type.CLIENT_ERROR, 410,
                List.of("error:transient")));
        assertThat(requests).hasSize(1);
        assertLoggedOnceSafely(output, operation, "CLIENT_ERROR", "410");
    }

    @ParameterizedTest
    @MethodSource("readOperations")
    void missingResourcesOnAnEmptyServerAre404ClientErrors(Operation operation, CapturedOutput output) throws IOException {
        localFhir = LocalFhirServer.start();
        context(localFhir.baseUrl()).run(ctx -> assertFails(ctx, operation, FhirServerException.Type.CLIENT_ERROR, 404,
                List.of("error:not-found")));
        assertThat(localFhir.requests()).hasSize(1).allMatch(request -> request.startsWith("GET /fhir/"));
        assertLoggedOnceSafely(output, operation, "CLIENT_ERROR", "404");
    }

    @Test
    void updatesOfMissingResourcesSendNoPutAndSearchesForUnknownPatientsAreEmpty(CapturedOutput output) throws IOException {
        localFhir = LocalFhirServer.start();
        context(localFhir.baseUrl()).run(ctx -> {
            assertThatThrownBy(() -> ctx.getBean(FhirOrganizationClient.class).updateOrganization("secret-org-77", "1", organization()))
                    .isInstanceOfSatisfying(FhirServerException.class, failure -> assertThat(failure.httpStatus()).hasValue(404));
            assertThatThrownBy(() -> ctx.getBean(FhirLocationClient.class).updateLocation("secret-loc-77", "1", location()))
                    .isInstanceOfSatisfying(FhirServerException.class, failure -> assertThat(failure.httpStatus()).hasValue(404));
            // A Patient without records is not an error.
            assertThat(ctx.getBean(FhirObservationClient.class).findByPatientId(SECRET_PATIENT).observations()).isEmpty();
            assertThat(ctx.getBean(FhirConditionClient.class).findByPatientId(SECRET_PATIENT)).isEmpty();
            assertThat(ctx.getBean(FhirEncounterClient.class).findByPatientId(SECRET_PATIENT).encounters()).isEmpty();
        });
        assertThat(localFhir.requests()).hasSize(5).allMatch(request -> request.startsWith("GET /fhir/"));
        assertSafe(output.getAll());
    }

    @ParameterizedTest
    @ValueSource(strings = {"connect", "pool"})
    void connectionTimeoutsAreTimeoutsAndLoggedSafely(String kind, CapturedOutput output) {
        // A real connect timeout depends on the network (see FhirConnectTimeoutIT); this checks the shared
        // translation and logging path with the exception HAPI raises for it.
        // Apache HttpClient wraps a connect timeout's SocketTimeoutException; the root cause is reported.
        IOException cause = kind.equals("connect")
                ? (IOException) new ConnectTimeoutException("Connect to http://127.0.0.1:1/fhir/Patient/secret-patient-77 timed out")
                        .initCause(new java.net.SocketTimeoutException("connect timed out"))
                : new ConnectionPoolTimeoutException("Timeout waiting for connection to secret-patient-77");
        String expectedCause = kind.equals("connect") ? "SocketTimeoutException" : "ConnectionPoolTimeoutException";
        assertThatThrownBy(() -> new FhirRequestExecutor().execute("patient-read", () -> {
            throw new FhirClientConnectionException(cause);
        })).isInstanceOfSatisfying(FhirServerException.class, failure -> {
            assertThat(failure.type()).isEqualTo(FhirServerException.Type.TIMEOUT);
            assertThat(failure.httpStatus()).isEmpty();
            assertThat(failure.causeType()).isEqualTo(expectedCause);
            assertThat(failure.getCause()).isNull();
            assertSafe(failure.getMessage());
        });
        assertThat(output.getAll()).contains("FHIR request failed: operation=patient-read, type=TIMEOUT, httpStatus=-");
        assertSafe(output.getAll());
    }

    @Test
    void sameClientsWorkAgainOnceTheServerRecovers(CapturedOutput output) throws IOException {
        reads = Behaviour.STATUS;
        String base = startServer();
        context(base).run(ctx -> {
            var organizations = ctx.getBean(FhirOrganizationClient.class);
            assertThatThrownBy(() -> organizations.readOrganization("secret-org-77"))
                    .isInstanceOfSatisfying(FhirServerException.class, failure -> assertThat(failure.httpStatus()).hasValue(503));
            reads = Behaviour.OK;
            // No circuit breaker or cached failure: the next call reaches the server and succeeds.
            assertThat(organizations.readOrganization("secret-org-77").getName()).isEqualTo("Secret Synthetic Clinic");
            assertThat(ctx.getBean(OrganizationMapper.class).toDto(organizations.readOrganization("secret-org-77")).versionId())
                    .isEqualTo("1");
        });
        assertThat(requests).containsExactly("GET /fhir/Organization/secret-org-77", "GET /fhir/Organization/secret-org-77",
                "GET /fhir/Organization/secret-org-77");
        assertThat(output.getAll().split("FHIR request failed", -1)).hasSize(2);
    }

    /**
     * A GET (read, search or the read phase of an update) whose connection is closed without a response is
     * sent exactly once. Apache HttpClient would by default resend it up to three times and log each retry
     * with the server address; FhirClientConfig disables these automatic retries.
     * Writes: writeOnAConnectionClosedWithoutResponseIsSentExactlyOnce.
     */
    @ParameterizedTest
    @MethodSource("getOperations")
    void getOnAConnectionClosedWithoutResponseIsSentExactlyOnce(Operation operation, CapturedOutput output)
            throws IOException {
        reads = Behaviour.DROP;
        String base = startServer();
        context(base).run(ctx -> assertThatThrownBy(() -> operation.call().accept(ctx))
                .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                    assertThat(failure.operation()).isEqualTo(operation.name());
                    assertThat(failure.type()).isEqualTo(FhirServerException.Type.UNAVAILABLE);
                    assertThat(failure.httpStatus()).isEmpty();
                    assertThat(failure.causeType()).isEqualTo("NoHttpResponseException");
                    assertSafe(failure.getMessage());
                }));
        assertThat(requests).singleElement().satisfies(request -> assertThat(request).startsWith("GET /fhir/"));
        // No retry log lines (which named the server address) and exactly one safe WARN line.
        assertThat(output.getAll().contains("RetryExec")).as("HTTP client retry logged").isFalse();
        assertLoggedOnceSafely(output, operation, "UNAVAILABLE", "-");
    }

    @ParameterizedTest
    @MethodSource("writeOperations")
    void writeOnAConnectionClosedWithoutResponseIsSentExactlyOnce(Operation operation, CapturedOutput output)
            throws IOException {
        // Reads succeed (the read phase of an update); every POST/PUT is received and the connection is then
        // closed without any response, as when a server or proxy fails mid-request.
        writes = Behaviour.DROP;
        String base = startServer();
        context(base).run(ctx -> assertThatThrownBy(() -> operation.call().accept(ctx))
                .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                    assertThat(failure.operation()).isEqualTo(operation.name());
                    assertThat(failure.httpStatus()).isEmpty();
                    assertSafe(failure.getMessage());
                }));
        List<String> writeRequests = requests.stream().filter(request -> !request.startsWith("GET ")).toList();
        assertThat(writeRequests).as("write requests received").hasSize(1);
        assertThat(requests).hasSize(operation.firstMethod().equals("GET") ? 2 : 1);
        assertSafe(output.getAll());
        assertThat(output.getAll().contains("RetryExec")).as("automatic retry logged").isFalse();
        assertThat(output.getAll().split("FHIR request failed", -1)).hasSize(2);
    }
}
