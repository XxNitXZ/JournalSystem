package com.medinexus.medicore.clinicalapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import com.medinexus.medicore.common.http.ServiceClients;
import com.medinexus.medicore.security.jwt.JwtSecurityConfig;
import com.medinexus.medicore.security.jwt.TestTokens;
import com.sun.net.httpserver.HttpServer;

/** The Clinical API client (C1–C4, docs/architecture.md §12) against a local fake Clinical. */
class ClinicalClientTest {

    private record Answer(int status, String body) {
    }

    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    private final Map<String, String> authorization = new ConcurrentHashMap<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile boolean hang;
    private HttpServer server;
    private ClinicalClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal", exchange -> {
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI();
            authorization.put(key, String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            if (hang) {
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            Answer answer = answers.getOrDefault(key, new Answer(404, "{\"status\":404}"));
            byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(answer.status(), answer.status() == 204 ? -1 : body.length);
            if (answer.status() != 204) exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        ServiceClients clients = new ServiceClients(TestTokens.tokens(), "identity", Duration.ofSeconds(1),
                Duration.ofMillis(400));
        client = new ClinicalClient(clients, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
    }

    @Test
    void c1ReturnsTheRegisteredRecordsWithClinicalsVerdictAndSendsAServiceToken() {
        answers.put("GET /internal/patients/registered?personnummer=182003151236", new Answer(200, """
                {"records":[{"id":"fhir-1","personnummerStatus":"VALID","personnummer":"182003151236"},
                            {"id":"fhir-2","personnummerStatus":"AMBIGUOUS","personnummer":null}]}"""));

        List<ClinicalClient.RegisteredRecord> records = client.registeredRecords("182003151236");

        assertThat(records).containsExactly(
                new ClinicalClient.RegisteredRecord("fhir-1", "VALID", "182003151236"),
                new ClinicalClient.RegisteredRecord("fhir-2", "AMBIGUOUS", null));
        assertThat(records.get(0).matches("182003151236")).isTrue();
        assertThat(records.get(1).matches("182003151236")).isFalse();
        String header = authorization.get("GET /internal/patients/registered?personnummer=182003151236");
        var token = new JwtSecurityConfig().jwtDecoder(TestTokens.properties()).decode(header.substring(7));
        assertThat(token.getSubject()).isEqualTo("identity");
        assertThat(token.getClaimAsString("role")).isEqualTo("SERVICE");
    }

    @Test
    void c2ReturnsTheRecordOrEmptyWhenClinicalDoesNotKnowThePatient() {
        answers.put("GET /internal/patients/fhir-1", new Answer(200, """
                {"id":"fhir-1","givenNames":["Ali"],"familyName":"Khan","personnummerStatus":"VALID",
                 "personnummer":"182003151236","gender":"male","birthDate":"1820-03-15"}"""));
        assertThat(client.patientRecord("fhir-1")).contains(new ClinicalClient.PatientRecord("fhir-1", List.of("Ali"),
                "Khan", "VALID", "182003151236", "male", "1820-03-15"));
        assertThat(client.patientRecord("unknown")).isEmpty();
    }

    @Test
    void c3AndC4AnswerWhetherTheResourceExists() {
        answers.put("POST /internal/registry/fhir-1", new Answer(204, ""));
        answers.put("GET /internal/practitioners/prac-1", new Answer(204, ""));
        assertThat(client.registerPatient("fhir-1")).isTrue();
        assertThat(client.registerPatient("unknown")).isFalse();
        assertThat(client.practitionerExists("prac-1")).isTrue();
        assertThat(client.practitionerExists("unknown")).isFalse();
    }

    @Test
    void errorsOtherThanNotFoundAreThrown() {
        answers.put("GET /internal/patients/fhir-1", new Answer(502, "{\"status\":502}"));
        answers.put("POST /internal/registry/fhir-1", new Answer(503, "{\"status\":503}"));
        assertThatThrownBy(() -> client.patientRecord("fhir-1")).isInstanceOf(RestClientResponseException.class);
        assertThatThrownBy(() -> client.registerPatient("fhir-1")).isInstanceOf(RestClientResponseException.class);
    }

    @Test
    void aClinicalThatNeverAnswersFailsAfterTheReadTimeout() {
        hang = true;
        long started = System.nanoTime();
        assertThatThrownBy(() -> client.patientRecord("fhir-1")).isInstanceOf(ResourceAccessException.class);
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(3_000L);
    }
}
