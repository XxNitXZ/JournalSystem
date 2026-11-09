package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.medinexus.medicore.fhir.config.FhirClientConfig;
import com.medinexus.medicore.fhir.support.LocalFhirServer;
import com.medinexus.medicore.patient.dto.PatientSearchCriteria;
import com.sun.net.httpserver.HttpServer;

import ca.uhn.fhir.context.FhirContext;

/**
 * Patient search restricted to known IDs, against local servers on 127.0.0.1 only. Synthetic data: personnummer
 * from Skatteverket's published test personnummer.
 */
@ExtendWith(OutputCaptureExtension.class)
class FhirPatientSearchTest {

    private static final String SYSTEM = "http://electronichealth.se/identifier/personnummer";
    private static final String PERSONNUMMER = "199001012385";

    private LocalFhirServer localFhir;
    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (localFhir != null) localFhir.close();
        if (server != null) server.stop(0);
    }

    private ApplicationContextRunner context(String baseUrl) {
        return new ApplicationContextRunner()
                .withUserConfiguration(FhirClientConfig.class, FhirRequestExecutor.class, FhirPatientClient.class)
                .withPropertyValues("medinexus.fhir.base-url=" + baseUrl, "medinexus.fhir.connect-timeout=2s",
                        "medinexus.fhir.socket-timeout=5s", "medinexus.fhir.connection-request-timeout=2s");
    }

    private static Patient patient(String id, String given, String family, String personnummer) {
        Patient patient = new Patient();
        patient.setId(id);
        patient.addName().setFamily(family).addGiven(given);
        if (personnummer != null) patient.addIdentifier().setSystem(SYSTEM).setValue(personnummer);
        return patient;
    }

    private static List<String> ids(List<Patient> patients) {
        return patients.stream().map(patient -> patient.getIdElement().getIdPart()).toList();
    }

    /** Registered (allowed) Patients and other Patients on the same shared server with the same names. */
    private LocalFhirServer sharedServer() throws IOException {
        localFhir = LocalFhirServer.start();
        localFhir.seed(patient("own-anna-1", "Anna", "Testsson", PERSONNUMMER));
        localFhir.seed(patient("own-anna-2", "Anna", "Testsson", "198506152381"));
        localFhir.seed(patient("own-ake", "Åke", "Öberg", "200002292381"));
        localFhir.seed(patient("foreign-anna", "Anna", "Testsson", "197303159250"));
        localFhir.seed(patient("foreign-same-pnr", "Other", "Group", PERSONNUMMER));
        return localFhir;
    }

    private static final List<String> OWN = List.of("own-anna-1", "own-anna-2", "own-ake");

    @Test
    void nameSearchReturnsEveryMatchingKnownPatientAndNothingElse(CapturedOutput output) throws IOException {
        sharedServer();
        context(localFhir.baseUrl()).run(ctx -> {
            FhirPatientClient client = ctx.getBean(FhirPatientClient.class);
            // Two Patients with identical names are both returned; the other group's "Anna Testsson" is not.
            assertThat(ids(client.searchPatients(PatientSearchCriteria.parse("Anna Testsson"), OWN)))
                    .containsExactlyInAnyOrder("own-anna-1", "own-anna-2");
            assertThat(ids(client.searchPatients(PatientSearchCriteria.parse("anna"), OWN)))
                    .containsExactlyInAnyOrder("own-anna-1", "own-anna-2");
            assertThat(ids(client.searchPatients(PatientSearchCriteria.parse("Testsson"), OWN)))
                    .containsExactlyInAnyOrder("own-anna-1", "own-anna-2");
            assertThat(ids(client.searchPatients(PatientSearchCriteria.parse("ake oberg"), OWN))).containsExactly("own-ake");
            assertThat(client.searchPatients(PatientSearchCriteria.parse("Anna Öberg"), OWN)).isEmpty();
            assertThat(client.searchPatients(PatientSearchCriteria.parse("Group"), OWN)).isEmpty();
        });
        // Only POST [base]/Patient/_search, without query strings; the IDs restrict every request on the server.
        assertThat(localFhir.requests()).allMatch(request -> request.equals("POST /fhir/Patient/_search"));
        assertThat(localFhir.searchForms()).allSatisfy(form -> {
            assertThat(form.get("_id")).singleElement().satisfies(value ->
                    assertThat(value.split(",")).containsExactlyInAnyOrderElementsOf(OWN));
            assertThat(form.get("identifier")).isNull();
        });
        assertThat(localFhir.searchForms().getFirst().get("name")).containsExactly("Anna", "Testsson");
        assertThat(output.getAll().contains("Testsson")).as("log contains a searched name").isFalse();
    }

    @Test
    void personnummerSearchIsExactAndNeverReturnsAnotherPatient(CapturedOutput output) throws IOException {
        sharedServer();
        context(localFhir.baseUrl()).run(ctx -> {
            FhirPatientClient client = ctx.getBean(FhirPatientClient.class);
            // "foreign-same-pnr" has the same personnummer on the shared server but is not a known Patient.
            assertThat(ids(client.searchPatients(PatientSearchCriteria.parse(PERSONNUMMER), OWN))).containsExactly("own-anna-1");
            assertThat(ids(client.searchPatients(PatientSearchCriteria.parse("19900101-2385"), OWN))).containsExactly("own-anna-1");
            assertThat(client.searchPatients(PatientSearchCriteria.parse("197303159250"), OWN)).isEmpty();
        });
        assertThat(localFhir.requests()).allMatch(request -> request.equals("POST /fhir/Patient/_search"));
        assertThat(localFhir.searchForms().getFirst().get("identifier")).containsExactly(SYSTEM + "|" + PERSONNUMMER);
        assertThat(String.join(" ", localFhir.requests()).contains(PERSONNUMMER)).as("personnummer in a URL").isFalse();
        assertThat(output.getAll().contains(PERSONNUMMER)).as("log contains the personnummer").isFalse();
    }

    @Test
    void withoutKnownPatientsNothingIsRequested() throws IOException {
        sharedServer();
        context(localFhir.baseUrl()).run(ctx ->
                assertThat(ctx.getBean(FhirPatientClient.class).searchPatients(PatientSearchCriteria.parse("Anna"), List.of())).isEmpty());
        assertThat(localFhir.requests()).isEmpty();
    }

    @Test
    void largeRegistriesAreSearchedInBatchesOfFiftyIds() throws IOException {
        localFhir = LocalFhirServer.start();
        List<String> known = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            localFhir.seed(patient("own-" + i, "Batch", "Testsson", null));
            known.add("own-" + i);
        }
        context(localFhir.baseUrl()).run(ctx -> assertThat(ids(ctx.getBean(FhirPatientClient.class)
                .searchPatients(PatientSearchCriteria.parse("Batch"), known))).containsExactlyInAnyOrderElementsOf(known));
        assertThat(localFhir.searchForms()).hasSize(3).allSatisfy(form ->
                assertThat(form.get("_id").getFirst().split(",").length).isLessThanOrEqualTo(50));
    }

    @Test
    void moreThanOneThousandMatchesAreRejected() throws IOException {
        localFhir = LocalFhirServer.start();
        List<String> known = IntStream.range(0, 1001).mapToObj(i -> "own-" + i).toList();
        known.forEach(id -> localFhir.seed(patient(id, "Many", "Testsson", null)));
        context(localFhir.baseUrl()).run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirPatientClient.class)
                .searchPatients(PatientSearchCriteria.parse("Many"), known)).isInstanceOf(TooManyPatientsException.class));
    }

    @Test
    void invalidIdsAreRejectedBeforeAnyRequest() throws IOException {
        sharedServer();
        context(localFhir.baseUrl()).run(ctx -> {
            FhirPatientClient client = ctx.getBean(FhirPatientClient.class);
            for (List<String> invalid : List.of(List.of("bad_id"), List.of("own-anna-1", "Patient/1"), List.of("x".repeat(65)))) {
                assertThatThrownBy(() -> client.searchPatients(PatientSearchCriteria.parse("Anna"), invalid))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThatThrownBy(() -> client.searchPatients(null, OWN)).isInstanceOf(IllegalArgumentException.class);
        });
        assertThat(localFhir.requests()).isEmpty();
    }

    // ---- Paging and unsafe responses, with a scripted server ----

    private static final FhirContext CONTEXT = FhirContext.forR4();

    /** Answers every request with the bundle produced for its 0-based number. */
    private String scriptedServer(Function<Integer, Bundle> responses) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                exchange.getRequestBody().readAllBytes();
                byte[] body = CONTEXT.newJsonParser().encodeResourceToString(responses.apply(requests.size() - 1))
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/fhir";
    }

    private static Bundle page(List<String> ids, String next) {
        Bundle bundle = new Bundle().setType(Bundle.BundleType.SEARCHSET);
        ids.forEach(id -> bundle.addEntry().setResource(patient(id, "Anna", "Testsson", null))
                .getSearch().setMode(Bundle.SearchEntryMode.MATCH));
        if (next != null) bundle.addLink().setRelation("next").setUrl(next);
        return bundle;
    }

    @Test
    void followsNextPagesWithinTheFhirBase() throws IOException {
        String[] base = new String[1];
        base[0] = scriptedServer(n -> n == 0 ? page(List.of("own-anna-1"), base[0] + "?_getpages=abc&_getpagesoffset=1&_count=1")
                : page(List.of("own-anna-2"), null));
        context(base[0]).run(ctx -> assertThat(ids(ctx.getBean(FhirPatientClient.class)
                .searchPatients(PatientSearchCriteria.parse("Anna"), OWN))).containsExactly("own-anna-1", "own-anna-2"));
        assertThat(requests).containsExactly("POST /fhir/Patient/_search", "GET /fhir?_getpages=abc&_getpagesoffset=1&_count=1");
    }

    @Test
    void unsafeOrInconsistentResponsesFailTheWholeSearch(CapturedOutput output) throws IOException {
        String[] base = new String[1];
        List<Function<String, Bundle>> cases = List.of(
                // A Patient that was not requested (another group's Patient).
                b -> page(List.of("foreign-anna"), null),
                // The same Patient twice.
                b -> page(List.of("own-anna-1", "own-anna-1"), null),
                // A next link to another host.
                b -> page(List.of("own-anna-1"), "http://attacker.invalid/fhir?_getpages=x"),
                // Not a search result.
                b -> new Bundle().setType(Bundle.BundleType.COLLECTION));
        for (Function<String, Bundle> response : cases) {
            requests.clear();
            if (server != null) server.stop(0);
            base[0] = scriptedServer(n -> response.apply(base[0]));
            context(base[0]).run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirPatientClient.class)
                    .searchPatients(PatientSearchCriteria.parse("Anna"), OWN))
                    .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                        assertThat(failure.operation()).isEqualTo("patient-search");
                        assertThat(failure.type()).isEqualTo(FhirServerException.Type.INVALID_RESPONSE);
                    }));
            assertThat(requests).hasSize(1);
        }
        assertThat(output.getAll().contains("attacker")).isFalse();
    }

    @Test
    void endlessPagingIsStopped() throws IOException {
        String[] base = new String[1];
        base[0] = scriptedServer(n -> page(List.of(), base[0] + "?_getpages=loop&_getpagesoffset=" + n));
        context(base[0]).run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirPatientClient.class)
                .searchPatients(PatientSearchCriteria.parse("Anna"), OWN)).isInstanceOf(FhirServerException.class));
        assertThat(requests).hasSize(10);
    }
}
