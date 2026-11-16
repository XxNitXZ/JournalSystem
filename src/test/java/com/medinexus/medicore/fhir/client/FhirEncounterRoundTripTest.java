package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.medinexus.medicore.fhir.config.FhirClientConfig;
import com.medinexus.medicore.fhir.mapper.EncounterMapper;
import com.medinexus.medicore.fhir.mapper.LocationMapper;
import com.medinexus.medicore.fhir.mapper.OrganizationMapper;
import com.medinexus.medicore.location.dto.LocationDto;
import com.medinexus.medicore.location.dto.LocationWriteRequest;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest.EncounterClass;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest.EncounterLocation;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest.LocationStatus;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest.Participant;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest.ParticipantType;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest.Status;
import com.medinexus.medicore.medicalrecord.dto.EncounterDto;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Coding;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.PeriodValue;
import com.medinexus.medicore.organization.dto.OrganizationDto;
import com.medinexus.medicore.organization.dto.OrganizationWriteRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ca.uhn.fhir.context.FhirContext;

/** Stateful local Encounter transport test: no database and no external FHIR server. */
class FhirEncounterRoundTripTest {
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
        if (exchange.getRequestMethod().equals("POST") && path.matches("Encounter|Organization|Location")) {
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
        if (exchange.getRequestMethod().equals("GET") && path.equals("Encounter")) {
            String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            String subject = Arrays.stream(query.split("&")).filter(parameter -> parameter.startsWith("subject="))
                    .map(parameter -> parameter.substring("subject=".length())).findFirst().orElse("");
            Bundle bundle = new Bundle().setType(Bundle.BundleType.SEARCHSET);
            stored.forEach((key, resource) -> {
                if (key.startsWith("Encounter/") && subject.equals(((Encounter) resource).getSubject().getReference())) {
                    bundle.addEntry().setResource(resource);
                }
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
                FhirEncounterClient.class, EncounterMapper.class)
                .withPropertyValues("medinexus.fhir.base-url=" + base, "medinexus.fhir.connect-timeout=2s",
                        "medinexus.fhir.socket-timeout=3s", "medinexus.fhir.connection-request-timeout=2s");
    }

    private Concept visitType() {
        return new Concept("Synthetic visit", List.of(new Coding("urn:synthetic:encounter-type", "1", "checkup", "Check-up", false)));
    }

    @Test
    void createReadAndPatientSearchPreserveSubjectAndPractitionerParticipants() {
        context().run(ctx -> {
            var client = ctx.getBean(FhirEncounterClient.class);
            var input = new CreateEncounterRequest("123", Status.FINISHED, EncounterClass.AMB, List.of(visitType()), null, null,
                    "2026-06-15T09:00:00+02:00", "2026-06-15T09:30:00+02:00", List.of(),
                    List.of(new Participant("synthetic-practitioner", List.of(ParticipantType.ATND, ParticipantType.PPRF))));

            String id = client.createEncounter(input);
            assertThat(id).isEqualTo("local-1");

            Encounter resource = client.readEncounter(id);
            EncounterDto dto = ctx.getBean(EncounterMapper.class).toDto(resource);
            assertThat(dto.id()).isEqualTo(id);
            assertThat(dto.status()).isEqualTo("finished");
            assertThat(dto.encounterClass().code()).isEqualTo("AMB");
            assertThat(dto.types()).containsExactly(visitType());
            assertThat(dto.subject().reference()).isEqualTo("Patient/123");
            assertThat(dto.participants()).hasSize(1);
            assertThat(dto.participants().getFirst().individual().reference()).isEqualTo("Practitioner/synthetic-practitioner");
            assertThat(dto.participants().getFirst().types()).extracting(type -> type.coding().getFirst().code())
                    .containsExactly("ATND", "PPRF");
            assertThat(dto.period()).isEqualTo(new PeriodValue("2026-06-15T09:00:00+02:00", "2026-06-15T09:30:00+02:00"));

            // A second Encounter for another patient must not appear in the search for Patient/123.
            client.createEncounter(new CreateEncounterRequest("other-patient", Status.PLANNED, EncounterClass.VR, null, null, null,
                    null, null, null, List.of(new Participant("other-practitioner", null))));

            assertThat(client.findByPatientId("123").encounters()).containsExactly(dto);
            assertThat(client.findByPatientId("other-patient").encounters()).singleElement().satisfies(other -> {
                assertThat(other.id()).isEqualTo("local-2");
                assertThat(other.participants().getFirst().individual().reference()).isEqualTo("Practitioner/other-practitioner");
            });
            assertThat(client.findByPatientId("no-encounters").encounters()).isEmpty();

            assertThat(requests).hasSize(6);
            assertThat(requests.get(0)).isEqualTo("POST /fhir/Encounter");
            assertThat(requests.get(1)).isEqualTo("GET /fhir/Encounter/" + id);
            assertThat(requests.get(2)).isEqualTo("POST /fhir/Encounter");
            assertThat(requests.subList(3, 6)).allMatch(request -> request.startsWith("GET /fhir/Encounter?"));
        });
    }

    @Test
    void missingStoredEncounterUsesExisting404Translation() {
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirEncounterClient.class).readEncounter("missing"))
                .isInstanceOfSatisfying(FhirServerException.class, error -> {
                    assertThat(error.operation()).isEqualTo("encounter-read");
                    assertThat(error.type()).isEqualTo(FhirServerException.Type.CLIENT_ERROR);
                    assertThat(error.httpStatus()).hasValue(404);
                    assertThat(error.issues()).containsExactly("error:not-found");
                }));
        assertThat(requests).containsExactly("GET /fhir/Encounter/missing");
        assertThat(stored).isEmpty();
    }

    /**
     * NIT-135: an Encounter linked to a service provider and two Locations. The referenced Organization and
     * Locations are created and read through their own clients on the same local server; the clients do not
     * depend on each other, the test passes the logical IDs between them.
     */
    @Test
    void encounterLinkedToOrganizationAndLocationsRoundTripsAndItsReferencesResolveLocally() {
        context().withUserConfiguration(FhirOrganizationClient.class, OrganizationMapper.class,
                FhirLocationClient.class, LocationMapper.class).run(ctx -> {
            var organizations = ctx.getBean(FhirOrganizationClient.class);
            var locations = ctx.getBean(FhirLocationClient.class);
            var encounters = ctx.getBean(FhirEncounterClient.class);

            String clinicId = organizations.createOrganization(
                    new OrganizationWriteRequest(null, true, null, "Synthetic Clinic", null, null, null, null));
            String wardId = locations.createLocation(new LocationWriteRequest(null, LocationWriteRequest.Status.ACTIVE,
                    "Synthetic Ward", null, null, LocationWriteRequest.Mode.INSTANCE, null, null, null, null, null, clinicId, null));
            String roomId = locations.createLocation(new LocationWriteRequest(null, LocationWriteRequest.Status.ACTIVE,
                    "Synthetic Room", null, null, LocationWriteRequest.Mode.INSTANCE, null, null, null, null, null, clinicId, wardId));
            assertThat(List.of(clinicId, wardId, roomId)).containsExactly("local-1", "local-2", "local-3");

            var input = new CreateEncounterRequest("synthetic-patient", Status.IN_PROGRESS, EncounterClass.AMB,
                    List.of(visitType()), null, null, "2026-06-15T09:00:00+02:00", null, List.of(),
                    List.of(new Participant("synthetic-practitioner", List.of(ParticipantType.ATND))), clinicId,
                    List.of(new EncounterLocation(wardId, LocationStatus.COMPLETED), new EncounterLocation(roomId, LocationStatus.ACTIVE)));
            String encounterId = encounters.createEncounter(input);
            assertThat(encounterId).isEqualTo("local-4");

            EncounterDto dto = ctx.getBean(EncounterMapper.class).toDto(encounters.readEncounter(encounterId));
            assertThat(dto.subject().reference()).isEqualTo("Patient/synthetic-patient");
            assertThat(dto.participants()).singleElement().satisfies(participant ->
                    assertThat(participant.individual().reference()).isEqualTo("Practitioner/synthetic-practitioner"));
            assertThat(dto.status()).isEqualTo("in-progress");
            assertThat(dto.serviceProvider().reference()).isEqualTo("Organization/" + clinicId);
            assertThat(dto.locations()).extracting(location -> location.location().reference())
                    .containsExactly("Location/" + wardId, "Location/" + roomId);
            assertThat(dto.locations()).extracting(EncounterDto.LocationEntry::status).containsExactly("completed", "active");
            assertThat(encounters.findByPatientId("synthetic-patient").encounters()).containsExactly(dto);

            // Resolve the references with the Organization and Location clients (no coupling between clients).
            OrganizationDto clinic = ctx.getBean(OrganizationMapper.class).toDto(organizations.readOrganization(
                    dto.serviceProvider().reference().substring("Organization/".length())));
            assertThat(clinic.name()).isEqualTo("Synthetic Clinic");
            List<LocationDto> places = dto.locations().stream().map(location -> ctx.getBean(LocationMapper.class).toDto(
                    locations.readLocation(location.location().reference().substring("Location/".length())))).toList();
            assertThat(places).extracting(LocationDto::name).containsExactly("Synthetic Ward", "Synthetic Room");
            assertThat(places).allSatisfy(place ->
                    assertThat(place.managingOrganization().reference()).isEqualTo("Organization/" + clinicId));
            assertThat(places.get(1).partOf().reference()).isEqualTo("Location/" + wardId);
        });
        assertThat(requests).hasSize(9);
        assertThat(requests.subList(0, 5)).containsExactly("POST /fhir/Organization", "POST /fhir/Location",
                "POST /fhir/Location", "POST /fhir/Encounter", "GET /fhir/Encounter/local-4");
        assertThat(requests.get(5)).startsWith("GET /fhir/Encounter?");
        assertThat(requests.subList(6, 9)).containsExactly("GET /fhir/Organization/local-1",
                "GET /fhir/Location/local-2", "GET /fhir/Location/local-3");
    }
}
