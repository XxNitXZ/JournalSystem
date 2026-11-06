package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.medinexus.medicore.fhir.config.FhirClientConfig;
import com.medinexus.medicore.fhir.mapper.ConditionMapper;
import com.medinexus.medicore.fhir.mapper.ObservationMapper;
import com.medinexus.medicore.medicalrecord.dto.CreateConditionRequest;
import com.medinexus.medicore.medicalrecord.dto.CreateObservationRequest;
import com.medinexus.medicore.medicalrecord.dto.ConditionDto;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Coding;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ca.uhn.fhir.context.FhirContext;

/** Stateful local transport test: no database and no external FHIR server. */
class FhirClinicalRoundTripTest {
    private static final FhirContext CONTEXT = FhirContext.forR4();
    private final Map<String, Resource> stored = new ConcurrentSkipListMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();
    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fhir/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/fhir";
    }

    @AfterEach
    void stop() { server.stop(0); }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath().substring("/fhir/".length());
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
        if (exchange.getRequestMethod().equals("POST") && (path.equals("Observation") || path.equals("Condition"))) {
            Resource resource = (Resource) CONTEXT.newJsonParser().parseResource(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String id = "local-" + ids.incrementAndGet();
            resource.setId(id);
            stored.put(path + "/" + id, resource);
            exchange.getResponseHeaders().set("Location", base + "/" + path + "/" + id + "/_history/1");
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
            return;
        }
        if (exchange.getRequestMethod().equals("GET") && !path.contains("/")) {
            String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            String subject = java.util.Arrays.stream(query.split("&")).filter(p -> p.startsWith("subject="))
                    .map(p -> p.substring("subject=".length())).findFirst().orElse("");
            Bundle bundle = new Bundle().setType(Bundle.BundleType.SEARCHSET);
            stored.forEach((key, resource) -> {
                String reference = resource instanceof Observation observation ? observation.getSubject().getReference()
                        : ((Condition) resource).getSubject().getReference();
                if (key.startsWith(path + "/") && subject.equals(reference)) bundle.addEntry().setResource(resource);
            });
            send(exchange, 200, bundle);
            return;
        }
        Resource resource = stored.get(path);
        if (exchange.getRequestMethod().equals("GET") && resource != null) {
            send(exchange, 200, resource);
        } else {
            OperationOutcome outcome = new OperationOutcome();
            outcome.addIssue().setSeverity(OperationOutcome.IssueSeverity.ERROR).setCode(OperationOutcome.IssueType.NOTFOUND);
            send(exchange, 404, outcome);
        }
    }

    private void send(HttpExchange exchange, int status, Resource resource) throws IOException {
        byte[] bytes = CONTEXT.newJsonParser().encodeResourceToString(resource).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(FhirClientConfig.class, FhirRequestExecutor.class,
                FhirObservationClient.class, ObservationMapper.class, FhirConditionClient.class, ConditionMapper.class)
                .withPropertyValues("medinexus.fhir.base-url=" + base, "medinexus.fhir.connect-timeout=2s",
                        "medinexus.fhir.socket-timeout=3s", "medinexus.fhir.connection-request-timeout=2s");
    }

    private Concept code() {
        return new Concept("Synthetic clinical code", List.of(new Coding("urn:synthetic:code", "1", "test", "Example", false)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"quantity", "coded", "absent"})
    void observationCreateReadAndPatientSearchUseStoredResource(String type) {
        CreateObservationRequest.Value value = switch (type) {
            case "quantity" -> new CreateObservationRequest.QuantityValue(new BigDecimal("12.500"), "mg",
                    "http://unitsofmeasure.org", "mg", CreateObservationRequest.Comparator.LESS_THAN);
            case "coded" -> new CreateObservationRequest.CodedValue(code());
            default -> null;
        };
        Concept absent = type.equals("absent") ? new Concept("Not measured", List.of()) : null;
        context().run(ctx -> {
            var client = ctx.getBean(FhirObservationClient.class);
            String id = client.createObservation(new CreateObservationRequest("synthetic-patient", CreateObservationRequest.Status.FINAL,
                    code(), value, absent));
            var resource = client.readObservation(id);
            var dto = ctx.getBean(ObservationMapper.class).toDto(resource);
            assertThat(dto.id()).isEqualTo(id);
            assertThat(dto.subject().reference()).isEqualTo("Patient/synthetic-patient");
            assertThat(dto.code()).isEqualTo(code());
            assertThat(dto.status()).isEqualTo("final");
            if (type.equals("quantity")) {
                assertThat(dto.value().data()).isEqualTo(new ObservationDto.QuantityValue(new BigDecimal("12.500"), "<", "mg",
                        "http://unitsofmeasure.org", "mg"));
            } else if (type.equals("coded")) {
                assertThat(dto.value().data()).isEqualTo(code());
            } else {
                assertThat(dto.value()).isNull();
                assertThat(dto.dataAbsentReason()).isEqualTo(absent);
            }
            client.createObservation(new CreateObservationRequest("other-synthetic-patient", CreateObservationRequest.Status.FINAL,
                    code(), value, absent));
            assertThat(client.findByPatientId("synthetic-patient")).containsExactly(dto);
            assertThat(requests).hasSize(4);
            assertThat(requests.get(0)).isEqualTo("POST /fhir/Observation");
            assertThat(requests.get(1)).isEqualTo("GET /fhir/Observation/" + id);
            assertThat(requests.get(2)).isEqualTo("POST /fhir/Observation");
            assertThat(requests.get(3)).startsWith("GET /fhir/Observation?");
        });
    }

    @Test
    void conditionCreateReadAndPatientSearchPreserveStatusesAndTemporalPrecision() {
        context().run(ctx -> {
            var client = ctx.getBean(FhirConditionClient.class);
            var input = new CreateConditionRequest("synthetic-patient", code(), CreateConditionRequest.ClinicalStatus.RESOLVED,
                    CreateConditionRequest.VerificationStatus.CONFIRMED, List.of(code()), code(), "2020-06", "2026-06-15T12:34:56.123+02:00");
            String id = client.createCondition(input);
            var resource = client.readCondition(id);
            var dto = ctx.getBean(ConditionMapper.class).toDto(resource);
            assertThat(dto.id()).isEqualTo(id);
            assertThat(dto.subject().reference()).isEqualTo("Patient/synthetic-patient");
            assertThat(dto.code()).isEqualTo(code());
            assertThat(dto.clinicalStatus().coding().getFirst().system()).isEqualTo(CreateConditionRequest.ClinicalStatus.SYSTEM);
            assertThat(dto.clinicalStatus().coding().getFirst().code()).isEqualTo("resolved");
            assertThat(dto.verificationStatus().coding().getFirst().system()).isEqualTo(CreateConditionRequest.VerificationStatus.SYSTEM);
            assertThat(dto.verificationStatus().coding().getFirst().code()).isEqualTo("confirmed");
            assertThat(dto.category()).containsExactly(code());
            assertThat(dto.severity()).isEqualTo(code());
            assertThat(resource.getOnsetDateTimeType().getValueAsString()).isEqualTo(input.onsetDateTime());
            assertThat(resource.getAbatementDateTimeType().getValueAsString()).isEqualTo(input.abatementDateTime());
            assertThat(dto.onset()).isEqualTo(new ConditionDto.TemporalValue(ConditionDto.TemporalType.DATE_TIME,
                    new ConditionDto.TextValue(input.onsetDateTime())));
            assertThat(dto.abatement()).isEqualTo(new ConditionDto.TemporalValue(ConditionDto.TemporalType.DATE_TIME,
                    new ConditionDto.TextValue(input.abatementDateTime())));
            client.createCondition(new CreateConditionRequest("other-synthetic-patient", code(), null, null, null, null, null, null));
            assertThat(client.findByPatientId("synthetic-patient")).containsExactly(dto);
            assertThat(requests).hasSize(4);
            assertThat(requests.get(0)).isEqualTo("POST /fhir/Condition");
            assertThat(requests.get(1)).isEqualTo("GET /fhir/Condition/" + id);
            assertThat(requests.get(2)).isEqualTo("POST /fhir/Condition");
            assertThat(requests.get(3)).startsWith("GET /fhir/Condition?");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"Observation", "Condition"})
    void missingStoredResourceUsesExisting404Translation(String type) {
        context().run(ctx -> assertThatThrownBy(() -> {
            if (type.equals("Observation")) ctx.getBean(FhirObservationClient.class).readObservation("missing");
            else ctx.getBean(FhirConditionClient.class).readCondition("missing");
        }).isInstanceOfSatisfying(FhirServerException.class, error -> {
            assertThat(error.type()).isEqualTo(FhirServerException.Type.CLIENT_ERROR);
            assertThat(error.httpStatus()).hasValue(404);
            assertThat(error.issues()).containsExactly("error:not-found");
        }));
        assertThat(requests).containsExactly("GET /fhir/" + type + "/missing");
        assertThat(stored).isEmpty();
    }
}
