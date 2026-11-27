package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.hl7.fhir.r4.model.Encounter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.medinexus.medicore.fhir.config.FhirClientConfig;
import com.medinexus.medicore.fhir.mapper.EncounterMapper;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest.EncounterClass;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest.EncounterLocation;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest.LocationStatus;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest.Participant;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest.ParticipantType;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest.Status;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Coding;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.sun.net.httpserver.HttpServer;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;

/** Creation against a local HTTP server only; nothing is ever sent to an external FHIR server. */
class FhirEncounterCreateTest {
    private static final FhirContext CONTEXT = FhirContext.forR4();
    private HttpServer server;
    private String base;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<Encounter> payloads = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> contentTypes = new CopyOnWriteArrayList<>();
    private int status = 201;
    private String location = "/fhir/Encounter/server-123/_history/1";
    private String responseBody = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            contentTypes.add(String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")));
            payloads.add(CONTEXT.newJsonParser().parseResource(Encounter.class, body));
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
                FhirRequestExecutor.class, FhirEncounterClient.class, EncounterMapper.class)
                .withPropertyValues("medinexus.fhir.base-url=" + base, "medinexus.fhir.connect-timeout=2s",
                        "medinexus.fhir.socket-timeout=3s", "medinexus.fhir.connection-request-timeout=2s");
    }

    private Concept visitType() {
        return new Concept("Synthetic visit", List.of(new Coding("urn:synthetic:encounter-type", "1", "checkup", "Check-up", false)));
    }

    private CreateEncounterRequest request(Status encounterStatus, EncounterClass encounterClass, String start, String end) {
        return new CreateEncounterRequest("synthetic-patient", encounterStatus, encounterClass, List.of(), null, null,
                start, end, List.of(), List.of());
    }

    private CreateEncounterRequest withParticipants(List<Participant> participants) {
        return new CreateEncounterRequest("synthetic-patient", Status.IN_PROGRESS, EncounterClass.AMB, List.of(), null, null,
                null, null, List.of(), participants);
    }

    private CreateEncounterRequest minimal() {
        return request(Status.FINISHED, EncounterClass.AMB, null, null);
    }

    @Test
    void createsMappedPayloadWithAssignedIdAndTerminologyOnTheLocalServerOnly() {
        Concept service = new Concept("Synthetic service", List.of());
        Concept priority = new Concept(null, List.of(new Coding("http://terminology.hl7.org/CodeSystem/v3-ActPriority", null, "R", "routine", null)));
        Concept reason = new Concept("Synthetic reason", List.of(new Coding("urn:synthetic:reason", null, "r1", "Reason", true)));
        var input = new CreateEncounterRequest("synthetic-patient", Status.IN_PROGRESS, EncounterClass.AMB, List.of(visitType()),
                service, priority, "2026-06-15T09:00:00+02:00", "2026-06-15T09:30:00.123+02:00", List.of(reason), List.of());
        context().run(ctx -> {
            assertThat(ctx.getBean(IGenericClient.class).getServerBase()).startsWith("http://127.0.0.1:");
            assertThat(ctx.getBean(FhirEncounterClient.class).createEncounter(input)).isEqualTo("server-123");
            assertThat(requests).containsExactly("POST /fhir/Encounter");
            Encounter resource = payloads.getFirst();
            assertThat(resource.hasId()).isFalse();
            assertThat(resource.getSubject().getReference()).isEqualTo("Patient/synthetic-patient");
            assertThat(resource.getStatus().toCode()).isEqualTo("in-progress");
            assertThat(resource.getClass_().getSystem()).isEqualTo(EncounterClass.SYSTEM);
            assertThat(resource.getClass_().getCode()).isEqualTo("AMB");
            var type = resource.getTypeFirstRep().getCodingFirstRep();
            assertThat(type.getSystem()).isEqualTo("urn:synthetic:encounter-type");
            assertThat(type.getVersion()).isEqualTo("1");
            assertThat(type.getCode()).isEqualTo("checkup");
            assertThat(type.getDisplay()).isEqualTo("Check-up");
            assertThat(type.getUserSelected()).isFalse();
            assertThat(resource.getServiceType().getText()).isEqualTo("Synthetic service");
            assertThat(resource.getPriority().getCodingFirstRep().getCode()).isEqualTo("R");
            assertThat(resource.getPeriod().getStartElement().getValueAsString()).isEqualTo("2026-06-15T09:00:00+02:00");
            assertThat(resource.getPeriod().getEndElement().getValueAsString()).isEqualTo("2026-06-15T09:30:00.123+02:00");
            assertThat(resource.getReasonCodeFirstRep().getCodingFirstRep().getUserSelected()).isTrue();
            assertThat(resource.hasParticipant()).isFalse();
            assertThat(resource.hasLocation()).isFalse();
            assertThat(resource.hasServiceProvider()).isFalse();
        });
    }

    @Test
    void createsMinimalEncounterWithoutInventedOptionalFields() {
        context().run(ctx -> ctx.getBean(FhirEncounterClient.class).createEncounter(minimal()));
        Encounter resource = payloads.getFirst();
        assertThat(resource.getStatus().toCode()).isEqualTo("finished");
        assertThat(resource.getSubject().getReference()).isEqualTo("Patient/synthetic-patient");
        assertThat(resource.hasType()).isFalse();
        assertThat(resource.hasServiceType()).isFalse();
        assertThat(resource.hasPriority()).isFalse();
        assertThat(resource.hasPeriod()).isFalse();
        assertThat(resource.hasReasonCode()).isFalse();
        assertThat(resource.hasParticipant()).isFalse();
        assertThat(resource.hasIdentifier()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Status.class)
    void emitsEveryStatusAsItsR4Code(Status encounterStatus) {
        context().run(ctx -> ctx.getBean(FhirEncounterClient.class).createEncounter(request(encounterStatus, EncounterClass.AMB, null, null)));
        assertThat(payloads.getFirst().getStatus().toCode()).isEqualTo(encounterStatus.code());
    }

    @ParameterizedTest
    @CsvSource({"PLANNED, planned", "ARRIVED, arrived", "TRIAGED, triaged", "IN_PROGRESS, in-progress",
            "ONLEAVE, onleave", "FINISHED, finished", "CANCELLED, cancelled", "ENTERED_IN_ERROR, entered-in-error",
            "UNKNOWN, unknown"})
    void statusCodesMatchTheR4ValueSet(Status encounterStatus, String expected) {
        assertThat(encounterStatus.code()).isEqualTo(expected);
        assertThat(Encounter.EncounterStatus.fromCode(expected).toCode()).isEqualTo(expected);
    }

    @Test
    void supportsExactlyTheNineR4EncounterStatuses() {
        Set<String> ours = Arrays.stream(Status.values()).map(Status::code).collect(Collectors.toSet());
        Set<String> r4 = Arrays.stream(Encounter.EncounterStatus.values())
                .filter(value -> value != Encounter.EncounterStatus.NULL).map(Encounter.EncounterStatus::toCode)
                .collect(Collectors.toSet());
        assertThat(ours).hasSize(9).contains("unknown").isEqualTo(r4);
    }

    @ParameterizedTest
    @EnumSource(EncounterClass.class)
    void emitsEveryClassWithTheActCodeSystem(EncounterClass encounterClass) {
        context().run(ctx -> ctx.getBean(FhirEncounterClient.class).createEncounter(request(Status.PLANNED, encounterClass, null, null)));
        assertThat(payloads.getFirst().getClass_().getSystem()).isEqualTo(EncounterClass.SYSTEM);
        assertThat(payloads.getFirst().getClass_().getCode()).isEqualTo(encounterClass.code());
    }

    @Test
    void createsEncounterWithOnePractitionerAsParticipantIndividual() {
        context().run(ctx -> assertThat(ctx.getBean(FhirEncounterClient.class)
                .createEncounter(withParticipants(List.of(new Participant("synthetic-practitioner", null))))).isEqualTo("server-123"));
        assertThat(requests).containsExactly("POST /fhir/Encounter");
        Encounter resource = payloads.getFirst();
        assertThat(resource.getSubject().getReference()).isEqualTo("Patient/synthetic-patient");
        assertThat(resource.getParticipant()).hasSize(1);
        var participant = resource.getParticipantFirstRep();
        assertThat(participant.getIndividual().getReference()).isEqualTo("Practitioner/synthetic-practitioner");
        assertThat(participant.hasType()).isFalse();
        assertThat(participant.hasPeriod()).isFalse();
    }

    @Test
    void createsEncounterWithMultiplePractitionersInRequestOrder() {
        // Logical IDs are case-sensitive, so these are three different Practitioners.
        var participants = List.of(new Participant("practitioner-b", List.of()), new Participant("practitioner-a", List.of()),
                new Participant("PRACTITIONER-A", List.of()));
        context().run(ctx -> ctx.getBean(FhirEncounterClient.class).createEncounter(withParticipants(participants)));
        assertThat(requests).containsExactly("POST /fhir/Encounter");
        assertThat(payloads.getFirst().getParticipant()).extracting(participant -> participant.getIndividual().getReference())
                .containsExactly("Practitioner/practitioner-b", "Practitioner/practitioner-a", "Practitioner/PRACTITIONER-A");
    }

    @Test
    void createsParticipantRolesWithTheParticipationTypeSystem() {
        var participants = List.of(new Participant("practitioner-a", List.of(ParticipantType.ATND, ParticipantType.PPRF)),
                new Participant("practitioner-b", List.of(ParticipantType.CON)));
        context().run(ctx -> ctx.getBean(FhirEncounterClient.class).createEncounter(withParticipants(participants)));
        var first = payloads.getFirst().getParticipant().get(0);
        assertThat(first.getType()).allSatisfy(type -> {
            assertThat(type.getCoding()).hasSize(1);
            assertThat(type.getCodingFirstRep().getSystem()).isEqualTo(ParticipantType.SYSTEM);
            assertThat(type.hasText()).isFalse();
        });
        assertThat(first.getType()).extracting(type -> type.getCodingFirstRep().getCode()).containsExactly("ATND", "PPRF");
        assertThat(payloads.getFirst().getParticipant().get(1).getType())
                .extracting(type -> type.getCodingFirstRep().getCode()).containsExactly("CON");
    }

    @ParameterizedTest
    @EnumSource(ParticipantType.class)
    void emitsEveryParticipantTypeAsItsV3Code(ParticipantType type) throws Exception {
        context().run(ctx -> ctx.getBean(FhirEncounterClient.class)
                .createEncounter(withParticipants(List.of(new Participant("practitioner-a", List.of(type))))));
        var coding = payloads.getFirst().getParticipantFirstRep().getTypeFirstRep().getCodingFirstRep();
        assertThat(coding.getSystem()).isEqualTo("http://terminology.hl7.org/CodeSystem/v3-ParticipationType");
        assertThat(coding.getCode()).isEqualTo(type.code());
        assertThat(org.hl7.fhir.r4.model.codesystems.V3ParticipationType.fromCode(coding.getCode()).toCode()).isEqualTo(type.code());
    }

    @ParameterizedTest
    @NullAndEmptySource
    void createsEncounterWithoutParticipants(List<Participant> participants) {
        context().run(ctx -> ctx.getBean(FhirEncounterClient.class).createEncounter(withParticipants(participants)));
        assertThat(requests).containsExactly("POST /fhir/Encounter");
        assertThat(payloads.getFirst().hasParticipant()).isFalse();
        assertThat(bodies.getFirst()).doesNotContain("\"participant\"");
    }

    @Test
    void serializesSubjectAndParticipantsAsFhirR4Json() {
        var participants = List.of(new Participant("practitioner-a", List.of(ParticipantType.ATND)),
                new Participant("practitioner-b", null));
        context().run(ctx -> ctx.getBean(FhirEncounterClient.class).createEncounter(withParticipants(participants)));
        assertThat(contentTypes.getFirst()).startsWith("application/fhir+json");
        String json = bodies.getFirst();
        assertThat(json).startsWith("{\"resourceType\":\"Encounter\"")
                .contains("\"status\":\"in-progress\"")
                .contains("\"class\":{\"system\":\"http://terminology.hl7.org/CodeSystem/v3-ActCode\",\"code\":\"AMB\"}")
                .contains("\"subject\":{\"reference\":\"Patient/synthetic-patient\"}")
                .contains("\"participant\":[{\"type\":[{\"coding\":[{\"system\":"
                        + "\"http://terminology.hl7.org/CodeSystem/v3-ParticipationType\",\"code\":\"ATND\"}]}],"
                        + "\"individual\":{\"reference\":\"Practitioner/practitioner-a\"}},"
                        + "{\"individual\":{\"reference\":\"Practitioner/practitioner-b\"}}]")
                .doesNotContain("\"id\"");
        Encounter parsed = CONTEXT.newJsonParser().parseResource(Encounter.class, json);
        assertThat(parsed.getParticipant()).extracting(participant -> participant.getIndividual().getReference())
                .containsExactly("Practitioner/practitioner-a", "Practitioner/practitioner-b");
    }

    @Test
    void invalidParticipantsCauseZeroRequests() {
        List<List<Participant>> invalid = new ArrayList<>();
        for (String id : new String[] {null, "", " ", " id", "Practitioner/1", "https://example.test/fhir/Practitioner/1",
                "../Practitioner/1", "id?x=1", "id#fragment", "id_name", "ä", "x".repeat(65)})
            invalid.add(List.of(new Participant("valid-practitioner", List.of()), new Participant(id, List.of(ParticipantType.ATND))));
        invalid.add(Arrays.asList(new Participant("valid-practitioner", List.of()), null));
        invalid.add(Arrays.asList((Participant) null));
        invalid.add(List.of(new Participant("p1", Arrays.asList(ParticipantType.ATND, null))));
        invalid.add(List.of(new Participant("p1", Arrays.asList((ParticipantType) null))));
        invalid.add(List.of(new Participant("p1", List.of()), new Participant("p1", List.of())));
        invalid.add(List.of(new Participant("p1", List.of(ParticipantType.ATND)), new Participant("p1", List.of(ParticipantType.CON))));
        invalid.add(List.of(new Participant("p1", List.of(ParticipantType.ATND, ParticipantType.ATND))));
        context().run(ctx -> {
            for (var participants : invalid) assertThatThrownBy(() -> ctx.getBean(FhirEncounterClient.class)
                    .createEncounter(withParticipants(participants)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid Encounter creation request");
            assertThat(requests).isEmpty();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"2020", "2020-02", "2020-02-29", "2020-02-29T12:34:56Z", "2020-02-29T12:34:56.123456+02:00"})
    void preservesPeriodPrecisionAndAcceptsEqualBounds(String date) {
        context().run(ctx -> ctx.getBean(FhirEncounterClient.class).createEncounter(request(Status.FINISHED, EncounterClass.AMB, date, date)));
        assertThat(payloads.getFirst().getPeriod().getStartElement().getValueAsString()).isEqualTo(date);
        assertThat(payloads.getFirst().getPeriod().getEndElement().getValueAsString()).isEqualTo(date);
    }

    @ParameterizedTest
    @CsvSource({
            "2026-06-15T10:00:00Z, 2026-06-15",
            "2026-06-15, 2026-06-15T00:30:00+14:00",
            "2026, 2026-06",
            "2026-06-15, 2026-06",
            "2026-06-15T10:00:00+02:00, 2026-06-15T08:00:00Z",
            ", 2026",
            "2026-06-15T09:00:00Z, "
    })
    void acceptsPeriodsThatAreNotDefinitelyReversed(String start, String end) {
        context().run(ctx -> ctx.getBean(FhirEncounterClient.class).createEncounter(request(Status.FINISHED, EncounterClass.AMB, start, end)));
        assertThat(requests).containsExactly("POST /fhir/Encounter");
        assertThat(payloads.getFirst().getPeriod().hasStart()).isEqualTo(start != null);
        assertThat(payloads.getFirst().getPeriod().hasEnd()).isEqualTo(end != null);
    }

    @Test
    void invalidInputsCauseZeroRequests() {
        List<CreateEncounterRequest> invalid = new ArrayList<>();
        invalid.add(null);
        for (String id : new String[] {null, "", " ", "Patient/1", "https://example.test/1", "id_name", "x".repeat(65)})
            invalid.add(new CreateEncounterRequest(id, Status.FINISHED, EncounterClass.AMB, null, null, null, null, null, null, null));
        invalid.add(new CreateEncounterRequest("p1", null, EncounterClass.AMB, null, null, null, null, null, null, null));
        invalid.add(new CreateEncounterRequest("p1", Status.FINISHED, null, null, null, null, null, null, null, null));
        for (Concept concept : new Concept[] {new Concept("", List.of()), new Concept(" ", List.of()),
                new Concept(null, List.of(new Coding(null, null, "test", null, null))),
                new Concept(null, List.of(new Coding("bad uri", null, "test", null, null))),
                new Concept(null, List.of(new Coding("urn:synthetic", null, " ", null, null)))}) {
            invalid.add(new CreateEncounterRequest("p1", Status.FINISHED, EncounterClass.AMB, List.of(concept), null, null, null, null, null, null));
            invalid.add(new CreateEncounterRequest("p1", Status.FINISHED, EncounterClass.AMB, null, concept, null, null, null, null, null));
            invalid.add(new CreateEncounterRequest("p1", Status.FINISHED, EncounterClass.AMB, null, null, concept, null, null, null, null));
            invalid.add(new CreateEncounterRequest("p1", Status.FINISHED, EncounterClass.AMB, null, null, null, null, null, List.of(concept), null));
        }
        for (String date : new String[] {"", "invalid", "0000", "2026-13", "2026-02-30", "2026-01-01T12:00:00",
                "2026-01-01T12:00:00+15:00", "2026-01-01T24:00:00Z"}) {
            invalid.add(request(Status.FINISHED, EncounterClass.AMB, date, null));
            invalid.add(request(Status.FINISHED, EncounterClass.AMB, null, date));
        }
        for (String[] reversed : new String[][] {{"2026-06-16", "2026-06-15"}, {"2027", "2026"}, {"2026-07", "2026-06"},
                {"2026-06-15T10:00:00Z", "2026-06-15T09:59:59Z"}, {"2026-06-17", "2026-06-15T23:00:00+14:00"},
                {"2026-06-15T10:00:00Z", "2026-06-13"}})
            invalid.add(request(Status.FINISHED, EncounterClass.AMB, reversed[0], reversed[1]));
        context().run(ctx -> {
            for (var input : invalid) assertThatThrownBy(() -> ctx.getBean(FhirEncounterClient.class).createEncounter(input))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid Encounter creation request");
            assertThat(requests).isEmpty();
        });
    }

    @ParameterizedTest
    @CsvSource({"404, CLIENT_ERROR", "422, CLIENT_ERROR", "500, SERVER_ERROR", "503, UNAVAILABLE"})
    void preservesExternalErrors(int responseStatus, FhirServerException.Type type) {
        status = responseStatus; location = null;
        responseBody = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"processing\",\"diagnostics\":\"SENSITIVE\"}]}";
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirEncounterClient.class).createEncounter(minimal()))
                .isInstanceOfSatisfying(FhirServerException.class, error -> {
                    assertThat(error.operation()).isEqualTo("encounter-create");
                    assertThat(error.type()).isEqualTo(type);
                    assertThat(error.httpStatus()).hasValue(responseStatus);
                    assertThat(error.issues()).containsExactly("error:processing");
                    assertThat(error.getCause()).isNull();
                    assertThat(error.getMessage()).doesNotContain("SENSITIVE", "synthetic-patient");
                }));
        assertThat(requests).containsExactly("POST /fhir/Encounter");
    }

    @ParameterizedTest
    @CsvSource({"201, /fhir/Patient/wrong", "201, /fhir/Encounter/bad_id", "200, /fhir/Encounter/server-123"})
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
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirEncounterClient.class).createEncounter(minimal()))
                .isInstanceOfSatisfying(FhirServerException.class, error -> assertThat(error.type()).isEqualTo(FhirServerException.Type.INVALID_RESPONSE)));
        assertThat(requests).hasSize(1);
    }

    // NIT-135: service provider (Organization) and locations (Location).

    private CreateEncounterRequest associated(String serviceProviderId, List<EncounterLocation> locations) {
        return new CreateEncounterRequest("synthetic-patient", Status.IN_PROGRESS, EncounterClass.AMB, List.of(), null, null,
                null, null, List.of(), List.of(new Participant("synthetic-practitioner", List.of(ParticipantType.ATND))),
                serviceProviderId, locations);
    }

    @Test
    void createsEncounterWithServiceProviderAndOneLocationAsExactR4Json() {
        context().run(ctx -> assertThat(ctx.getBean(FhirEncounterClient.class).createEncounter(associated("synthetic-clinic",
                List.of(new EncounterLocation("synthetic-ward", LocationStatus.ACTIVE))))).isEqualTo("server-123"));
        assertThat(requests).containsExactly("POST /fhir/Encounter");
        assertThat(contentTypes.getFirst()).startsWith("application/fhir+json");
        assertThat(bodies.getFirst()).isEqualTo("{\"resourceType\":\"Encounter\",\"status\":\"in-progress\","
                + "\"class\":{\"system\":\"http://terminology.hl7.org/CodeSystem/v3-ActCode\",\"code\":\"AMB\"},"
                + "\"subject\":{\"reference\":\"Patient/synthetic-patient\"},"
                + "\"participant\":[{\"type\":[{\"coding\":[{\"system\":\"http://terminology.hl7.org/CodeSystem/v3-ParticipationType\","
                + "\"code\":\"ATND\"}]}],\"individual\":{\"reference\":\"Practitioner/synthetic-practitioner\"}}],"
                + "\"location\":[{\"location\":{\"reference\":\"Location/synthetic-ward\"},\"status\":\"active\"}],"
                + "\"serviceProvider\":{\"reference\":\"Organization/synthetic-clinic\"}}");
    }

    @Test
    void createsEncounterWithMultipleLocationsInRequestOrder() {
        context().run(ctx -> ctx.getBean(FhirEncounterClient.class).createEncounter(associated(null, List.of(
                new EncounterLocation("ward-b", LocationStatus.COMPLETED), new EncounterLocation("room-a", null),
                new EncounterLocation("room-c", LocationStatus.PLANNED)))));
        Encounter resource = payloads.getFirst();
        assertThat(resource.getLocation()).extracting(location -> location.getLocation().getReference())
                .containsExactly("Location/ward-b", "Location/room-a", "Location/room-c");
        assertThat(resource.getLocation()).extracting(location -> location.hasStatus() ? location.getStatus().toCode() : null)
                .containsExactly("completed", null, "planned");
        assertThat(resource.hasServiceProvider()).isFalse();
        assertThat(bodies.getFirst()).contains("\"location\":[{\"location\":{\"reference\":\"Location/ward-b\"},\"status\":\"completed\"},"
                + "{\"location\":{\"reference\":\"Location/room-a\"}},"
                + "{\"location\":{\"reference\":\"Location/room-c\"},\"status\":\"planned\"}]");
    }

    @ParameterizedTest
    @EnumSource(LocationStatus.class)
    void emitsEveryLocationStatusAsItsR4Code(LocationStatus locationStatus) {
        context().run(ctx -> ctx.getBean(FhirEncounterClient.class).createEncounter(associated(null,
                List.of(new EncounterLocation("synthetic-ward", locationStatus)))));
        assertThat(payloads.getFirst().getLocationFirstRep().getStatus().toCode()).isEqualTo(locationStatus.code());
        assertThat(bodies.getFirst()).contains("\"status\":\"" + locationStatus.name().toLowerCase(java.util.Locale.ROOT) + "\"}]");
    }

    @Test
    void createsEncounterWithOnlyAServiceProvider() {
        context().run(ctx -> ctx.getBean(FhirEncounterClient.class).createEncounter(associated("synthetic-clinic", null)));
        Encounter resource = payloads.getFirst();
        assertThat(resource.getServiceProvider().getReference()).isEqualTo("Organization/synthetic-clinic");
        assertThat(resource.hasLocation()).isFalse();
        assertThat(resource.getSubject().getReference()).isEqualTo("Patient/synthetic-patient");
        assertThat(resource.getParticipantFirstRep().getIndividual().getReference()).isEqualTo("Practitioner/synthetic-practitioner");
    }

    @Test
    void createsEncounterWithoutServiceProviderOrLocationsIdenticallyToTheOriginalConstructor() {
        var original = new CreateEncounterRequest("synthetic-patient", Status.IN_PROGRESS, EncounterClass.AMB, List.of(), null,
                null, null, null, List.of(), List.of(new Participant("synthetic-practitioner", List.of(ParticipantType.ATND))));
        context().run(ctx -> {
            var client = ctx.getBean(FhirEncounterClient.class);
            client.createEncounter(original);
            client.createEncounter(associated(null, null));
            client.createEncounter(associated(null, List.of()));
        });
        assertThat(bodies).hasSize(3).allMatch(body -> body.equals(bodies.getFirst()));
        assertThat(bodies.getFirst()).doesNotContain("\"location\"", "serviceProvider", "Organization/", "Location/");
    }

    @Test
    void invalidAssociationsCauseZeroRequests() {
        List<CreateEncounterRequest> invalid = new ArrayList<>();
        for (String id : new String[] {"", " ", " id", "Organization/1", "Location/1", "https://example.test/fhir/Organization/1",
                "../Organization/1", "id?x=1", "id#fragment", "id_name", "ä", "x".repeat(65)}) {
            invalid.add(associated(id, null));
            invalid.add(associated("synthetic-clinic", List.of(new EncounterLocation(id, LocationStatus.ACTIVE))));
        }
        invalid.add(associated(null, Arrays.asList((EncounterLocation) null)));
        invalid.add(associated(null, Arrays.asList(new EncounterLocation("synthetic-ward", null), null)));
        invalid.add(associated(null, List.of(new EncounterLocation(null, null))));
        invalid.add(associated(null, List.of(new EncounterLocation("synthetic-ward", LocationStatus.ACTIVE),
                new EncounterLocation("synthetic-ward", LocationStatus.COMPLETED))));
        context().run(ctx -> {
            for (var request : invalid) assertThatThrownBy(() -> ctx.getBean(FhirEncounterClient.class).createEncounter(request))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid Encounter creation request");
            assertThat(requests).isEmpty();
        });
    }
}
