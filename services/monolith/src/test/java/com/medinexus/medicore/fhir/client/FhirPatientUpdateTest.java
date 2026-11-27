package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hl7.fhir.r4.model.Address;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.medinexus.medicore.fhir.config.FhirClientConfig;
import com.medinexus.medicore.fhir.support.LocalFhirServer;
import com.sun.net.httpserver.HttpServer;

/**
 * US-04.04: version-aware Patient update against local servers on 127.0.0.1 only. Fictional data; nothing is sent to
 * an external FHIR server.
 */
@ExtendWith(OutputCaptureExtension.class)
class FhirPatientUpdateTest {

    private static final String SYSTEM = "http://electronichealth.se/identifier/personnummer";
    private static final String PERSONNUMMER = "200412201971";

    private LocalFhirServer localFhir;
    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (localFhir != null) localFhir.close();
        if (server != null) server.stop(0);
    }

    private ApplicationContextRunner context(String baseUrl, String socketTimeout) {
        return new ApplicationContextRunner()
                .withUserConfiguration(FhirClientConfig.class, FhirRequestExecutor.class, FhirPatientClient.class)
                .withPropertyValues("medinexus.fhir.base-url=" + baseUrl, "medinexus.fhir.connect-timeout=2s",
                        "medinexus.fhir.socket-timeout=" + socketTimeout, "medinexus.fhir.connection-request-timeout=2s");
    }

    /** A Patient with more than MediCore writes, to show that everything is written back unchanged. */
    private static Patient rich(String id) {
        Patient patient = new Patient();
        patient.setId(id);
        patient.addIdentifier().setSystem(SYSTEM).setValue(PERSONNUMMER);
        patient.addIdentifier().setSystem("urn:example:other").setValue("X-1");
        patient.addName().setUse(HumanName.NameUse.OFFICIAL).setFamily("Khan").addGiven("Ali");
        patient.addName().setUse(HumanName.NameUse.NICKNAME).addGiven("Alle");
        patient.setGender(AdministrativeGender.MALE);
        patient.getBirthDateElement().setValueAsString("2004-12-20");
        patient.addTelecom().setSystem(ContactPoint.ContactPointSystem.PHONE).setValue("+46 70 000 00 00");
        patient.addAddress(new Address().setCity("Stockholm").addLine("Testgatan 1"));
        patient.addExtension("urn:example:extension", new StringType("kept"));
        patient.setActive(true);
        return patient;
    }

    private static void assertSafe(String text) {
        for (String forbidden : List.of(PERSONNUMMER, "Khan", "Ali", "Stockholm", "+46", "SENSITIVE")) {
            assertThat(text.contains(forbidden)).as("contains forbidden text #%s", forbidden.length()).isFalse();
        }
    }

    @Test
    void updatesTheSamePatientWithIfMatchAndKeepsEverythingElse(CapturedOutput output) throws IOException {
        localFhir = LocalFhirServer.start();
        localFhir.seed(rich("syn-1"));
        context(localFhir.baseUrl(), "5s").run(ctx -> {
            FhirPatientClient client = ctx.getBean(FhirPatientClient.class);
            Patient read = client.readPatient("syn-1");
            read.getNameFirstRep().setFamily("Karlsson");
            read.setGender(AdministrativeGender.OTHER);

            Patient updated = client.updatePatient(read, "1");

            assertThat(localFhir.requests()).containsExactly("GET /fhir/Patient/syn-1", "PUT /fhir/Patient/syn-1");
            assertThat(localFhir.ifMatches()).containsExactly("W/\"1\"");
            assertThat(localFhir.count("Patient")).isEqualTo(1);
            assertThat(updated.getIdElement().getIdPart()).isEqualTo("syn-1");
            assertThat(updated.getMeta().getVersionId()).isEqualTo("2");
            Patient stored = (Patient) localFhir.stored("Patient", "syn-1");
            assertThat(stored.getNameFirstRep().getFamily()).isEqualTo("Karlsson");
            assertThat(stored.getGender()).isEqualTo(AdministrativeGender.OTHER);
            Patient original = rich("syn-1");
            assertThat(stored.getIdentifier()).hasSize(2);
            for (int i = 0; i < 2; i++) {
                assertThat(stored.getIdentifier().get(i).equalsDeep(original.getIdentifier().get(i))).isTrue();
            }
            assertThat(stored.getBirthDateElement().getValueAsString()).isEqualTo("2004-12-20");
            assertThat(stored.getName().get(1).equalsDeep(original.getName().get(1))).isTrue();
            assertThat(stored.getTelecomFirstRep().equalsDeep(original.getTelecomFirstRep())).isTrue();
            assertThat(stored.getAddressFirstRep().equalsDeep(original.getAddressFirstRep())).isTrue();
            assertThat(stored.getExtensionByUrl("urn:example:extension").getValue().primitiveValue()).isEqualTo("kept");
            assertThat(stored.getActive()).isTrue();
        });
        assertSafe(output.getAll());
    }

    @Test
    void aStaleVersionIsAConflictAndNothingIsOverwritten() throws IOException {
        localFhir = LocalFhirServer.start();
        localFhir.seed(rich("syn-1"));
        context(localFhir.baseUrl(), "5s").run(ctx -> {
            FhirPatientClient client = ctx.getBean(FhirPatientClient.class);
            Patient read = client.readPatient("syn-1");
            Patient other = rich("syn-1");
            other.getNameFirstRep().setFamily("Someone-Else");
            localFhir.changeConcurrently(other);

            read.getNameFirstRep().setFamily("Karlsson");
            assertThatThrownBy(() -> client.updatePatient(read, "1"))
                    .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                        assertThat(failure.type()).isEqualTo(FhirServerException.Type.CLIENT_ERROR);
                        assertThat(failure.httpStatus().getAsInt()).isEqualTo(409);
                    });
            assertThat(((Patient) localFhir.stored("Patient", "syn-1")).getNameFirstRep().getFamily())
                    .isEqualTo("Someone-Else");
        });
    }

    @Test
    void outagesTimeoutsAndMissingPatientsAreReportedNotIgnored() throws IOException {
        localFhir = LocalFhirServer.start();
        localFhir.seed(rich("syn-1"));
        context(localFhir.baseUrl(), "1s").run(ctx -> {
            FhirPatientClient client = ctx.getBean(FhirPatientClient.class);
            Patient read = client.readPatient("syn-1");

            localFhir.updatesUnavailable(true);
            assertThatThrownBy(() -> client.updatePatient(read, "1")).isInstanceOfSatisfying(FhirServerException.class,
                    failure -> assertThat(failure.type()).isEqualTo(FhirServerException.Type.UNAVAILABLE));
            localFhir.updatesUnavailable(false);

            Patient missing = read.copy();
            missing.setId("syn-404");
            assertThatThrownBy(() -> client.updatePatient(missing, "1")).isInstanceOfSatisfying(FhirServerException.class,
                    failure -> assertThat(failure.httpStatus().getAsInt()).isEqualTo(404));

            // Last: the local server handles one request at a time and stays busy with the delayed one.
            localFhir.updateDelayMillis(2_500);
            assertThatThrownBy(() -> client.updatePatient(read, "1")).isInstanceOfSatisfying(FhirServerException.class,
                    failure -> assertThat(failure.type()).isEqualTo(FhirServerException.Type.TIMEOUT));
        });
    }

    /** A server that answers every PUT with the given status and body. */
    private String startServer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fhir/", exchange -> {
            try (exchange) {
                requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
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

    @Test
    void aResponseThatCreatedOrNamesAnotherPatientIsInvalid(CapturedOutput output) throws IOException {
        String otherPatient = "{\"resourceType\":\"Patient\",\"id\":\"syn-other\",\"meta\":{\"versionId\":\"2\"}}";
        for (int status : new int[] {200, 201}) {
            String base = startServer(status, status == 201
                    ? "{\"resourceType\":\"Patient\",\"id\":\"syn-1\",\"meta\":{\"versionId\":\"1\"}}" : otherPatient);
            context(base, "5s").run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirPatientClient.class)
                    .updatePatient(rich("syn-1"), "1")).isInstanceOfSatisfying(FhirServerException.class,
                            failure -> assertThat(failure.type()).isEqualTo(FhirServerException.Type.INVALID_RESPONSE)));
            server.stop(0);
        }
        String base = startServer(500, "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\","
                + "\"code\":\"exception\",\"diagnostics\":\"SENSITIVE " + PERSONNUMMER + " Khan\"}]}");
        context(base, "5s").run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirPatientClient.class)
                .updatePatient(rich("syn-1"), "1")).isInstanceOfSatisfying(FhirServerException.class, failure -> {
                    assertThat(failure.type()).isEqualTo(FhirServerException.Type.SERVER_ERROR);
                    assertSafe(failure.getMessage());
                }));
        assertSafe(output.getAll());
    }

    @Test
    void invalidInputIsRejectedBeforeAnyRequest() throws IOException {
        String base = startServer(200, "{}");
        context(base, "5s").run(ctx -> {
            FhirPatientClient client = ctx.getBean(FhirPatientClient.class);
            Patient noId = rich("syn-1");
            noId.setId((String) null);
            for (Runnable call : List.<Runnable>of(() -> client.updatePatient(null, "1"),
                    () -> client.updatePatient(noId, "1"),
                    () -> client.updatePatient(rich("syn-1"), null),
                    () -> client.updatePatient(rich("syn-1"), "1\"; x"),
                    () -> client.updatePatient(rich("bad_id"), "1"))) {
                assertThatThrownBy(call::run).isInstanceOf(IllegalArgumentException.class);
            }
        });
        assertThat(requests).isEmpty();
    }
}
