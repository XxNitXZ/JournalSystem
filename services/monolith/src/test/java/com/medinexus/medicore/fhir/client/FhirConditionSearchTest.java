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
import com.medinexus.medicore.fhir.mapper.ConditionMapper;
import com.sun.net.httpserver.HttpServer;

class FhirConditionSearchTest {
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
                FhirRequestExecutor.class, FhirConditionClient.class, ConditionMapper.class)
                .withPropertyValues("medinexus.fhir.base-url=" + base, "medinexus.fhir.connect-timeout=2s",
                        "medinexus.fhir.socket-timeout=5s", "medinexus.fhir.connection-request-timeout=2s");
    }

    private String condition(String id, String subject) {
        return "{\"resource\":{\"resourceType\":\"Condition\",\"id\":\"" + id
                + "\",\"code\":{\"text\":\"Synthetic diagnosis\"},\"subject\":{\"reference\":\""
                + subject + "\"}}}";
    }

    private String bundle(String entries, String next) {
        return "{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"entry\":[" + entries + "]"
                + (next.isEmpty() ? "" : ",\"link\":[{\"relation\":\"next\",\"url\":\"" + next + "\"}]") + "}";
    }

    @Test
    void mapsSingleResultAndSendsOnlyGetWithTypedSubjectAndCount() {
        response = query -> bundle(condition("o1", "Patient/p1"), "");
        context().run(ctx -> {
            var results = ctx.getBean(FhirConditionClient.class).findByPatientId("p1");
            assertThat(results).hasSize(1);
            assertThat(results.getFirst().id()).isEqualTo("o1");
            assertThat(results.getFirst().code().text()).isEqualTo("Synthetic diagnosis");
            assertThat(requests).hasSize(1);
            String request = URLDecoder.decode(requests.getFirst(), StandardCharsets.UTF_8);
            assertThat(request).startsWith("GET /fhir/Condition?");
            assertThat(request.substring(request.indexOf('?') + 1).split("&"))
                    .containsExactlyInAnyOrder("subject=Patient/p1", "_count=100");
        });
    }

    @Test
    void mapsMultipleResultsIncludingSameServerAbsolutePatientReference() {
        response = query -> bundle(condition("o1", "Patient/p1") + "," + condition("o2", base + "/Patient/p1"), "");
        context().run(ctx -> assertThat(ctx.getBean(FhirConditionClient.class).findByPatientId("p1"))
                .extracting(dto -> dto.id()).containsExactly("o1", "o2"));
    }

    @Test
    void returnsEmptyListForEmptySearch() {
        context().run(ctx -> assertThat(ctx.getBean(FhirConditionClient.class).findByPatientId("p1")).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"absolute", "relative", "query"})
    void followsServerNextLink(String style) {
        String next = style.equals("absolute") ? base + "/Condition?page=2"
                : style.equals("relative") ? "/fhir/Condition?page=2" : "?page=2";
        response = query -> query.equals("page=2") ? bundle(condition("o2", "Patient/p1"), "")
                : bundle(condition("o1", "Patient/p1"), next);
        context().run(ctx -> {
            assertThat(ctx.getBean(FhirConditionClient.class).findByPatientId("p1"))
                    .extracting(dto -> dto.id()).containsExactly("o1", "o2");
            assertThat(requests).hasSize(2);
            assertThat(requests.get(1)).isEqualTo("GET /fhir/Condition?page=2");
            assertThat(requests).allMatch(request -> request.startsWith("GET "));
        });
    }

    @Test
    void invalidPatientIdsMakeNoRequests() {
        context().run(ctx -> {
            for (String id : new String[] {null, "", " ", "Patient/p1", "http://example.test/p1", "p1&x=y", "p_1", "x".repeat(65)}) {
                assertThatThrownBy(() -> ctx.getBean(FhirConditionClient.class).findByPatientId(id))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(requests).isEmpty();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"Patient/other", "Practitioner/p1", "http://other.test/fhir/Patient/p1", "#p1", "p1", "Patient/p1/_history/1"})
    void rejectsAnyWrongOrUnverifiablePatient(String subject) {
        response = query -> bundle(condition("o1", "Patient/p1") + "," + condition("o2", subject), "");
        failsInvalid();
    }

    @Test
    void detectsRepeatedNextLinkBeforeRequestingItAgain() {
        response = query -> bundle("", base + "/Condition?page=2");
        failsInvalid();
        assertThat(requests).hasSize(2);
    }

    @Test
    void wrongPatientOnLaterPageFailsTheWholeSearch() {
        response = query -> query.equals("page=2") ? bundle(condition("o2", "Patient/other"), "")
                : bundle(condition("o1", "Patient/p1"), "?page=2");
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
    @ValueSource(strings = {"id", "subject"})
    void rejectsMissingConditionIdentityOrSubject(String missing) {
        String entry = condition("o1", "Patient/p1");
        entry = switch (missing) {
            case "id" -> entry.replace("\"id\":\"o1\",", "");
            default -> entry.replace(",\"subject\":{\"reference\":\"Patient/p1\"}", "");
        };
        String body = bundle(entry, "");
        response = query -> body;
        failsInvalid();
    }

    @Test
    void limitsPagesRatherThanSilentlyReturningPartialResults() {
        response = query -> bundle("", base + "/Condition?page=" + requests.size());
        failsInvalid();
        assertThat(requests).hasSize(10);
    }

    @Test
    void limitsTotalResultsEvenIfServerIgnoresCount() {
        String entries = java.util.stream.IntStream.range(0, 1001)
                .mapToObj(i -> condition("o" + i, "Patient/p1")).collect(java.util.stream.Collectors.joining(","));
        response = query -> bundle(entries, "");
        failsInvalid();
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://other.test/fhir?page=2", "/outside?page=2", "/fhir/../outside?page=2",
            "/fhir/%2e%2e/outside?page=2", "/fhir/Condition?page=2#fragment", "http://[broken"})
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

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void preservesExternalErrorFromInitialSearchOrLaterPage(int requestNumber) {
        errorOnRequest = requestNumber;
        response = query -> bundle(condition("o1", "Patient/p1"), base + "/Condition?page=2");
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirConditionClient.class).findByPatientId("p1"))
                .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                    assertThat(failure.operation()).isEqualTo("condition-search");
                    assertThat(failure.type()).isEqualTo(FhirServerException.Type.UNAVAILABLE);
                    assertThat(failure.httpStatus()).hasValue(503);
                }));
        assertThat(requests).hasSize(requestNumber);
    }

    private void failsInvalid() {
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirConditionClient.class).findByPatientId("p1"))
                .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                    assertThat(failure.type()).isEqualTo(FhirServerException.Type.INVALID_RESPONSE);
                    assertThat(failure.getMessage()).doesNotContain("Patient/p1", base, "SENSITIVE");
                    assertThat(failure.getCause()).isNull();
                }));
    }
}
