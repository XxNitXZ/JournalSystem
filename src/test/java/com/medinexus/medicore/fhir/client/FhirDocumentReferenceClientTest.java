package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Reference;
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
import com.medinexus.medicore.fhir.mapper.ClinicalNoteMapper;
import com.sun.net.httpserver.HttpServer;

import ca.uhn.fhir.context.FhirContext;

/** US-05.01: DocumentReference create and Patient-filtered search against a local HTTP server, synthetic data only. */
@ExtendWith(OutputCaptureExtension.class)
class FhirDocumentReferenceClientTest {

    private HttpServer server;
    private String base;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private Function<String, String> response = query -> bundle("", "");
    private int createStatus = 201;
    private String createLocation;
    private int errorOnRequest = -1;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
            if (requests.size() == errorOnRequest) {
                byte[] bytes = ("{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\","
                        + "\"code\":\"processing\",\"diagnostics\":\"SENSITIVE server detail\"}]}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(503, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
                return;
            }
            if (exchange.getRequestMethod().equals("POST")) {
                if (createLocation != null) exchange.getResponseHeaders().set("Location", createLocation);
                exchange.sendResponseHeaders(createStatus, -1);
                exchange.close();
                return;
            }
            byte[] bytes = response.apply(exchange.getRequestURI().getRawQuery()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/fhir";
        createLocation = base + "/DocumentReference/note-1/_history/1";
    }

    @AfterEach
    void stop() { server.stop(0); }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(FhirClientConfig.class,
                FhirRequestExecutor.class, FhirDocumentReferenceClient.class)
                .withPropertyValues("medinexus.fhir.base-url=" + base, "medinexus.fhir.connect-timeout=2s",
                        "medinexus.fhir.socket-timeout=5s", "medinexus.fhir.connection-request-timeout=2s");
    }

    private static String note(String id, String subject) {
        return "{\"resource\":{\"resourceType\":\"DocumentReference\",\"id\":\"" + id + "\",\"status\":\"current\","
                + "\"subject\":{\"reference\":\"" + subject + "\"},\"content\":[{\"attachment\":{\"contentType\":"
                + "\"text/plain\",\"data\":\"SGVq\"}}]}}";
    }

    private static String bundle(String entries, String next) {
        return "{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"entry\":[" + entries + "]"
                + (next.isEmpty() ? "" : ",\"link\":[{\"relation\":\"next\",\"url\":\"" + next + "\"}]") + "}";
    }

    // ---------- create ----------

    @Test
    void createsTheNoteWithOnePostAndReturnsTheAssignedId() {
        DocumentReference resource = new ClinicalNoteMapper().toResource("p1", "e1", "pr1", "Mår bättre.",
                Instant.parse("2026-11-12T09:30:00Z"));
        context().run(ctx -> {
            assertThat(ctx.getBean(FhirDocumentReferenceClient.class).createDocumentReference(resource))
                    .isEqualTo("note-1");
            assertThat(requests).containsExactly("POST /fhir/DocumentReference");
            DocumentReference sent = FhirContext.forR4().newJsonParser()
                    .parseResource(DocumentReference.class, bodies.getFirst());
            assertThat(sent.getSubject().getReference()).isEqualTo("Patient/p1");
            assertThat(sent.getContext().getEncounterFirstRep().getReference()).isEqualTo("Encounter/e1");
            assertThat(sent.getAuthorFirstRep().getReference()).isEqualTo("Practitioner/pr1");
            assertThat(new String(sent.getContentFirstRep().getAttachment().getData(), StandardCharsets.UTF_8))
                    .isEqualTo("Mår bättre.");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"200", "missing", "otherType", "badId"})
    void createResponsesThatDoNotConfirmANewNoteAreInvalid(String problem) {
        switch (problem) {
            case "200" -> createStatus = 200;
            case "missing" -> createLocation = null;
            case "otherType" -> createLocation = base + "/Patient/note-1/_history/1";
            default -> createLocation = base + "/DocumentReference/bad_id/_history/1";
        }
        DocumentReference resource = new ClinicalNoteMapper().toResource("p1", "e1", "pr1", "x", Instant.now());
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirDocumentReferenceClient.class)
                .createDocumentReference(resource)).isInstanceOfSatisfying(FhirServerException.class, failure -> {
                    assertThat(failure.operation()).isEqualTo("document-reference-create");
                    assertThat(failure.type()).isEqualTo(FhirServerException.Type.INVALID_RESPONSE);
                }));
        assertThat(requests).hasSize(1);
    }

    @Test
    void createFailuresAreTranslatedAndLoggedWithoutNoteText(CapturedOutput output) {
        errorOnRequest = 1;
        DocumentReference resource = new ClinicalNoteMapper().toResource("p1", "e1", "pr1", "Hemlig anteckning",
                Instant.now());
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirDocumentReferenceClient.class)
                .createDocumentReference(resource)).isInstanceOfSatisfying(FhirServerException.class,
                        failure -> assertThat(failure.type()).isEqualTo(FhirServerException.Type.UNAVAILABLE)));
        assertThat(output.getAll()).contains("operation=document-reference-create, type=UNAVAILABLE")
                .doesNotContain("Hemlig", "SENSITIVE", "p1");
        context().run(ctx -> {
            DocumentReference withId = resource.copy();
            withId.setId("chosen");
            assertThatThrownBy(() -> ctx.getBean(FhirDocumentReferenceClient.class).createDocumentReference(withId))
                    .isInstanceOf(IllegalArgumentException.class);
        });
    }

    // ---------- search ----------

    @Test
    void searchesBySubjectWithCountAndReturnsOnlyThisPatientsNotes() {
        response = query -> bundle(note("n1", "Patient/p1") + "," + note("n2", base + "/Patient/p1"), "");
        context().run(ctx -> {
            List<DocumentReference> found = ctx.getBean(FhirDocumentReferenceClient.class).findByPatientId("p1");
            assertThat(found).extracting(note -> note.getIdElement().getIdPart()).containsExactly("n1", "n2");
            String request = URLDecoder.decode(requests.getFirst(), StandardCharsets.UTF_8);
            assertThat(request).startsWith("GET /fhir/DocumentReference?");
            assertThat(request.substring(request.indexOf('?') + 1).split("&"))
                    .containsExactlyInAnyOrder("subject=Patient/p1", "_count=100");
        });
    }

    @Test
    void anEmptySearchIsAnEmptyList() {
        context().run(ctx -> assertThat(ctx.getBean(FhirDocumentReferenceClient.class).findByPatientId("p1")).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Patient/other", "Group/p1", "http://other.test/fhir/Patient/p1", "#p1", "p1",
            "Patient/p1/_history/1"})
    void anotherOrUnverifiablePatientFailsTheWholeSearch(String subject) {
        response = query -> bundle(note("n1", "Patient/p1") + "," + note("n2", subject), "");
        failsInvalid();
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "badId", "duplicate", "subject", "otherType", "includeMode", "declaredType"})
    void malformedEntriesFailTheSearch(String problem) {
        String entry = switch (problem) {
            case "id" -> note("n2", "Patient/p1").replace("\"id\":\"n2\",", "");
            case "badId" -> note("bad_id", "Patient/p1");
            case "duplicate" -> note("n1", "Patient/p1");
            case "subject" -> note("n2", "Patient/p1").replace(",\"subject\":{\"reference\":\"Patient/p1\"}", "");
            case "otherType" -> "{\"resource\":{\"resourceType\":\"Observation\",\"id\":\"o1\",\"status\":\"final\","
                    + "\"code\":{\"text\":\"x\"},\"subject\":{\"reference\":\"Patient/p1\"}}}";
            case "declaredType" -> note("n2", "Patient/p1").replace("\"reference\":\"Patient/p1\"",
                    "\"reference\":\"Patient/p1\",\"type\":\"Group\"");
            default -> note("n2", "Patient/p1").substring(0, note("n2", "Patient/p1").length() - 1)
                    + ",\"search\":{\"mode\":\"include\"}}";
        };
        String body = bundle(note("n1", "Patient/p1") + "," + entry, "");
        response = query -> body;
        failsInvalid();
    }

    @ParameterizedTest
    @ValueSource(strings = {"absolute", "relative", "query"})
    void followsSafeNextLinks(String style) {
        String next = style.equals("absolute") ? base + "/DocumentReference?page=2"
                : style.equals("relative") ? "/fhir/DocumentReference?page=2" : "?page=2";
        response = query -> query.equals("page=2") ? bundle(note("n2", "Patient/p1"), "")
                : bundle(note("n1", "Patient/p1"), next);
        context().run(ctx -> assertThat(ctx.getBean(FhirDocumentReferenceClient.class).findByPatientId("p1"))
                .extracting(note -> note.getIdElement().getIdPart()).containsExactly("n1", "n2"));
        assertThat(requests.get(1)).isEqualTo("GET /fhir/DocumentReference?page=2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://other.test/fhir?page=2", "/outside?page=2", "/fhir/../outside?page=2",
            "/fhir/%2e%2e/outside?page=2", "/fhir/DocumentReference?page=2#fragment",
            "http://user@127.0.0.1/fhir?page=2", "http://[broken"})
    void unsafeNextLinksAreNeverFollowed(String link) {
        response = query -> bundle(note("n1", "Patient/p1"), link);
        failsInvalid();
        assertThat(requests).hasSize(1);
    }

    @Test
    void pagesAndResultsAreBoundedAndLoopsDetected() {
        response = query -> bundle(note("n" + requests.size(), "Patient/p1"), base + "/DocumentReference?page=" + requests.size());
        failsInvalid();
        assertThat(requests).hasSize(10);

        requests.clear();
        response = query -> bundle("", base + "/DocumentReference?page=2");
        failsInvalid();
        assertThat(requests).hasSize(2);

        requests.clear();
        String entries = IntStream.range(0, 1001).mapToObj(i -> note("n" + i, "Patient/p1")).collect(Collectors.joining(","));
        response = query -> bundle(entries, "");
        failsInvalid();
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"resourceType\":\"Bundle\",\"type\":\"collection\"}",
            "{\"resourceType\":\"OperationOutcome\"}", "not JSON"})
    void malformedResponsesAreInvalid(String body) {
        response = query -> body;
        failsInvalid();
    }

    @Test
    void searchFailuresAreUnavailableAndInvalidIdsSendNothing() {
        errorOnRequest = 1;
        context().run(ctx -> {
            assertThatThrownBy(() -> ctx.getBean(FhirDocumentReferenceClient.class).findByPatientId("p1"))
                    .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                        assertThat(failure.operation()).isEqualTo("document-reference-search");
                        assertThat(failure.type()).isEqualTo(FhirServerException.Type.UNAVAILABLE);
                    });
            for (String id : new String[] {null, "", "Patient/p1", "p_1", "../p1", "x".repeat(65)}) {
                assertThatThrownBy(() -> ctx.getBean(FhirDocumentReferenceClient.class).findByPatientId(id))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        });
        assertThat(requests).hasSize(1);
    }

    // ---------- references ----------

    @Test
    void referencesAreResolvedOnlyForTheExpectedTypeOnThisServer() {
        context().run(ctx -> {
            FhirDocumentReferenceClient client = ctx.getBean(FhirDocumentReferenceClient.class);
            assertThat(client.logicalId(new Reference("Encounter/e1"), "Encounter")).isEqualTo("e1");
            assertThat(client.logicalId(new Reference(base + "/Encounter/e1"), "Encounter")).isEqualTo("e1");
            assertThat(client.logicalId(new Reference("Encounter/e1").setType("Encounter"), "Encounter")).isEqualTo("e1");
            for (Reference other : List.of(new Reference("Patient/e1"), new Reference("http://other.test/fhir/Encounter/e1"),
                    new Reference("Encounter/e1/_history/2"), new Reference("#e1"), new Reference("Encounter/bad_id"),
                    new Reference("Encounter/e1").setType("EpisodeOfCare"), new Reference())) {
                assertThat(client.logicalId(other, "Encounter")).as(String.valueOf(other.getReference())).isNull();
            }
            assertThat(client.logicalId(null, "Encounter")).isNull();
            assertThat(client.refersTo(new Reference("Patient/p1"), "Patient", "p1")).isTrue();
            assertThat(client.refersTo(new Reference("Patient/p10"), "Patient", "p1")).isFalse();
            assertThat(client.refersTo(new Reference("Patient/p1"), "Patient", null)).isFalse();
        });
        assertThat(requests).isEmpty();
    }

    private void failsInvalid() {
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirDocumentReferenceClient.class).findByPatientId("p1"))
                .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                    assertThat(failure.operation()).isEqualTo("document-reference-search");
                    assertThat(failure.type()).isEqualTo(FhirServerException.Type.INVALID_RESPONSE);
                    assertThat(failure.getMessage()).doesNotContain("Patient/p1", base);
                }));
    }
}
