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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.medinexus.medicore.fhir.config.FhirClientConfig;
import com.medinexus.medicore.fhir.mapper.ObservationMapper;
import com.sun.net.httpserver.HttpServer;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class FhirObservationSearchTest {
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
                    : "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"processing\"}]}";
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
                FhirRequestExecutor.class, FhirObservationClient.class, ObservationMapper.class)
                .withPropertyValues("medinexus.fhir.base-url=" + base, "medinexus.fhir.connect-timeout=2s",
                        "medinexus.fhir.socket-timeout=5s", "medinexus.fhir.connection-request-timeout=2s");
    }

    private String observation(String id, String subject) {
        return "{\"resource\":{\"resourceType\":\"Observation\",\"id\":\"" + id
                + "\",\"status\":\"final\",\"code\":{\"text\":\"Synthetic\"},\"subject\":{\"reference\":\""
                + subject + "\"},\"valueBoolean\":false}}";
    }

    private String bundle(String entries, String next) {
        return "{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"entry\":[" + entries + "]"
                + (next.isEmpty() ? "" : ",\"link\":[{\"relation\":\"next\",\"url\":\"" + next + "\"}]") + "}";
    }

    @Test
    void mapsSingleResultAndSendsOnlyGetWithTypedSubjectAndCount() {
        response = query -> bundle(observation("o1", "Patient/p1"), "");
        context().run(ctx -> {
            var results = ctx.getBean(FhirObservationClient.class).findByPatientId("p1").observations();
            assertThat(results).hasSize(1);
            assertThat(results.getFirst().id()).isEqualTo("o1");
            assertThat(results.getFirst().value().type().name()).isEqualTo("BOOLEAN");
            assertThat(requests).hasSize(1);
            String request = URLDecoder.decode(requests.getFirst(), StandardCharsets.UTF_8);
            assertThat(request).startsWith("GET /fhir/Observation?");
            assertThat(request.substring(request.indexOf('?') + 1).split("&"))
                    .containsExactlyInAnyOrder("subject=Patient/p1", "_count=100");
        });
    }

    @Test
    void mapsMultipleResultsIncludingSameServerAbsolutePatientReference() {
        response = query -> bundle(observation("o1", "Patient/p1") + "," + observation("o2", base + "/Patient/p1"), "");
        context().run(ctx -> assertThat(ctx.getBean(FhirObservationClient.class).findByPatientId("p1").observations())
                .extracting(dto -> dto.id()).containsExactly("o1", "o2"));
    }

    @Test
    void returnsEmptyListForEmptySearch() {
        context().run(ctx -> assertThat(ctx.getBean(FhirObservationClient.class).findByPatientId("p1").observations()).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"absolute", "relative", "query"})
    void followsServerNextLink(String style) {
        String next = style.equals("absolute") ? base + "/Observation?page=2"
                : style.equals("relative") ? "/fhir/Observation?page=2" : "?page=2";
        response = query -> query.equals("page=2") ? bundle(observation("o2", "Patient/p1"), "")
                : bundle(observation("o1", "Patient/p1"), next);
        context().run(ctx -> {
            assertThat(ctx.getBean(FhirObservationClient.class).findByPatientId("p1").observations())
                    .extracting(dto -> dto.id()).containsExactly("o1", "o2");
            assertThat(requests).hasSize(2);
            assertThat(requests.get(1)).isEqualTo("GET /fhir/Observation?page=2");
            assertThat(requests).allMatch(request -> request.startsWith("GET "));
        });
    }

    @Test
    void invalidPatientIdsMakeNoRequests() {
        context().run(ctx -> {
            for (String id : new String[] {null, "", " ", "Patient/p1", "http://example.test/p1", "p1&x=y", "p_1", "x".repeat(65)}) {
                assertThatThrownBy(() -> ctx.getBean(FhirObservationClient.class).findByPatientId(id))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(requests).isEmpty();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"Patient/other", "Practitioner/p1", "http://other.test/fhir/Patient/p1", "#p1", "p1", "Patient/p1/_history/1"})
    void rejectsAnyWrongOrUnverifiablePatient(String subject) {
        response = query -> bundle(observation("o1", "Patient/p1") + "," + observation("o2", subject), "");
        failsInvalid();
    }

    @Test
    void detectsRepeatedNextLinkBeforeRequestingItAgain() {
        response = query -> bundle("", base + "/Observation?page=2");
        failsInvalid();
        assertThat(requests).hasSize(2);
    }

    @Test
    void wrongPatientOnLaterPageFailsTheWholeSearch() {
        response = query -> query.equals("page=2") ? bundle(observation("o2", "Patient/other"), "")
                : bundle(observation("o1", "Patient/p1"), "?page=2");
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
    @ValueSource(strings = {"id", "code", "subject"})
    void rejectsMissingObservationIdentityCodeOrSubject(String missing) {
        String entry = observation("o1", "Patient/p1");
        entry = switch (missing) {
            case "id" -> entry.replace("\"id\":\"o1\",", "");
            case "code" -> entry.replace("\"code\":{\"text\":\"Synthetic\"},", "");
            default -> entry.replace("\"subject\":{\"reference\":\"Patient/p1\"},", "");
        };
        String body = bundle(entry, "");
        response = query -> body;
        failsInvalid();
    }

    @Test
    void limitsPagesRatherThanSilentlyReturningPartialResults() {
        response = query -> bundle("", base + "/Observation?page=" + requests.size());
        failsInvalid();
        assertThat(requests).hasSize(10);
    }

    @Test
    void limitsTotalResultsEvenIfServerIgnoresCount() {
        String entries = java.util.stream.IntStream.range(0, 1001)
                .mapToObj(i -> observation("o" + i, "Patient/p1")).collect(java.util.stream.Collectors.joining(","));
        response = query -> bundle(entries, "");
        failsInvalid();
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://other.test/fhir?page=2", "/outside?page=2", "/fhir/../outside?page=2",
            "/fhir/%2e%2e/outside?page=2", "/fhir/Observation?page=2#fragment", "http://[broken"})
    void rejectsUnsafeNextLinksBeforeSendingAnotherRequest(String link) {
        response = query -> bundle("", link);
        failsInvalid();
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"resourceType\":\"Bundle\",\"type\":\"collection\"}",
            "{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"entry\":[{}]}",
            "{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"entry\":[{\"resource\":{\"resourceType\":\"Patient\",\"id\":\"p1\"}}]}",
            "not JSON"
    })
    void rejectsMalformedBundlesAndUnexpectedResources(String body) {
        response = query -> body;
        failsInvalid();
    }

    @Test
    void rejectsMalformedOrUnsupportedObservationsWithoutPartialResults() {
        response = query -> bundle(observation("o1", "Patient/p1").replace("\"valueBoolean\":false", "\"valueSampledData\":{\"dimensions\":1}"), "");
        failsInvalid();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void preservesExternalErrorFromInitialSearchOrLaterPage(int requestNumber) {
        errorOnRequest = requestNumber;
        response = query -> bundle(observation("o1", "Patient/p1"), base + "/Observation?page=2");
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirObservationClient.class).findByPatientId("p1"))
                .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                    assertThat(failure.operation()).isEqualTo("observation-search");
                    assertThat(failure.type()).isEqualTo(FhirServerException.Type.UNAVAILABLE);
                    assertThat(failure.httpStatus()).hasValue(503);
                }));
        assertThat(requests).hasSize(requestNumber);
    }

    @Test
    void reportsMissingStatusAndContinuesLaterEntries(org.springframework.boot.test.system.CapturedOutput output) {
        response = query -> bundle(observation("valid-before", "Patient/p1") + ","
                + missingStatus("SECRET-OBS-ID", "Patient/p1") + "," + observation("valid-after", "Patient/p1"), "");
        context().run(ctx -> {
            var result = ctx.getBean(FhirObservationClient.class).findByPatientId("p1");
            assertThat(result.observations()).extracting(dto -> dto.id()).containsExactly("valid-before", "valid-after");
            assertThat(result.complete()).isFalse();
            assertThat(result.excludedCount()).isEqualTo(1);
        });
        assertThat(output.getAll()).contains("reason=MISSING_STATUS excludedCount=1")
                .doesNotContain("SECRET-OBS-ID", "Patient/p1");
    }

    @Test
    void followsAllPagesAfterExclusions() {
        response = query -> query.equals("page=2")
                ? bundle(missingStatus("bad-2", "Patient/p1") + "," + observation("good-2", "Patient/p1"), "")
                : bundle(missingStatus("bad-1", "Patient/p1"), "?page=2");
        context().run(ctx -> {
            var result = ctx.getBean(FhirObservationClient.class).findByPatientId("p1");
            assertThat(result.observations()).extracting(dto -> dto.id()).containsExactly("good-2");
            assertThat(result.complete()).isFalse();
            assertThat(result.excludedCount()).isEqualTo(2);
        });
        assertThat(requests).hasSize(2);
    }

    @Test
    void distinguishesAllExcludedFromGenuinelyEmpty() {
        response = query -> bundle(missingStatus("bad", "Patient/p1"), "");
        context().run(ctx -> {
            var result = ctx.getBean(FhirObservationClient.class).findByPatientId("p1");
            assertThat(result.observations()).isEmpty();
            assertThat(result.complete()).isFalse();
            assertThat(result.excludedCount()).isEqualTo(1);
        });
        response = query -> bundle("", "");
        context().run(ctx -> {
            var result = ctx.getBean(FhirObservationClient.class).findByPatientId("p1");
            assertThat(result.observations()).isEmpty();
            assertThat(result.complete()).isTrue();
            assertThat(result.excludedCount()).isZero();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"Patient/other", "Practitioner/p1", "#p1", "Patient/p1/_history/1"})
    void missingStatusNeverBypassesPatientValidation(String subject) {
        response = query -> bundle(missingStatus("bad", subject), "");
        failsInvalid();
    }

    @ParameterizedTest
    @ValueSource(strings = {"code", "id", "subject", "value", "effective", "component", "conflict"})
    void missingStatusDoesNotHideOtherInvalidContent(String field) {
        String entry = missingStatus("bad", "Patient/p1");
        entry = switch (field) {
            case "code" -> entry.replace("\"code\":{\"text\":\"Synthetic\"},", "");
            case "id" -> entry.replace("\"id\":\"bad\",", "");
            case "subject" -> entry.replace("\"subject\":{\"reference\":\"Patient/p1\"},", "");
            case "value" -> entry.replace("\"valueBoolean\":false", "\"valueSampledData\":{\"dimensions\":1}");
            case "effective" -> entry.replace("\"valueBoolean\":false", "\"effectiveTiming\":{\"event\":[\"2026\"]}");
            case "component" -> entry.replace("\"valueBoolean\":false", "\"component\":[{\"valueBoolean\":false}]");
            default -> entry.replace("\"valueBoolean\":false", "\"valueBoolean\":false,\"dataAbsentReason\":{\"text\":\"Unknown\"}");
        };
        String body = bundle(entry, "");
        response = query -> body;
        failsInvalid();
    }

    @Test
    void excludedResourcesCountTowardLimit() {
        String entries = java.util.stream.IntStream.range(0, 1000).mapToObj(i -> missingStatus("bad-" + i, "Patient/p1"))
                .collect(java.util.stream.Collectors.joining(","));
        response = query -> bundle(entries, "");
        context().run(ctx -> {
            var result = ctx.getBean(FhirObservationClient.class).findByPatientId("p1");
            assertThat(result.observations()).isEmpty();
            assertThat(result.excludedCount()).isEqualTo(1000);
            assertThat(result.complete()).isFalse();
        });
        response = query -> bundle(entries + "," + observation("overflow", "Patient/p1"), "");
        failsInvalid();
    }

    @ParameterizedTest
    @ValueSource(strings = {"parse", "subject", "next", "resource", "limit"})
    void laterUnsafeResponsesFailClosedEvenAfterExclusions(String reason) {
        response = query -> {
            if (!query.equals("page=2")) return bundle(missingStatus("bad", "Patient/p1"), "?page=2");
            return switch (reason) {
                case "parse" -> "not JSON";
                case "subject" -> bundle(observation("wrong", "Patient/other"), "");
                case "resource" -> bundle("{\"resource\":{\"resourceType\":\"Patient\",\"id\":\"p1\"}}", "");
                case "limit" -> bundle("", "?page=2");
                default -> bundle("", "https://other.test/fhir/Observation?page=3");
            };
        };
        failsInvalid();
    }

    @Test
    void externalFailureAfterExclusionDoesNotReturnPartialEnvelope() {
        errorOnRequest = 2;
        response = query -> bundle(missingStatus("bad", "Patient/p1"), "?page=2");
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirObservationClient.class).findByPatientId("p1"))
                .isInstanceOfSatisfying(FhirServerException.class, failure -> assertThat(failure.type()).isEqualTo(FhirServerException.Type.UNAVAILABLE)));
    }

    private String missingStatus(String id, String subject) {
        return observation(id, subject).replace("\"status\":\"final\",", "");
    }

    private void failsInvalid() {
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirObservationClient.class).findByPatientId("p1"))
                .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                    assertThat(failure.type()).isEqualTo(FhirServerException.Type.INVALID_RESPONSE);
                    assertThat(failure.getMessage()).doesNotContain("Patient/p1", base, "SENSITIVE");
                    assertThat(failure.getCause()).isNull();
                }));
    }
}
