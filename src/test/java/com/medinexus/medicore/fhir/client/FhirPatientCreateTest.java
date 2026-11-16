package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.medinexus.medicore.fhir.config.FhirClientConfig;
import com.medinexus.medicore.fhir.support.LocalFhirServer;
import com.medinexus.medicore.patient.dto.CreatePatientRequest;
import com.sun.net.httpserver.HttpServer;

import ca.uhn.fhir.context.FhirContext;

/**
 * US-04.01: conditional Patient create against local servers on 127.0.0.1 only. Synthetic test personnummer
 * (Skatteverket's test series); nothing is sent to an external FHIR server.
 */
@ExtendWith(OutputCaptureExtension.class)
class FhirPatientCreateTest {

    private static final String SYSTEM = "http://electronichealth.se/identifier/personnummer";
    private static final String PERSONNUMMER = "199001012385";
    private static final String OTHER_PERSONNUMMER = "198506152381";

    private LocalFhirServer localFhir;
    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> conditions = new CopyOnWriteArrayList<>();
    private int status;
    private String location;
    private String body;

    @AfterEach
    void stop() {
        if (localFhir != null) localFhir.close();
        if (server != null) server.stop(0);
    }

    private static CreatePatientRequest request(String personnummer, LocalDate birthDate) {
        return new CreatePatientRequest(personnummer, "Testsson", List.of("Tolvan", "Syntet"),
                CreatePatientRequest.Gender.FEMALE, birthDate);
    }

    private static CreatePatientRequest request() {
        return request(PERSONNUMMER, LocalDate.of(1990, 1, 1));
    }

    private ApplicationContextRunner context(String baseUrl) {
        return new ApplicationContextRunner()
                .withUserConfiguration(FhirClientConfig.class, FhirRequestExecutor.class, FhirPatientClient.class)
                .withPropertyValues("medinexus.fhir.base-url=" + baseUrl, "medinexus.fhir.connect-timeout=2s",
                        "medinexus.fhir.socket-timeout=5s", "medinexus.fhir.connection-request-timeout=2s");
    }

    /** A server that answers every request with the configured status, Location and body. */
    private String startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fhir/", exchange -> {
            try (exchange) {
                requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
                String condition = exchange.getRequestHeaders().getFirst("If-None-Exist");
                if (condition != null) conditions.add(condition);
                exchange.getRequestBody().readAllBytes();
                if (location != null) exchange.getResponseHeaders().set("Location", location.replace("{base}",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/fhir"));
                if (body == null) {
                    exchange.sendResponseHeaders(status, -1);
                } else {
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/fhir";
    }

    private static String outcome(String code) {
        return "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"" + code
                + "\",\"diagnostics\":\"SENSITIVE detail about " + PERSONNUMMER + " Testsson\"}]}";
    }

    /** Neither the personnummer, names, birth date nor server diagnostics; checked without printing the text. */
    private static void assertSafe(String text) {
        for (String forbidden : List.of(PERSONNUMMER, "9001012385", "Testsson", "Tolvan", "1990-01-01", "SENSITIVE")) {
            assertThat(text.contains(forbidden)).as("contains forbidden text #%s", forbidden.length()).isFalse();
        }
    }

    @Test
    void createsThePatientWithOneConditionalPostAndReturnsTheAssignedId(CapturedOutput output) throws IOException {
        localFhir = LocalFhirServer.start();
        context(localFhir.baseUrl()).run(ctx -> {
            String id = ctx.getBean(FhirPatientClient.class).createPatient(request());

            assertThat(id).isEqualTo("local-1");
            assertThat(localFhir.requests()).containsExactly("POST /fhir/Patient");
            assertThat(localFhir.conditionalCreates()).singleElement().satisfies(header -> assertThat(
                    URLDecoder.decode(header, StandardCharsets.UTF_8))
                    .endsWith("identifier=" + SYSTEM + "|" + PERSONNUMMER));
            Patient stored = (Patient) localFhir.stored("Patient", "local-1");
            assertThat(stored.getIdentifier()).singleElement().satisfies(identifier -> {
                assertThat(identifier.getSystem()).isEqualTo(SYSTEM);
                assertThat(identifier.getValue()).isEqualTo(PERSONNUMMER);
            });
            assertThat(stored.getNameFirstRep().getFamily()).isEqualTo("Testsson");
            assertThat(stored.getGender()).isEqualTo(AdministrativeGender.FEMALE);
            assertThat(stored.getBirthDateElement().getValueAsString()).isEqualTo("1990-01-01");
            assertThat(stored.getActive()).isTrue();
            assertThat(stored.hasText()).isFalse();
        });
        assertSafe(output.getAll());
    }

    @Test
    void anExistingPatientWithTheSamePersonnummerIsADuplicateAndNothingIsCreated(CapturedOutput output)
            throws IOException {
        localFhir = LocalFhirServer.start();
        context(localFhir.baseUrl()).run(ctx -> {
            FhirPatientClient client = ctx.getBean(FhirPatientClient.class);
            assertThat(client.createPatient(request())).isEqualTo("local-1");
            assertThat(client.createPatient(request(OTHER_PERSONNUMMER, LocalDate.of(1985, 6, 15))))
                    .isEqualTo("local-2");

            assertThatThrownBy(() -> client.createPatient(request()))
                    .isInstanceOfSatisfying(DuplicatePatientException.class, failure -> {
                        assertThat(failure.getMessage()).isEqualTo("A Patient with the same personnummer already exists");
                        assertSafe(failure.getMessage());
                        assertThat(failure.getMessage()).doesNotContain("local-1");
                    });
        });
        assertThat(localFhir.count("Patient")).isEqualTo(2);
        assertThat(localFhir.requests()).containsExactly("POST /fhir/Patient", "POST /fhir/Patient", "POST /fhir/Patient");
        // A duplicate is an expected outcome, not a FHIR failure: nothing is logged by the client.
        assertThat(output.getAll()).doesNotContain("FHIR request failed");
        assertSafe(output.getAll());
    }

    @Test
    void aPatientSeededWithTheSameIdentifierIsDetected() throws IOException {
        localFhir = LocalFhirServer.start();
        Patient existing = new Patient();
        existing.setId("existing-7");
        existing.addIdentifier().setSystem(SYSTEM).setValue(PERSONNUMMER);
        localFhir.seed(existing);
        context(localFhir.baseUrl()).run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirPatientClient.class)
                .createPatient(request())).isInstanceOf(DuplicatePatientException.class));
        assertThat(localFhir.count("Patient")).isEqualTo(1);
    }

    @Test
    void aMatchingIdentifierInAnotherSystemIsNotADuplicate() throws IOException {
        localFhir = LocalFhirServer.start();
        Patient other = new Patient();
        other.setId("other-system");
        other.addIdentifier().setSystem("urn:synthetic:other").setValue(PERSONNUMMER);
        localFhir.seed(other);
        context(localFhir.baseUrl()).run(ctx ->
                assertThat(ctx.getBean(FhirPatientClient.class).createPatient(request())).isEqualTo("local-1"));
        assertThat(localFhir.count("Patient")).isEqualTo(2);
    }

    @Test
    void severalMatchesGive412AndAreReportedAsDuplicate(CapturedOutput output) throws IOException {
        status = 412;
        body = outcome("duplicate");
        String base = startServer();
        context(base).run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirPatientClient.class).createPatient(request()))
                .isInstanceOfSatisfying(DuplicatePatientException.class, failure -> assertSafe(failure.getMessage())));
        assertThat(requests).containsExactly("POST /fhir/Patient");
        assertThat(output.getAll()).contains("FHIR request failed: operation=patient-create, type=CLIENT_ERROR, httpStatus=412");
        assertSafe(output.getAll());
    }

    @Test
    void aMatchReportedWithoutLocationIsStillADuplicate() throws IOException {
        status = 200;
        String base = startServer();
        context(base).run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirPatientClient.class).createPatient(request()))
                .isInstanceOf(DuplicatePatientException.class));
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            // Created, but no assigned ID.
            "201, null",
            // Created, but the Location names another resource type or an invalid ID.
            "201, {base}/Observation/local-1/_history/1",
            "201, {base}/Patient/bad_id/_history/1",
            // Not created and the Location names another resource type.
            "200, {base}/Observation/local-1/_history/1"})
    void responsesThatDoNotConfirmAValidPatientAreInvalid(int responseStatus, String responseLocation,
            CapturedOutput output) throws IOException {
        status = responseStatus;
        location = responseLocation;
        String base = startServer();
        context(base).run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirPatientClient.class).createPatient(request()))
                .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                    assertThat(failure.operation()).isEqualTo("patient-create");
                    assertThat(failure.type()).isEqualTo(FhirServerException.Type.INVALID_RESPONSE);
                    assertSafe(failure.getMessage());
                }));
        assertThat(requests).hasSize(1);
        assertSafe(output.getAll());
    }

    @ParameterizedTest
    @CsvSource({"400, CLIENT_ERROR", "422, CLIENT_ERROR", "500, SERVER_ERROR", "503, UNAVAILABLE"})
    void serverErrorsAreTranslatedWithoutDiagnosticsOrRetry(int responseStatus, FhirServerException.Type type,
            CapturedOutput output) throws IOException {
        status = responseStatus;
        body = outcome("processing");
        String base = startServer();
        context(base).run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirPatientClient.class).createPatient(request()))
                .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                    assertThat(failure.type()).isEqualTo(type);
                    assertThat(failure.httpStatus()).hasValue(responseStatus);
                    assertThat(failure.issues()).containsExactly("error:processing");
                    assertThat(failure.getCause()).isNull();
                    assertSafe(failure.getMessage());
                }));
        assertThat(requests).containsExactly("POST /fhir/Patient");
        assertSafe(output.getAll());
    }

    @Test
    void invalidRegistrationDataIsRejectedBeforeAnyRequest() throws IOException {
        status = 201;
        String base = startServer();
        context(base).run(ctx -> {
            FhirPatientClient client = ctx.getBean(FhirPatientClient.class);
            for (CreatePatientRequest invalid : new CreatePatientRequest[] {null,
                    request("199002301234", LocalDate.of(1990, 2, 28)),
                    request(PERSONNUMMER, LocalDate.of(1990, 1, 2))}) {
                assertThatThrownBy(() -> client.createPatient(invalid))
                        .isInstanceOfSatisfying(IllegalArgumentException.class, failure -> assertSafe(failure.getMessage()));
            }
        });
        assertThat(requests).isEmpty();
    }

    @Test
    void theRequestBodyIsAPlainR4PatientWithoutIdMetaOrNarrative() throws IOException {
        localFhir = LocalFhirServer.start();
        context(localFhir.baseUrl()).run(ctx -> ctx.getBean(FhirPatientClient.class).createPatient(request()));
        // The stored copy is the parsed request body plus the server's ID and meta.
        String json = FhirContext.forR4().newJsonParser().encodeResourceToString(localFhir.stored("Patient", "local-1"));
        assertThat(json).contains("\"resourceType\":\"Patient\"").doesNotContain("\"text\"", "\"contained\"",
                "\"extension\"", "\"modifierExtension\"");
    }
}
