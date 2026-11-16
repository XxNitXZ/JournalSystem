package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hl7.fhir.r4.model.Observation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.medinexus.medicore.fhir.config.FhirClientConfig;
import com.medinexus.medicore.fhir.mapper.ObservationMapper;
import com.medinexus.medicore.medicalrecord.dto.CreateObservationRequest;
import com.medinexus.medicore.medicalrecord.dto.CreateObservationRequest.CodedValue;
import com.medinexus.medicore.medicalrecord.dto.CreateObservationRequest.Comparator;
import com.medinexus.medicore.medicalrecord.dto.CreateObservationRequest.QuantityValue;
import com.medinexus.medicore.medicalrecord.dto.CreateObservationRequest.Status;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Coding;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.sun.net.httpserver.HttpServer;

import ca.uhn.fhir.context.FhirContext;

class FhirObservationCreateTest {
    private static final FhirContext CONTEXT = FhirContext.forR4();
    private HttpServer server;
    private String base;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<Observation> payloads = new CopyOnWriteArrayList<>();
    private int status = 201;
    private String location = "/fhir/Observation/server-123/_history/1";
    private String responseBody = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            payloads.add(CONTEXT.newJsonParser().parseResource(Observation.class, body));
            exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
            if (location != null) exchange.getResponseHeaders().set("Location", base.substring(0, base.length() - 5) + location);
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
                FhirRequestExecutor.class, FhirObservationClient.class, ObservationMapper.class)
                .withPropertyValues("medinexus.fhir.base-url=" + base, "medinexus.fhir.connect-timeout=2s",
                        "medinexus.fhir.socket-timeout=3s", "medinexus.fhir.connection-request-timeout=2s");
    }

    private Concept code() {
        return new Concept("Synthetic measurement", List.of(new Coding("urn:synthetic:code", "1", "test", "Example", false)));
    }

    private QuantityValue quantity() {
        return new QuantityValue(new BigDecimal("12.500"), "mg", "http://unitsofmeasure.org", "mg", Comparator.LESS_THAN);
    }

    private CreateObservationRequest request() {
        return new CreateObservationRequest("synthetic-patient", Status.FINAL, code(), quantity(), null);
    }

    @Test
    void createsQuantityPreservingCodesUnitsAndPrecisionAndReturnsAssignedId() {
        context().run(ctx -> {
            assertThat(ctx.getBean(FhirObservationClient.class).createObservation(request())).isEqualTo("server-123");
            assertThat(requests).containsExactly("POST /fhir/Observation");
            Observation resource = payloads.getFirst();
            assertThat(resource.hasId()).isFalse();
            assertThat(resource.getSubject().getReference()).isEqualTo("Patient/synthetic-patient");
            assertThat(resource.getStatus()).isEqualTo(Observation.ObservationStatus.FINAL);
            assertThat(resource.getCode().getCodingFirstRep().getCode()).isEqualTo("test");
            assertThat(resource.getCode().getCodingFirstRep().getSystem()).isEqualTo("urn:synthetic:code");
            assertThat(resource.getCode().getCodingFirstRep().getVersion()).isEqualTo("1");
            assertThat(resource.getCode().getCodingFirstRep().getDisplay()).isEqualTo("Example");
            assertThat(resource.getValueQuantity().getValue()).isEqualTo(new BigDecimal("12.500"));
            assertThat(resource.getValueQuantity().getUnit()).isEqualTo("mg");
            assertThat(resource.getValueQuantity().getSystem()).isEqualTo("http://unitsofmeasure.org");
            assertThat(resource.getValueQuantity().getCode()).isEqualTo("mg");
            assertThat(resource.getValueQuantity().getComparator().toCode()).isEqualTo("<");
        });
    }

    @Test
    void createsCodedValueWithMultipleCodings() {
        Concept value = new Concept("Synthetic result", List.of(
                new Coding("urn:synthetic:a", null, "a", "First", true),
                new Coding("urn:synthetic:b", null, "b", "Second", null)));
        context().run(ctx -> {
            var request = new CreateObservationRequest("synthetic-patient", Status.PRELIMINARY, code(), new CodedValue(value), null);
            ctx.getBean(FhirObservationClient.class).createObservation(request);
            var resource = payloads.getFirst();
            assertThat(resource.hasValueQuantity()).isFalse();
            assertThat(resource.getValueCodeableConcept().getText()).isEqualTo("Synthetic result");
            assertThat(resource.getValueCodeableConcept().getCoding()).extracting(c -> c.getCode()).containsExactly("a", "b");
        });
    }

    @Test
    void createsAbsenceReasonWithoutInventingAValue() {
        context().run(ctx -> {
            ctx.getBean(FhirObservationClient.class).createObservation(new CreateObservationRequest(
                    "synthetic-patient", Status.FINAL, code(), null, new Concept("Not measured", List.of())));
            assertThat(payloads.getFirst().hasValue()).isFalse();
            assertThat(payloads.getFirst().getDataAbsentReason().getText()).isEqualTo("Not measured");
        });
    }

    @Test
    void allInvalidInputsFailBeforeOutboundRequests() {
        List<CreateObservationRequest> invalid = new ArrayList<>();
        invalid.add(null);
        for (String id : new String[] {null, "", " ", "Patient/1", "https://example.test/1", "id_name", "x".repeat(65)}) {
            invalid.add(new CreateObservationRequest(id, Status.FINAL, code(), quantity(), null));
        }
        invalid.add(new CreateObservationRequest("p1", null, code(), quantity(), null));
        invalid.add(new CreateObservationRequest("p1", Status.FINAL, null, quantity(), null));
        invalid.add(new CreateObservationRequest("p1", Status.FINAL, new Concept(null, List.of()), quantity(), null));
        invalid.add(new CreateObservationRequest("p1", Status.FINAL, code(), null, null));
        invalid.add(new CreateObservationRequest("p1", Status.FINAL, code(), quantity(), code()));
        invalid.add(new CreateObservationRequest("p1", Status.FINAL, code(), new CodedValue(null), null));
        invalid.add(new CreateObservationRequest("p1", Status.FINAL, code(), null, new Concept(" ", List.of())));
        for (QuantityValue value : List.of(new QuantityValue(null, "mg", null, null, null),
                new QuantityValue(BigDecimal.ONE, "mg", "not a URI", "mg", null),
                new QuantityValue(BigDecimal.ONE, "mg", null, "mg", null),
                new QuantityValue(BigDecimal.ONE, "mg", "urn:synthetic", null, null))) {
            invalid.add(new CreateObservationRequest("p1", Status.FINAL, code(), value, null));
        }
        invalid.add(new CreateObservationRequest("p1", Status.FINAL,
                new Concept("Text", List.of(new Coding(null, null, "code", null, null))), quantity(), null));
        context().run(ctx -> {
            for (var request : invalid) {
                assertThatThrownBy(() -> ctx.getBean(FhirObservationClient.class).createObservation(request))
                        .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid Observation creation request");
            }
            assertThat(requests).isEmpty();
        });
    }

    @ParameterizedTest
    @CsvSource({"404, CLIENT_ERROR", "422, CLIENT_ERROR", "500, SERVER_ERROR", "503, UNAVAILABLE"})
    void preservesExternalErrors(int responseStatus, FhirServerException.Type expected) {
        status = responseStatus;
        location = null;
        responseBody = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"processing\",\"diagnostics\":\"SENSITIVE\"}]}";
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirObservationClient.class).createObservation(request()))
                .isInstanceOfSatisfying(FhirServerException.class, error -> {
                    assertThat(error.operation()).isEqualTo("observation-create");
                    assertThat(error.httpStatus()).hasValue(responseStatus);
                    assertThat(error.type()).isEqualTo(expected);
                    assertThat(error.issues()).containsExactly("error:processing");
                    assertThat(error.getMessage()).doesNotContain("SENSITIVE", "synthetic-patient");
                }));
        assertThat(requests).containsExactly("POST /fhir/Observation");
    }

    @ParameterizedTest
    @CsvSource({"201, /fhir/Patient/wrong", "201, /fhir/Observation/bad_id", "200, /fhir/Observation/server-123"})
    void rejectsUnconfirmedOrInvalidAssignedIds(int responseStatus, String responseLocation) {
        status = responseStatus;
        location = responseLocation;
        failsInvalidResponse();
    }

    @Test
    void rejectsSuccessfulResponseWithoutAssignedId() {
        location = null;
        failsInvalidResponse();
    }

    private void failsInvalidResponse() {
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirObservationClient.class).createObservation(request()))
                .isInstanceOfSatisfying(FhirServerException.class, error ->
                        assertThat(error.type()).isEqualTo(FhirServerException.Type.INVALID_RESPONSE)));
        assertThat(requests).hasSize(1);
    }
}
