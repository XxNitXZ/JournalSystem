package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.medinexus.medicore.fhir.config.FhirClientConfig;
import com.medinexus.medicore.fhir.mapper.LocationMapper;
import com.medinexus.medicore.location.dto.LocationDto;
import com.medinexus.medicore.location.dto.LocationDto.Position;
import com.medinexus.medicore.location.dto.LocationWriteRequest;
import com.medinexus.medicore.location.dto.LocationWriteRequest.ContactPoint;
import com.medinexus.medicore.location.dto.LocationWriteRequest.ContactPointSystem;
import com.medinexus.medicore.location.dto.LocationWriteRequest.ContactPointUse;
import com.medinexus.medicore.location.dto.LocationWriteRequest.Mode;
import com.medinexus.medicore.location.dto.LocationWriteRequest.Status;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Identifier;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;

/**
 * Read-modify-write updates against a local, versioned HTTP server that enforces If-Match like a
 * versioned-update FHIR server. Synthetic data only; nothing is sent to an external FHIR server.
 */
@ExtendWith(OutputCaptureExtension.class)
class FhirLocationUpdateTest {

    private static final FhirContext CONTEXT = FhirContext.forR4();

    /** Unmanaged elements plus managed fields with hidden subfields and references with display. */
    private static final String CURRENT = """
            {"resourceType":"Location",
             "meta":{"source":"#synthetic","tag":[{"system":"urn:synthetic:tag","code":"t"}]},
             "text":{"status":"generated","div":"<div xmlns=\\"http://www.w3.org/1999/xhtml\\">Synthetic Ward</div>"},
             "extension":[{"url":"urn:synthetic:extension","valueString":"kept"}],
             "identifier":[{"use":"official","system":"urn:synthetic:location","value":"LOC-1","period":{"start":"2020-01-01"}}],
             "status":"active",
             "operationalStatus":{"system":"http://terminology.hl7.org/CodeSystem/v2-0116","code":"O","display":"Occupied"},
             "name":"Synthetic Ward","mode":"instance",
             "telecom":[{"system":"phone","value":"+46000000002","use":"work","period":{"start":"2020-01-01"}}],
             "position":{"longitude":18.0686000,"latitude":59.3293000},
             "managingOrganization":{"reference":"Organization/synthetic-org","display":"Synthetic Clinic"},
             "partOf":{"reference":"Location/synthetic-building","display":"Synthetic Building"},
             "hoursOfOperation":[{"daysOfWeek":["mon"],"openingTime":"08:00:00","closingTime":"17:00:00"}],
             "availabilityExceptions":"Synthetic availability exceptions",
             "endpoint":[{"reference":"Endpoint/synthetic-endpoint"}]}
            """;

    private final Map<String, Location> store = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> ifMatches = new CopyOnWriteArrayList<>();
    private final List<String> putBodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger createdIds = new AtomicInteger();
    private HttpServer server;
    private String base;

    // Knobs for faulty or unusual server behaviour.
    private Integer getFailure;
    private String getBody;
    private boolean etag = true;
    private Consumer<Location> afterGet;
    private Integer putFailure;
    private int putStatus = 200;
    private boolean contentLocation = true;
    private boolean returnBody = true;
    private String locationId;
    private String locationVersion;
    private String bodyVersion;
    private String locationType = "Location";

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
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath().substring("/fhir/".length());
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(method + " " + exchange.getRequestURI());
        if (method.equals("POST") && path.equals("Location")) {
            Location created = CONTEXT.newJsonParser().parseResource(Location.class, body);
            String id = "server-" + createdIds.incrementAndGet();
            stamp(created, id, "1");
            store.put(id, created);
            exchange.getResponseHeaders().set("Location", base + "/Location/" + id + "/_history/1");
            send(exchange, 201, created);
            return;
        }
        String id = path.substring("Location/".length());
        Location stored = store.get(id);
        if (method.equals("GET")) {
            if (getFailure != null) { outcome(exchange, getFailure, OperationOutcome.IssueType.PROCESSING); return; }
            if (getBody != null) { sendRaw(exchange, 200, getBody); return; }
            if (stored == null) { outcome(exchange, 404, OperationOutcome.IssueType.NOTFOUND); return; }
            if (etag && stored.getMeta().hasVersionId()) {
                exchange.getResponseHeaders().set("ETag", "W/\"" + stored.getMeta().getVersionId() + "\"");
            }
            send(exchange, 200, stored);
            if (afterGet != null) afterGet.accept(stored);
            return;
        }
        if (method.equals("PUT")) {
            String ifMatch = exchange.getRequestHeaders().getFirst("If-Match");
            ifMatches.add(String.valueOf(ifMatch));
            putBodies.add(body);
            if (putFailure != null) { outcome(exchange, putFailure, OperationOutcome.IssueType.CONFLICT); return; }
            if (stored == null) { outcome(exchange, 404, OperationOutcome.IssueType.NOTFOUND); return; }
            String current = stored.getMeta().getVersionId();
            if (ifMatch == null || !ifMatch.equals("\"" + current + "\"") && !ifMatch.equals("W/\"" + current + "\"")) {
                outcome(exchange, 412, OperationOutcome.IssueType.CONFLICT);
                return;
            }
            Location updated = CONTEXT.newJsonParser().parseResource(Location.class, body);
            String next = String.valueOf(Integer.parseInt(current) + 1);
            stamp(updated, id, next);
            store.put(id, updated);
            if (contentLocation) {
                exchange.getResponseHeaders().set("Content-Location", base + "/" + locationType + "/"
                        + (locationId == null ? id : locationId) + "/_history/" + (locationVersion == null ? next : locationVersion));
            }
            if (!returnBody) {
                exchange.sendResponseHeaders(putStatus, -1);
                exchange.close();
                return;
            }
            Location returned = updated.copy();
            if (bodyVersion != null) returned.getMeta().setVersionId(bodyVersion);
            send(exchange, putStatus, returned);
            return;
        }
        outcome(exchange, 405, OperationOutcome.IssueType.NOTSUPPORTED);
    }

    private static void stamp(Location location, String id, String version) {
        location.setId(id);
        location.getMeta().setVersionId(version).setLastUpdated(new Date());
    }

    private void outcome(HttpExchange exchange, int status, OperationOutcome.IssueType type) throws IOException {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue().setSeverity(OperationOutcome.IssueSeverity.ERROR).setCode(type)
                .setDiagnostics("SENSITIVE server detail about Synthetic Ward");
        send(exchange, status, outcome);
    }

    private void send(HttpExchange exchange, int status, Resource resource) throws IOException {
        sendRaw(exchange, status, CONTEXT.newJsonParser().encodeResourceToString(resource));
    }

    private void sendRaw(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private void seed(String id, String version, String json) {
        Location location = CONTEXT.newJsonParser().parseResource(Location.class, json);
        location.setId(id);
        if (version != null) location.getMeta().setVersionId(version).setLastUpdated(new Date());
        store.put(id, location);
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(FhirClientConfig.class, FhirRequestExecutor.class,
                FhirLocationClient.class, LocationMapper.class)
                .withPropertyValues("medinexus.fhir.base-url=" + base, "medinexus.fhir.connect-timeout=2s",
                        "medinexus.fhir.socket-timeout=3s", "medinexus.fhir.connection-request-timeout=2s");
    }

    /**
     * New desired state: identifier, telecom, managing Organization and parent Location unchanged; name,
     * status and description change; the position moves with exact decimals.
     */
    private static LocationWriteRequest renamed() {
        return new LocationWriteRequest(List.of(new Identifier("urn:synthetic:location", "LOC-1")), Status.SUSPENDED,
                "Renamed Synthetic Ward", null, "Temporarily closed", Mode.INSTANCE, null,
                List.of(new ContactPoint(ContactPointSystem.PHONE, "+46000000002", ContactPointUse.WORK, null)),
                null, null, new Position(new BigDecimal("18.0700000"), new BigDecimal("59.3300000"), new BigDecimal("15.25")),
                "synthetic-org", "synthetic-building");
    }

    private static LocationWriteRequest named(String name) {
        return new LocationWriteRequest(null, null, name, null, null, null, null, null, null, null, null, null, null);
    }

    private static LocationWriteRequest references(String managingOrganizationId, String partOfLocationId) {
        return new LocationWriteRequest(null, null, "Synthetic Ward", null, null, null, null, null, null, null, null,
                managingOrganizationId, partOfLocationId);
    }

    private void assertFailure(Runnable call, FhirServerException.Type type, Integer status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FhirServerException.class, failure -> {
            assertThat(failure.operation()).isEqualTo("location-update");
            assertThat(failure.type()).isEqualTo(type);
            if (status == null) assertThat(failure.httpStatus()).isEmpty();
            else assertThat(failure.httpStatus()).hasValue(status);
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getMessage()).doesNotContain("SENSITIVE", "Synthetic Ward", "127.0.0.1", "loc-1");
        });
    }

    @Test
    void updatesWithIfMatchPreservingUnmanagedFieldsAndReturnsNewVersion() {
        seed("loc-1", "3", CURRENT);
        context().run(ctx -> {
            assertThat(ctx.getBean(IGenericClient.class).getServerBase()).startsWith("http://127.0.0.1:");
            String version = ctx.getBean(FhirLocationClient.class).updateLocation("loc-1", "3", renamed());

            assertThat(version).isEqualTo("4");
            assertThat(requests).containsExactly("GET /fhir/Location/loc-1", "PUT /fhir/Location/loc-1");
            assertThat(ifMatches).containsExactly("\"3\"");
            String body = putBodies.getFirst();
            assertThat(body).doesNotContain("versionId", "lastUpdated", "\"generated\"")
                    .contains("\"position\":{\"longitude\":18.0700000,\"latitude\":59.3300000,\"altitude\":15.25}");
            Location sent = CONTEXT.newJsonParser().parseResource(Location.class, body);
            assertThat(sent.getIdElement().getIdPart()).isEqualTo("loc-1");
            // Managed fields: exactly the desired state.
            assertThat(sent.getName()).isEqualTo("Renamed Synthetic Ward");
            assertThat(sent.getStatus().toCode()).isEqualTo("suspended");
            assertThat(sent.getDescription()).isEqualTo("Temporarily closed");
            // Unchanged entries and references keep hidden subfields and display.
            assertThat(sent.getIdentifierFirstRep().getPeriod().getStartElement().getValueAsString()).isEqualTo("2020-01-01");
            assertThat(sent.getTelecomFirstRep().getPeriod().getStartElement().getValueAsString()).isEqualTo("2020-01-01");
            assertThat(sent.getManagingOrganization().getReference()).isEqualTo("Organization/synthetic-org");
            assertThat(sent.getManagingOrganization().getDisplay()).isEqualTo("Synthetic Clinic");
            assertThat(sent.getPartOf().getReference()).isEqualTo("Location/synthetic-building");
            assertThat(sent.getPartOf().getDisplay()).isEqualTo("Synthetic Building");
            // Unmanaged elements preserved; the stale generated narrative removed.
            assertThat(sent.getOperationalStatus().getCode()).isEqualTo("O");
            assertThat(sent.getHoursOfOperationFirstRep().getClosingTime()).isEqualTo("17:00:00");
            assertThat(sent.getAvailabilityExceptions()).isEqualTo("Synthetic availability exceptions");
            assertThat(sent.getEndpointFirstRep().getReference()).isEqualTo("Endpoint/synthetic-endpoint");
            assertThat(sent.getExtensionByUrl("urn:synthetic:extension").getValue().primitiveValue()).isEqualTo("kept");
            assertThat(sent.getMeta().getSource()).isEqualTo("#synthetic");
            assertThat(sent.getMeta().getTagFirstRep().getCode()).isEqualTo("t");
            assertThat(sent.hasText()).isFalse();
            assertThat(store.get("loc-1").getMeta().getVersionId()).isEqualTo("4");
        });
    }

    @Test
    void changesAndRemovesManagingOrganizationAndParentLocationReferences() {
        seed("loc-1", "3", CURRENT);
        context().run(ctx -> {
            var client = ctx.getBean(FhirLocationClient.class);
            assertThat(client.updateLocation("loc-1", "3", references("other-org", "other-building"))).isEqualTo("4");
            Location changed = store.get("loc-1");
            assertThat(changed.getManagingOrganization().getReference()).isEqualTo("Organization/other-org");
            assertThat(changed.getManagingOrganization().hasDisplay()).isFalse();
            assertThat(changed.getPartOf().getReference()).isEqualTo("Location/other-building");
            assertThat(changed.getPartOf().hasDisplay()).isFalse();

            assertThat(client.updateLocation("loc-1", "4", references(null, null))).isEqualTo("5");
            Location removed = store.get("loc-1");
            assertThat(removed.hasManagingOrganization() || removed.hasPartOf()).isFalse();
            assertThat(removed.getOperationalStatus().getCode()).isEqualTo("O");
        });
        assertThat(ifMatches).containsExactly("\"3\"", "\"4\"");
    }

    @Test
    void staleExpectedVersionIsRejectedLocallyAs412WithoutPut() {
        seed("loc-1", "5", CURRENT);
        context().run(ctx -> assertFailure(() -> ctx.getBean(FhirLocationClient.class)
                .updateLocation("loc-1", "3", renamed()), FhirServerException.Type.CLIENT_ERROR, 412));
        assertThat(requests).containsExactly("GET /fhir/Location/loc-1");
        assertThat(store.get("loc-1").getName()).isEqualTo("Synthetic Ward");
        assertThat(store.get("loc-1").getMeta().getVersionId()).isEqualTo("5");
    }

    @Test
    void concurrentChangeBetweenReadAndWriteIsRejectedByIfMatchWithoutRetry() {
        seed("loc-1", "3", CURRENT);
        // Another writer commits version 4 after our GET returned version 3.
        afterGet = stored -> {
            Location other = stored.copy();
            stamp(other, "loc-1", "4");
            other.setName("Concurrent Synthetic Ward");
            store.put("loc-1", other);
        };
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirLocationClient.class)
                .updateLocation("loc-1", "3", renamed()))
                .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                    assertThat(failure.operation()).isEqualTo("location-update");
                    assertThat(failure.type()).isEqualTo(FhirServerException.Type.CLIENT_ERROR);
                    assertThat(failure.httpStatus()).hasValue(412);
                    assertThat(failure.causeType()).isEqualTo("PreconditionFailedException");
                }));
        assertThat(requests).containsExactly("GET /fhir/Location/loc-1", "PUT /fhir/Location/loc-1");
        assertThat(ifMatches).containsExactly("\"3\"");
        assertThat(store.get("loc-1").getName()).isEqualTo("Concurrent Synthetic Ward");
        assertThat(store.get("loc-1").getMeta().getVersionId()).isEqualTo("4");
    }

    @ParameterizedTest
    @CsvSource({"409, CLIENT_ERROR", "412, CLIENT_ERROR", "422, CLIENT_ERROR", "500, SERVER_ERROR", "503, UNAVAILABLE"})
    void putFailuresAreTranslatedAndNeverRetried(int status, FhirServerException.Type type) {
        seed("loc-1", "3", CURRENT);
        putFailure = status;
        context().run(ctx -> assertFailure(() -> ctx.getBean(FhirLocationClient.class)
                .updateLocation("loc-1", "3", renamed()), type, status));
        assertThat(requests).containsExactly("GET /fhir/Location/loc-1", "PUT /fhir/Location/loc-1");
    }

    @ParameterizedTest
    @CsvSource({"404, CLIENT_ERROR", "410, CLIENT_ERROR", "500, SERVER_ERROR", "503, UNAVAILABLE"})
    void readPhaseFailuresSendNoPut(int status, FhirServerException.Type type) {
        getFailure = status;
        context().run(ctx -> assertFailure(() -> ctx.getBean(FhirLocationClient.class)
                .updateLocation("loc-1", "3", renamed()), type, status));
        assertThat(requests).containsExactly("GET /fhir/Location/loc-1");
    }

    @Test
    void missingLocationIsA404WithoutPut() {
        context().run(ctx -> assertFailure(() -> ctx.getBean(FhirLocationClient.class)
                .updateLocation("loc-1", "3", renamed()), FhirServerException.Type.CLIENT_ERROR, 404));
        assertThat(requests).containsExactly("GET /fhir/Location/loc-1");
        assertThat(store).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"resourceType\":\"Location\",\"id\":\"loc-1\",\"name\":\"Synthetic Ward\"}",
        "{\"resourceType\":\"Location\",\"id\":\"other-loc\",\"meta\":{\"versionId\":\"3\"},\"name\":\"Synthetic Ward\"}",
        "{\"resourceType\":\"Location\",\"id\":\"loc-1\",\"meta\":{\"versionId\":\"bad_version\"},\"name\":\"Synthetic Ward\"}",
        "{\"resourceType\":\"Organization\",\"id\":\"loc-1\",\"meta\":{\"versionId\":\"3\"},\"name\":\"Synthetic Ward\"}",
        "{\"resourceType\":\"Location\",\"id\":\"loc-1\",\"meta\":{\"versionId\":\"3\"},\"implicitRules\":\"urn:synthetic:rules\",\"name\":\"Synthetic Ward\"}",
        "{\"resourceType\":\"Location\",\"id\":\"loc-1\",\"meta\":{\"versionId\":\"3\"},\"modifierExtension\":[{\"url\":\"urn:synthetic:modifier\",\"valueBoolean\":true}],\"name\":\"Synthetic Ward\"}",
        "{\"resourceType\":\"Location\",\"id\":\"loc-1\",\"meta\":{\"versionId\":\"3\"},\"position\":{\"modifierExtension\":[{\"url\":\"urn:synthetic:modifier\",\"valueBoolean\":true}],\"longitude\":1,\"latitude\":2}}",
        "{\"resourceType\":\"Location\",\"id\":\"loc-1\",\"name\":"
    })
    void unversionedMismatchedUnsafeOrMalformedCurrentResourcesSendNoPut(String currentBody) {
        getBody = currentBody;
        context().run(ctx -> assertFailure(() -> ctx.getBean(FhirLocationClient.class)
                .updateLocation("loc-1", "3", renamed()), FhirServerException.Type.INVALID_RESPONSE, null));
        assertThat(requests).containsExactly("GET /fhir/Location/loc-1");
    }

    @Test
    void currentResourceWithoutAnyVersionSendsNoPut() {
        seed("loc-1", null, CURRENT);
        etag = false;
        context().run(ctx -> assertFailure(() -> ctx.getBean(FhirLocationClient.class)
                .updateLocation("loc-1", "3", renamed()), FhirServerException.Type.INVALID_RESPONSE, null));
        assertThat(requests).containsExactly("GET /fhir/Location/loc-1");
    }

    @Test
    void invalidInputsAndSelfReferencesSendNothing() {
        seed("loc-1", "3", CURRENT);
        context().run(ctx -> {
            var client = ctx.getBean(FhirLocationClient.class);
            for (String id : new String[] {null, "", "Location/loc-1", "loc_1", "x".repeat(65)}) {
                assertThatThrownBy(() -> client.updateLocation(id, "3", renamed()))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("A valid logical FHIR Location ID is required");
            }
            for (String version : new String[] {null, "", " 3", "W/\"3\"", "3_1", "x".repeat(65)}) {
                assertThatThrownBy(() -> client.updateLocation("loc-1", version, renamed()))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("A valid expected FHIR Location version ID is required");
            }
            for (LocationWriteRequest request : Arrays.asList(null, named(" "), references("Organization/org", null),
                    references(null, "Location/parent"), new LocationWriteRequest(null, null, "N", null, null, null,
                            null, null, null, null, new Position(BigDecimal.ZERO, new BigDecimal("91"), null), null, null))) {
                assertThatThrownBy(() -> client.updateLocation("loc-1", "3", request))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Invalid Location write request");
            }
            assertThatThrownBy(() -> client.updateLocation("loc-1", "3", references(null, "loc-1")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("A Location cannot be part of itself");
        });
        assertThat(requests).isEmpty();
        assertThat(store.get("loc-1").getMeta().getVersionId()).isEqualTo("3");
    }

    @Test
    void managingOrganizationWithTheSameLogicalIdIsNotASelfReference() {
        seed("shared-id", "3", CURRENT);
        context().run(ctx -> assertThat(ctx.getBean(FhirLocationClient.class)
                .updateLocation("shared-id", "3", references("shared-id", null))).isEqualTo("4"));
        assertThat(store.get("shared-id").getManagingOrganization().getReference()).isEqualTo("Organization/shared-id");
    }

    @Test
    void acceptsVersionFromReturnedResourceWhenContentLocationIsAbsent() {
        seed("loc-1", "3", CURRENT);
        contentLocation = false;
        context().run(ctx -> assertThat(ctx.getBean(FhirLocationClient.class)
                .updateLocation("loc-1", "3", renamed())).isEqualTo("4"));
        assertThat(requests).hasSize(2);
    }

    @Test
    void acceptsContentLocationWithoutResponseBody() {
        seed("loc-1", "3", CURRENT);
        returnBody = false;
        context().run(ctx -> assertThat(ctx.getBean(FhirLocationClient.class)
                .updateLocation("loc-1", "3", renamed())).isEqualTo("4"));
    }

    @Test
    void responseWithoutAnyNewVersionIsInvalid() {
        seed("loc-1", "3", CURRENT);
        contentLocation = false;
        returnBody = false;
        assertInvalidUpdateResponse();
    }

    @Test
    void responseNamingAnotherLocationIsInvalid() {
        seed("loc-1", "3", CURRENT);
        locationId = "other-loc";
        assertInvalidUpdateResponse();
    }

    @Test
    void responseNamingAnotherResourceTypeIsInvalid() {
        seed("loc-1", "3", CURRENT);
        locationType = "Organization";
        assertInvalidUpdateResponse();
    }

    @Test
    void responseWithUnchangedVersionIsInvalid() {
        seed("loc-1", "3", CURRENT);
        locationVersion = "3";
        bodyVersion = "3";
        assertInvalidUpdateResponse();
    }

    @Test
    void responseWithInvalidVersionIsInvalid() {
        seed("loc-1", "3", CURRENT);
        contentLocation = false;
        bodyVersion = "bad_version";
        assertInvalidUpdateResponse();
    }

    @Test
    void responseWhoseBodyAndHeaderVersionsDisagreeIsInvalid() {
        seed("loc-1", "3", CURRENT);
        bodyVersion = "9";
        assertInvalidUpdateResponse();
    }

    @Test
    void responseClaimingCreationIsInvalid() {
        seed("loc-1", "3", CURRENT);
        putStatus = 201;
        assertInvalidUpdateResponse();
    }

    private void assertInvalidUpdateResponse() {
        context().run(ctx -> assertFailure(() -> ctx.getBean(FhirLocationClient.class)
                .updateLocation("loc-1", "3", renamed()), FhirServerException.Type.INVALID_RESPONSE, null));
        // The write may have been committed; the client reports it but never retries.
        assertThat(requests).containsExactly("GET /fhir/Location/loc-1", "PUT /fhir/Location/loc-1");
    }

    @Test
    void createReadUpdateReadAndStaleUpdateRoundTrip() {
        context().run(ctx -> {
            var client = ctx.getBean(FhirLocationClient.class);
            var mapper = ctx.getBean(LocationMapper.class);

            String id = client.createLocation(new LocationWriteRequest(List.of(new Identifier("urn:synthetic:location", "RT-1")),
                    Status.ACTIVE, "Round Trip Synthetic Ward", null, null, Mode.INSTANCE, null, null, null, null,
                    new Position(new BigDecimal("18.0686000"), new BigDecimal("59.3293000"), null),
                    "synthetic-org", "synthetic-building"));
            assertThat(id).isEqualTo("server-1");
            LocationDto created = mapper.toDto(client.readLocation(id));
            assertThat(created.versionId()).isEqualTo("1");
            assertThat(created.name()).isEqualTo("Round Trip Synthetic Ward");
            assertThat(created.position().latitude()).isEqualTo(new BigDecimal("59.3293000"));
            assertThat(created.managingOrganization().reference()).isEqualTo("Organization/synthetic-org");
            assertThat(created.partOf().reference()).isEqualTo("Location/synthetic-building");

            // Edit the read projection: keep identifiers and references, rename and deactivate.
            var edited = new LocationWriteRequest(created.identifiers(), Status.INACTIVE, "Renamed Round Trip Ward",
                    created.aliases(), created.description(), Mode.INSTANCE, created.types(), null, null, null,
                    created.position(), "synthetic-org", "synthetic-building");
            assertThat(client.updateLocation(id, created.versionId(), edited)).isEqualTo("2");
            LocationDto updated = mapper.toDto(client.readLocation(id));
            assertThat(updated.versionId()).isEqualTo("2");
            assertThat(updated.name()).isEqualTo("Renamed Round Trip Ward");
            assertThat(updated.status()).isEqualTo("inactive");
            assertThat(updated.identifiers()).isEqualTo(created.identifiers());
            assertThat(updated.position()).isEqualTo(created.position());
            assertThat(updated.managingOrganization()).isEqualTo(created.managingOrganization());
            assertThat(updated.partOf()).isEqualTo(created.partOf());

            // A second update based on the outdated version 1 must not overwrite version 2.
            assertThatThrownBy(() -> client.updateLocation(id, created.versionId(), named("Lost Update")))
                    .isInstanceOfSatisfying(FhirServerException.class,
                            failure -> assertThat(failure.httpStatus()).hasValue(412));
            assertThat(mapper.toDto(client.readLocation(id)).name()).isEqualTo("Renamed Round Trip Ward");
        });
        assertThat(requests).containsExactly("POST /fhir/Location", "GET /fhir/Location/server-1",
                "GET /fhir/Location/server-1", "PUT /fhir/Location/server-1", "GET /fhir/Location/server-1",
                "GET /fhir/Location/server-1", "GET /fhir/Location/server-1");
        assertThat(ifMatches).containsExactly("\"1\"");
    }

    @Test
    void conflictIsLoggedOnceWithoutIdentifiersNamesUrlsOrDiagnostics(CapturedOutput output) {
        seed("secret-loc-77", "5", CURRENT);
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirLocationClient.class)
                .updateLocation("secret-loc-77", "3", renamed())).isInstanceOf(FhirServerException.class));
        assertThat(output.getAll())
                .contains("FHIR request failed: operation=location-update, type=CLIENT_ERROR, httpStatus=412, "
                        + "issues=[], cause=PreconditionFailedException, durationMs=")
                .doesNotContain("secret-loc-77", "Synthetic Ward", "+46000000002", "18.07", "SENSITIVE", "127.0.0.1",
                        "/Location", "synthetic-org");
        assertThat(output.getAll().split("FHIR request failed", -1)).hasSize(2);
    }

    @Test
    void serverConflictLogContainsNoServerDiagnostics(CapturedOutput output) {
        seed("loc-1", "3", CURRENT);
        putFailure = 409;
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirLocationClient.class)
                .updateLocation("loc-1", "3", renamed())).isInstanceOf(FhirServerException.class));
        assertThat(output.getAll())
                .contains("FHIR request failed: operation=location-update, type=CLIENT_ERROR, httpStatus=409, "
                        + "issues=[error:conflict], cause=ResourceVersionConflictException, durationMs=")
                .doesNotContain("SENSITIVE", "Synthetic Ward", "127.0.0.1", "/Location");
    }
}
