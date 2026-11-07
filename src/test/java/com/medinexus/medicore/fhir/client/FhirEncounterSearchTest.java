package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.medinexus.medicore.fhir.config.FhirClientConfig;
import com.medinexus.medicore.fhir.mapper.EncounterMapper;
import com.medinexus.medicore.medicalrecord.dto.EncounterDto;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.PeriodValue;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.SubjectReference;
import com.sun.net.httpserver.HttpServer;

/** Patient-filtered Encounter search against a local HTTP server with synthetic data only. */
@ExtendWith(OutputCaptureExtension.class)
class FhirEncounterSearchTest {
    private static final String CLASS = "\"class\":{\"system\":\"http://terminology.hl7.org/CodeSystem/v3-ActCode\",\"code\":\"AMB\"}";
    private HttpServer server;
    private String base;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private Function<String, String> response = query -> bundle("", "");
    private int errorOnRequest = -1;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            int status = requests.size() == errorOnRequest ? 503 : 200;
            String body = status == 200 ? response.apply(exchange.getRequestURI().getRawQuery())
                    : "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"processing\","
                    + "\"diagnostics\":\"SENSITIVE server detail\"}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/fhir";
    }

    @AfterEach
    void stop() { server.stop(0); }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(FhirClientConfig.class,
                FhirRequestExecutor.class, FhirEncounterClient.class, EncounterMapper.class)
                .withPropertyValues("medinexus.fhir.base-url=" + base, "medinexus.fhir.connect-timeout=2s",
                        "medinexus.fhir.socket-timeout=5s", "medinexus.fhir.connection-request-timeout=2s");
    }

    private String encounter(String id, String subject) {
        return encounter(id, subject, "");
    }

    private String encounter(String id, String subject, String extraFields) {
        return "{\"resource\":{\"resourceType\":\"Encounter\",\"id\":\"" + id + "\",\"status\":\"finished\","
                + CLASS + ",\"subject\":{\"reference\":\"" + subject + "\"}" + extraFields + "}}";
    }

    private String bundle(String entries, String next) {
        return "{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"entry\":[" + entries + "]"
                + (next.isEmpty() ? "" : ",\"link\":[{\"relation\":\"next\",\"url\":\"" + next + "\"}]") + "}";
    }

    @Test
    void mapsSingleResultAndSendsOnlyGetWithTypedSubjectAndCount() {
        response = query -> bundle(encounter("e1", "Patient/p1"), "");
        context().run(ctx -> {
            var results = ctx.getBean(FhirEncounterClient.class).findByPatientId("p1");
            assertThat(results).hasSize(1);
            assertThat(results.getFirst().id()).isEqualTo("e1");
            assertThat(results.getFirst().status()).isEqualTo("finished");
            assertThat(results.getFirst().encounterClass().code()).isEqualTo("AMB");
            assertThat(requests).hasSize(1);
            String request = URLDecoder.decode(requests.getFirst(), StandardCharsets.UTF_8);
            assertThat(request).startsWith("GET /fhir/Encounter?");
            assertThat(request.substring(request.indexOf('?') + 1).split("&"))
                    .containsExactlyInAnyOrder("subject=Patient/p1", "_count=100");
        });
    }

    @Test
    void mapsMultipleEncountersPreservingParticipantsPeriodsAndReferences() {
        String participants = ",\"participant\":[{\"type\":[{\"coding\":[{\"system\":"
                + "\"http://terminology.hl7.org/CodeSystem/v3-ParticipationType\",\"code\":\"ATND\"}]}],"
                + "\"individual\":{\"reference\":\"Practitioner/synthetic-practitioner\"}},"
                + "{\"individual\":{\"reference\":\"PractitionerRole/synthetic-role\"}}],"
                + "\"period\":{\"start\":\"2026-06\",\"end\":\"2026-06-15T09:30:00+02:00\"},"
                + "\"location\":[{\"location\":{\"reference\":\"Location/synthetic-room\"}}],"
                + "\"serviceProvider\":{\"reference\":\"Organization/synthetic-clinic\"}";
        response = query -> bundle(encounter("e1", "Patient/p1", participants) + ","
                + encounter("e2", base + "/Patient/p1") + "," + encounter("e3", "Patient/p1"), "");
        context().run(ctx -> {
            List<EncounterDto> results = ctx.getBean(FhirEncounterClient.class).findByPatientId("p1");
            assertThat(results).extracting(EncounterDto::id).containsExactly("e1", "e2", "e3");
            EncounterDto first = results.getFirst();
            assertThat(first.participants()).extracting(participant -> participant.individual().reference())
                    .containsExactly("Practitioner/synthetic-practitioner", "PractitionerRole/synthetic-role");
            assertThat(first.participants().getFirst().types().getFirst().coding().getFirst().code()).isEqualTo("ATND");
            assertThat(first.period()).isEqualTo(new PeriodValue("2026-06", "2026-06-15T09:30:00+02:00"));
            assertThat(first.locations().getFirst().location().reference()).isEqualTo("Location/synthetic-room");
            assertThat(first.serviceProvider()).isEqualTo(new SubjectReference("Organization/synthetic-clinic", null, null, null));
            assertThat(results.get(1).subject().reference()).isEqualTo(base + "/Patient/p1");
            assertThat(results.get(2).participants()).isEmpty();
            assertThatThrownBy(() -> results.add(results.getFirst())).isInstanceOf(UnsupportedOperationException.class);
        });
    }

    @Test
    void returnsEmptyListForEmptySearch() {
        context().run(ctx -> assertThat(ctx.getBean(FhirEncounterClient.class).findByPatientId("p1")).isEmpty());
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"absolute", "relative", "query"})
    void followsServerNextLink(String style) {
        String next = style.equals("absolute") ? base + "/Encounter?page=2"
                : style.equals("relative") ? "/fhir/Encounter?page=2" : "?page=2";
        response = query -> query.equals("page=2") ? bundle(encounter("e2", "Patient/p1"), "")
                : bundle(encounter("e1", "Patient/p1"), next);
        context().run(ctx -> {
            assertThat(ctx.getBean(FhirEncounterClient.class).findByPatientId("p1"))
                    .extracting(EncounterDto::id).containsExactly("e1", "e2");
            assertThat(requests).hasSize(2);
            assertThat(requests.get(1)).isEqualTo("GET /fhir/Encounter?page=2");
            assertThat(requests).allMatch(request -> request.startsWith("GET "));
        });
    }

    @Test
    void invalidPatientIdsMakeNoRequests() {
        context().run(ctx -> {
            for (String id : new String[] {null, "", " ", "Patient/p1", "http://example.test/p1", "p1&x=y", "p_1",
                    "../p1", "p1#x", "ä", "x".repeat(65)}) {
                assertThatThrownBy(() -> ctx.getBean(FhirEncounterClient.class).findByPatientId(id))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("A valid logical FHIR Patient ID is required");
            }
            assertThat(requests).isEmpty();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"Patient/other", "Practitioner/p1", "Group/p1", "http://other.test/fhir/Patient/p1", "#p1", "p1",
            "Patient/p1/_history/1"})
    void rejectsAnyWrongOrUnverifiablePatient(String subject) {
        response = query -> bundle(encounter("e1", "Patient/p1") + "," + encounter("e2", subject), "");
        failsInvalid();
    }

    @Test
    void rejectsSubjectWhoseDeclaredTypeIsNotPatient() {
        response = query -> bundle(encounter("e1", "Patient/p1").replace("\"reference\":\"Patient/p1\"",
                "\"reference\":\"Patient/p1\",\"type\":\"Group\""), "");
        failsInvalid();
    }

    @Test
    void detectsRepeatedNextLinkBeforeRequestingItAgain() {
        response = query -> bundle("", base + "/Encounter?page=2");
        failsInvalid();
        assertThat(requests).hasSize(2);
    }

    @Test
    void wrongPatientOnLaterPageFailsTheWholeSearch() {
        response = query -> query.equals("page=2") ? bundle(encounter("e2", "Patient/other"), "")
                : bundle(encounter("e1", "Patient/p1"), "?page=2");
        failsInvalid();
        assertThat(requests).hasSize(2);
    }

    @Test
    void rejectsAmbiguousMultipleNextLinks() {
        response = query -> """
                {"resourceType":"Bundle","type":"searchset","link":[
                 {"relation":"next","url":"?page=2"},{"relation":"next","url":"?page=3"}]}
                """;
        failsInvalid();
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "badId", "subject", "status", "class", "includeMode"})
    void rejectsMissingOrMalformedEncounterFields(String problem) {
        String entry = encounter("e1", "Patient/p1");
        entry = switch (problem) {
            case "id" -> entry.replace("\"id\":\"e1\",", "");
            case "badId" -> entry.replace("\"id\":\"e1\"", "\"id\":\"bad_id\"");
            case "subject" -> entry.replace(",\"subject\":{\"reference\":\"Patient/p1\"}", "");
            case "status" -> entry.replace("\"status\":\"finished\",", "");
            case "class" -> entry.replace(CLASS + ",", "");
            // Bundle.entry.search sits next to entry.resource, not inside the resource.
            default -> entry.substring(0, entry.length() - 1) + ",\"search\":{\"mode\":\"include\"}}";
        };
        String body = bundle(encounter("e0", "Patient/p1") + "," + entry, "");
        response = query -> body;
        failsInvalid();
    }

    @Test
    void limitsPagesRatherThanSilentlyReturningPartialResults() {
        response = query -> bundle(encounter("e" + requests.size(), "Patient/p1"), base + "/Encounter?page=" + requests.size());
        failsInvalid();
        assertThat(requests).hasSize(10);
    }

    @Test
    void limitsTotalResultsEvenIfServerIgnoresCount() {
        String entries = IntStream.range(0, 1001).mapToObj(i -> encounter("e" + i, "Patient/p1"))
                .collect(Collectors.joining(","));
        response = query -> bundle(entries, "");
        failsInvalid();
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://other.test/fhir?page=2", "/outside?page=2", "/fhir/../outside?page=2",
            "/fhir/%2e%2e/outside?page=2", "/fhir/Encounter?page=2#fragment", "http://user@127.0.0.1/fhir?page=2",
            "http://[broken"})
    void rejectsUnsafeNextLinksBeforeSendingAnotherRequest(String link) {
        response = query -> bundle(encounter("e1", "Patient/p1"), link);
        failsInvalid();
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"resourceType\":\"Bundle\",\"type\":\"collection\"}",
            "{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"entry\":[{}]}",
            "{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"entry\":[{\"resource\":{\"resourceType\":\"Patient\",\"id\":\"p1\"}}]}",
            "{\"resourceType\":\"OperationOutcome\"}",
            "not JSON"
    })
    void rejectsMalformedBundlesAndUnexpectedResources(String body) {
        response = query -> body;
        failsInvalid();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void preservesExternalErrorFromInitialSearchOrLaterPage(int requestNumber) {
        errorOnRequest = requestNumber;
        response = query -> bundle(encounter("e1", "Patient/p1"), base + "/Encounter?page=2");
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirEncounterClient.class).findByPatientId("p1"))
                .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                    assertThat(failure.operation()).isEqualTo("encounter-search");
                    assertThat(failure.type()).isEqualTo(FhirServerException.Type.UNAVAILABLE);
                    assertThat(failure.httpStatus()).hasValue(503);
                    assertThat(failure.issues()).containsExactly("error:processing");
                    assertThat(failure.getMessage()).doesNotContain("SENSITIVE", "Patient/p1", base);
                }));
        assertThat(requests).hasSize(requestNumber);
    }

    @Test
    void failuresAreLoggedWithoutPatientIdsUrlsOrServerDetails(CapturedOutput output) {
        response = query -> query.contains("page=2") ? bundle(encounter("e2", "Patient/someone-else-88"), "")
                : bundle(encounter("e1", "Patient/secret-patient-77"), "?page=2");
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirEncounterClient.class).findByPatientId("secret-patient-77"))
                .isInstanceOf(FhirServerException.class));
        errorOnRequest = requests.size() + 1;
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirEncounterClient.class).findByPatientId("secret-patient-77"))
                .isInstanceOf(FhirServerException.class));

        assertThat(output.getAll())
                .contains("FHIR request failed: operation=encounter-search, type=INVALID_RESPONSE, httpStatus=-, "
                        + "issues=[], cause=DataFormatException, durationMs=")
                .contains("FHIR request failed: operation=encounter-search, type=UNAVAILABLE, httpStatus=503, "
                        + "issues=[error:processing], cause=UnclassifiedServerFailureException, durationMs=")
                .doesNotContain("secret-patient-77")
                .doesNotContain("someone-else-88")
                .doesNotContain("SENSITIVE")
                .doesNotContain("127.0.0.1")
                .doesNotContain("subject=");
        assertThat(output.getAll().split("FHIR request failed", -1)).hasSize(3);
    }

    private void failsInvalid() {
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirEncounterClient.class).findByPatientId("p1"))
                .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                    assertThat(failure.operation()).isEqualTo("encounter-search");
                    assertThat(failure.type()).isEqualTo(FhirServerException.Type.INVALID_RESPONSE);
                    assertThat(failure.getMessage()).doesNotContain("Patient/p1", base, "SENSITIVE");
                    assertThat(failure.getCause()).isNull();
                }));
    }
}
