package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.hl7.fhir.r4.model.Condition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.medinexus.medicore.fhir.config.FhirClientConfig;
import com.medinexus.medicore.fhir.mapper.ConditionMapper;
import com.medinexus.medicore.medicalrecord.dto.CreateConditionRequest;
import com.medinexus.medicore.medicalrecord.dto.CreateConditionRequest.ClinicalStatus;
import com.medinexus.medicore.medicalrecord.dto.CreateConditionRequest.VerificationStatus;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Coding;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.sun.net.httpserver.HttpServer;

import ca.uhn.fhir.context.FhirContext;

class FhirConditionCreateTest {
    private static final FhirContext CONTEXT = FhirContext.forR4();
    private HttpServer server;
    private String base;
    private final List<String> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<Condition> payloads = new java.util.concurrent.CopyOnWriteArrayList<>();
    private int status = 201;
    private String location = "/fhir/Condition/server-123/_history/1";
    private String responseBody = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            payloads.add(CONTEXT.newJsonParser().parseResource(Condition.class,
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
            if (location != null) exchange.getResponseHeaders().set("Location", base.substring(0, base.length() - 5) + location);
            if (responseBody.isEmpty()) {
                exchange.sendResponseHeaders(status, -1); exchange.close();
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
                FhirRequestExecutor.class, FhirConditionClient.class, ConditionMapper.class)
                .withPropertyValues("medinexus.fhir.base-url=" + base, "medinexus.fhir.connect-timeout=2s",
                        "medinexus.fhir.socket-timeout=3s", "medinexus.fhir.connection-request-timeout=2s");
    }

    private Concept diagnosis() {
        return new Concept("Synthetic diagnosis", List.of(new Coding("urn:synthetic:diagnosis", "1", "test", "Example", false)));
    }

    private CreateConditionRequest request(ClinicalStatus clinical, VerificationStatus verification, String onset, String abatement) {
        return new CreateConditionRequest("synthetic-patient", diagnosis(), clinical, verification, List.of(), null, onset, abatement);
    }

    @Test
    void createsMappedPayloadWithAssignedIdAndTerminology() {
        Concept category = new Concept("Problem", List.of(new Coding("http://terminology.hl7.org/CodeSystem/condition-category",
                null, "problem-list-item", "Problem List Item", null)));
        Concept severity = new Concept("Synthetic severity", List.of(new Coding("urn:synthetic:severity", null, "mild", "Mild", true)));
        context().run(ctx -> {
            var input = new CreateConditionRequest("synthetic-patient", diagnosis(), ClinicalStatus.RESOLVED,
                    VerificationStatus.CONFIRMED, List.of(category), severity, "2020-06", "2026-06-15T12:34:56.123+02:00");
            assertThat(ctx.getBean(FhirConditionClient.class).createCondition(input)).isEqualTo("server-123");
            assertThat(requests).containsExactly("POST /fhir/Condition");
            var resource = payloads.getFirst();
            assertThat(resource.hasId()).isFalse();
            assertThat(resource.getSubject().getReference()).isEqualTo("Patient/synthetic-patient");
            var code = resource.getCode().getCodingFirstRep();
            assertThat(code.getSystem()).isEqualTo("urn:synthetic:diagnosis");
            assertThat(code.getCode()).isEqualTo("test");
            assertThat(code.getVersion()).isEqualTo("1");
            assertThat(code.getDisplay()).isEqualTo("Example");
            assertThat(code.getUserSelected()).isFalse();
            assertThat(resource.getClinicalStatus().getCodingFirstRep().getSystem()).isEqualTo(ClinicalStatus.SYSTEM);
            assertThat(resource.getClinicalStatus().getCodingFirstRep().getCode()).isEqualTo("resolved");
            assertThat(resource.getVerificationStatus().getCodingFirstRep().getSystem()).isEqualTo(VerificationStatus.SYSTEM);
            assertThat(resource.getVerificationStatus().getCodingFirstRep().getCode()).isEqualTo("confirmed");
            assertThat(resource.getCategoryFirstRep().getCodingFirstRep().getDisplay()).isEqualTo("Problem List Item");
            assertThat(resource.getSeverity().getCodingFirstRep().getCode()).isEqualTo("mild");
            assertThat(resource.getOnsetDateTimeType().getValueAsString()).isEqualTo("2020-06");
            assertThat(resource.getAbatementDateTimeType().getValueAsString()).isEqualTo("2026-06-15T12:34:56.123+02:00");
        });
    }

    @ParameterizedTest
    @EnumSource(ClinicalStatus.class)
    void emitsOnlyValidatedClinicalCodes(ClinicalStatus clinical) {
        context().run(ctx -> {
            ctx.getBean(FhirConditionClient.class).createCondition(request(clinical, null, null, null));
            assertThat(payloads.getFirst().getClinicalStatus().getCodingFirstRep().getCode()).isEqualTo(clinical.code());
            assertThat(payloads.getFirst().hasVerificationStatus()).isFalse();
        });
    }

    @ParameterizedTest
    @EnumSource(VerificationStatus.class)
    void emitsOnlyValidatedVerificationCodes(VerificationStatus verification) {
        context().run(ctx -> {
            ctx.getBean(FhirConditionClient.class).createCondition(request(null, verification, null, null));
            assertThat(payloads.getFirst().getVerificationStatus().getCodingFirstRep().getCode()).isEqualTo(verification.code());
            assertThat(payloads.getFirst().hasClinicalStatus()).isFalse();
        });
    }

    @ParameterizedTest
    @EnumSource(value = ClinicalStatus.class, names = {"INACTIVE", "REMISSION", "RESOLVED"})
    void allowsAbatementOnlyWithAllowedClinicalStatuses(ClinicalStatus clinical) {
        context().run(ctx -> ctx.getBean(FhirConditionClient.class).createCondition(request(clinical, null, null, "2026")));
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2020", "2020-02", "2020-02-29", "2020-02-29T12:34:56Z", "2020-02-29T12:34:56.123456+02:00"})
    void preservesOptionalDateTimePrecision(String date) {
        context().run(ctx -> ctx.getBean(FhirConditionClient.class).createCondition(request(ClinicalStatus.RESOLVED, null, date, date)));
        assertThat(payloads.getFirst().getOnsetDateTimeType().getValueAsString()).isEqualTo(date);
        assertThat(payloads.getFirst().getAbatementDateTimeType().getValueAsString()).isEqualTo(date);
    }

    /** US-05.02: an uncoded diagnosis is stored as text only, with recorded date, Encounter and recorder references. */
    @Test
    void createsTextOnlyDiagnosisWithRecordedDateEncounterAndRecorder() {
        context().run(ctx -> {
            var input = new CreateConditionRequest("synthetic-patient", new Concept("Fiktiv diagnos", List.of()),
                    ClinicalStatus.ACTIVE, VerificationStatus.CONFIRMED, List.of(), null, "2026-11-01", null,
                    "2026-11-12T09:30:00Z", "enc-1", "pract-1");
            assertThat(ctx.getBean(FhirConditionClient.class).createCondition(input)).isEqualTo("server-123");
            var resource = payloads.getFirst();
            assertThat(resource.getCode().getText()).isEqualTo("Fiktiv diagnos");
            assertThat(resource.getCode().hasCoding()).isFalse();
            assertThat(resource.getRecordedDateElement().getValueAsString()).isEqualTo("2026-11-12T09:30:00Z");
            assertThat(resource.getEncounter().getReference()).isEqualTo("Encounter/enc-1");
            assertThat(resource.getRecorder().getReference()).isEqualTo("Practitioner/pract-1");
        });
    }

    @Test
    void invalidInputsAndStatusConstraintsCauseZeroRequests() {
        List<CreateConditionRequest> invalid = new ArrayList<>();
        invalid.add(null);
        for (String id : new String[] {null, "", " ", "Patient/1", "https://example.test/1", "id_name", "x".repeat(65)})
            invalid.add(new CreateConditionRequest(id, diagnosis(), null, null, null, null, null, null));
        // Since US-05.02 a text-only code is valid (Condition.code.text); a code without text and coding is not.
        for (Concept code : new Concept[] {null, new Concept(" ", List.of()),
                new Concept(null, List.of(new Coding(null, null, "test", null, null))),
                new Concept(null, List.of(new Coding("bad uri", null, "test", null, null))),
                new Concept(null, List.of(new Coding("urn:synthetic", null, " ", null, null)))})
            invalid.add(new CreateConditionRequest("p1", code, null, null, null, null, null, null));
        for (ClinicalStatus clinical : ClinicalStatus.values()) {
            invalid.add(request(clinical, VerificationStatus.ENTERED_IN_ERROR, null, null));
            if (clinical == ClinicalStatus.ACTIVE || clinical == ClinicalStatus.RECURRENCE || clinical == ClinicalStatus.RELAPSE)
                invalid.add(request(clinical, null, null, "2026"));
        }
        invalid.add(request(null, null, null, "2026"));
        for (String[] ids : new String[][] {{"Encounter/1", null}, {"bad_id", null}, {null, "Practitioner/1"},
                {null, "http://x.test/p"}}) {
            invalid.add(new CreateConditionRequest("p1", diagnosis(), null, null, null, null, null, null, null, ids[0], ids[1]));
        }
        invalid.add(new CreateConditionRequest("p1", diagnosis(), null, null, null, null, null, null, "2026-13-01", null, null));
        invalid.add(request(null, VerificationStatus.ENTERED_IN_ERROR, null, "2026"));
        var problemList = new Concept(null, List.of(new Coding("http://terminology.hl7.org/CodeSystem/condition-category",
                null, "problem-list-item", null, null)));
        invalid.add(new CreateConditionRequest("p1", diagnosis(), null, VerificationStatus.CONFIRMED, List.of(problemList), null, null, null));
        invalid.add(new CreateConditionRequest("p1", diagnosis(), null, null, List.of(new Concept("", List.of())), null, null, null));
        invalid.add(new CreateConditionRequest("p1", diagnosis(), null, null, null, new Concept("", List.of()), null, null));
        for (String date : new String[] {"", "invalid", "0000", "2026-13", "2026-02-30", "2026-01-01T12:00:00", "2026-01-01T12:00:00+15:00"}) {
            invalid.add(request(null, null, date, null));
            invalid.add(request(ClinicalStatus.RESOLVED, null, null, date));
        }
        context().run(ctx -> {
            for (var input : invalid) assertThatThrownBy(() -> ctx.getBean(FhirConditionClient.class).createCondition(input))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid Condition creation request");
            assertThat(requests).isEmpty();
        });
    }

    @ParameterizedTest
    @CsvSource({"404, CLIENT_ERROR", "422, CLIENT_ERROR", "500, SERVER_ERROR", "503, UNAVAILABLE"})
    void preservesExternalErrors(int responseStatus, FhirServerException.Type type) {
        status = responseStatus; location = null;
        responseBody = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"processing\",\"diagnostics\":\"SENSITIVE\"}]}";
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirConditionClient.class).createCondition(request(null, null, null, null)))
                .isInstanceOfSatisfying(FhirServerException.class, error -> {
                    assertThat(error.operation()).isEqualTo("condition-create");
                    assertThat(error.type()).isEqualTo(type);
                    assertThat(error.httpStatus()).hasValue(responseStatus);
                    assertThat(error.issues()).containsExactly("error:processing");
                    assertThat(error.getMessage()).doesNotContain("SENSITIVE", "synthetic-patient");
                }));
        assertThat(requests).containsExactly("POST /fhir/Condition");
    }

    @ParameterizedTest
    @CsvSource({"201, /fhir/Patient/wrong", "201, /fhir/Condition/bad_id", "200, /fhir/Condition/server-123"})
    void rejectsInvalidCreateResponses(int responseStatus, String responseLocation) {
        status = responseStatus; location = responseLocation;
        failsInvalidResponse();
    }

    @Test
    void rejectsMissingAssignedId() {
        location = null;
        failsInvalidResponse();
    }

    private void failsInvalidResponse() {
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirConditionClient.class).createCondition(request(null, null, null, null)))
                .isInstanceOfSatisfying(FhirServerException.class, error -> assertThat(error.type()).isEqualTo(FhirServerException.Type.INVALID_RESPONSE)));
        assertThat(requests).hasSize(1);
    }
}
