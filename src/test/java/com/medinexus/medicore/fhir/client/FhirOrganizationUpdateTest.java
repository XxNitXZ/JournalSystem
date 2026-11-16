package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
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

import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Organization;
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
import com.medinexus.medicore.fhir.mapper.OrganizationMapper;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Identifier;
import com.medinexus.medicore.organization.dto.OrganizationDto;
import com.medinexus.medicore.organization.dto.OrganizationWriteRequest;
import com.medinexus.medicore.organization.dto.OrganizationWriteRequest.ContactPoint;
import com.medinexus.medicore.organization.dto.OrganizationWriteRequest.ContactPointSystem;
import com.medinexus.medicore.organization.dto.OrganizationWriteRequest.ContactPointUse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;

/**
 * Read-modify-write updates against a local, versioned HTTP server that enforces If-Match like a
 * versioned-update FHIR server. Synthetic data only; nothing is sent to an external FHIR server.
 */
@ExtendWith(OutputCaptureExtension.class)
class FhirOrganizationUpdateTest {

    private static final FhirContext CONTEXT = FhirContext.forR4();

    /** Unmanaged elements (contact, endpoint, extension, meta tag) plus managed fields with hidden subfields. */
    private static final String CURRENT = """
            {"resourceType":"Organization",
             "meta":{"source":"#synthetic","tag":[{"system":"urn:synthetic:tag","code":"t"}]},
             "text":{"status":"generated","div":"<div xmlns=\\"http://www.w3.org/1999/xhtml\\">Synthetic Clinic</div>"},
             "extension":[{"url":"urn:synthetic:extension","valueString":"kept"}],
             "identifier":[{"use":"official","system":"urn:synthetic:org","value":"ORG-1","period":{"start":"2020-01-01"}}],
             "active":true,"name":"Synthetic Clinic",
             "telecom":[{"system":"phone","value":"+46000000000","use":"work","period":{"start":"2020-01-01"}}],
             "contact":[{"name":{"text":"Synthetic Contact Person"}}],
             "endpoint":[{"reference":"Endpoint/synthetic-endpoint"}]}
            """;

    private final Map<String, Organization> store = new ConcurrentHashMap<>();
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
    private Consumer<Organization> afterGet;
    private Integer putFailure;
    private int putStatus = 200;
    private boolean contentLocation = true;
    private boolean returnBody = true;
    private String locationId;
    private String locationVersion;
    private String bodyVersion;
    private String locationType = "Organization";

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
        if (method.equals("POST") && path.equals("Organization")) {
            Organization created = CONTEXT.newJsonParser().parseResource(Organization.class, body);
            String id = "server-" + createdIds.incrementAndGet();
            stamp(created, id, "1");
            store.put(id, created);
            exchange.getResponseHeaders().set("Location", base + "/Organization/" + id + "/_history/1");
            send(exchange, 201, created);
            return;
        }
        String id = path.substring("Organization/".length());
        Organization stored = store.get(id);
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
            Organization updated = CONTEXT.newJsonParser().parseResource(Organization.class, body);
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
            Organization returned = updated.copy();
            if (bodyVersion != null) returned.getMeta().setVersionId(bodyVersion);
            send(exchange, putStatus, returned);
            return;
        }
        outcome(exchange, 405, OperationOutcome.IssueType.NOTSUPPORTED);
    }

    private static void stamp(Organization organization, String id, String version) {
        organization.setId(id);
        organization.getMeta().setVersionId(version).setLastUpdated(new Date());
    }

    private void outcome(HttpExchange exchange, int status, OperationOutcome.IssueType type) throws IOException {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue().setSeverity(OperationOutcome.IssueSeverity.ERROR).setCode(type)
                .setDiagnostics("SENSITIVE server detail about Synthetic Clinic");
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
        Organization organization = CONTEXT.newJsonParser().parseResource(Organization.class, json);
        organization.setId(id);
        if (version != null) organization.getMeta().setVersionId(version).setLastUpdated(new Date());
        store.put(id, organization);
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(FhirClientConfig.class, FhirRequestExecutor.class,
                FhirOrganizationClient.class, OrganizationMapper.class)
                .withPropertyValues("medinexus.fhir.base-url=" + base, "medinexus.fhir.connect-timeout=2s",
                        "medinexus.fhir.socket-timeout=3s", "medinexus.fhir.connection-request-timeout=2s");
    }

    /** New desired state: the identifier and telecom stay unchanged, name and active change, an alias is added. */
    private static OrganizationWriteRequest renamed() {
        return new OrganizationWriteRequest(List.of(new Identifier("urn:synthetic:org", "ORG-1")), false, null,
                "Renamed Synthetic Clinic", List.of("Former Synthetic Clinic"),
                List.of(new ContactPoint(ContactPointSystem.PHONE, "+46000000000", ContactPointUse.WORK, null)),
                null, null);
    }

    private static OrganizationWriteRequest named(String name) {
        return new OrganizationWriteRequest(null, null, null, name, null, null, null, null);
    }

    private void assertFailure(Runnable call, String operation, FhirServerException.Type type, Integer status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FhirServerException.class, failure -> {
            assertThat(failure.operation()).isEqualTo(operation);
            assertThat(failure.type()).isEqualTo(type);
            if (status == null) assertThat(failure.httpStatus()).isEmpty();
            else assertThat(failure.httpStatus()).hasValue(status);
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getMessage()).doesNotContain("SENSITIVE", "Synthetic Clinic", "127.0.0.1", "org-1");
        });
    }

    @Test
    void updatesWithIfMatchPreservingUnmanagedFieldsAndReturnsNewVersion() {
        seed("org-1", "3", CURRENT);
        context().run(ctx -> {
            assertThat(ctx.getBean(IGenericClient.class).getServerBase()).startsWith("http://127.0.0.1:");
            String version = ctx.getBean(FhirOrganizationClient.class).updateOrganization("org-1", "3", renamed());

            assertThat(version).isEqualTo("4");
            assertThat(requests).containsExactly("GET /fhir/Organization/org-1", "PUT /fhir/Organization/org-1");
            assertThat(ifMatches).containsExactly("\"3\"");
            String body = putBodies.getFirst();
            assertThat(body).doesNotContain("versionId", "lastUpdated", "\"generated\"");
            Organization sent = CONTEXT.newJsonParser().parseResource(Organization.class, body);
            assertThat(sent.getIdElement().getIdPart()).isEqualTo("org-1");
            // Managed fields: exactly the desired state.
            assertThat(sent.getName()).isEqualTo("Renamed Synthetic Clinic");
            assertThat(sent.getActive()).isFalse();
            assertThat(sent.getAlias()).extracting(alias -> alias.getValue()).containsExactly("Former Synthetic Clinic");
            // Unchanged entries keep hidden subfields.
            assertThat(sent.getIdentifierFirstRep().getUse().toCode()).isEqualTo("official");
            assertThat(sent.getIdentifierFirstRep().getPeriod().getStartElement().getValueAsString()).isEqualTo("2020-01-01");
            assertThat(sent.getTelecomFirstRep().getPeriod().getStartElement().getValueAsString()).isEqualTo("2020-01-01");
            // Unmanaged elements preserved; the stale generated narrative removed.
            assertThat(sent.getContactFirstRep().getName().getText()).isEqualTo("Synthetic Contact Person");
            assertThat(sent.getEndpointFirstRep().getReference()).isEqualTo("Endpoint/synthetic-endpoint");
            assertThat(sent.getExtensionByUrl("urn:synthetic:extension").getValue().primitiveValue()).isEqualTo("kept");
            assertThat(sent.getMeta().getSource()).isEqualTo("#synthetic");
            assertThat(sent.getMeta().getTagFirstRep().getCode()).isEqualTo("t");
            assertThat(sent.hasText()).isFalse();
            assertThat(store.get("org-1").getMeta().getVersionId()).isEqualTo("4");
        });
    }

    @Test
    void staleExpectedVersionIsRejectedLocallyAs412WithoutPut() {
        seed("org-1", "5", CURRENT);
        context().run(ctx -> assertFailure(() -> ctx.getBean(FhirOrganizationClient.class)
                .updateOrganization("org-1", "3", renamed()), "organization-update", FhirServerException.Type.CLIENT_ERROR, 412));
        assertThat(requests).containsExactly("GET /fhir/Organization/org-1");
        assertThat(store.get("org-1").getName()).isEqualTo("Synthetic Clinic");
        assertThat(store.get("org-1").getMeta().getVersionId()).isEqualTo("5");
    }

    @Test
    void concurrentChangeBetweenReadAndWriteIsRejectedByIfMatchWithoutRetry() {
        seed("org-1", "3", CURRENT);
        // Another writer commits version 4 after our GET returned version 3.
        afterGet = stored -> {
            Organization other = stored.copy();
            stamp(other, "org-1", "4");
            other.setName("Concurrent Synthetic Clinic");
            store.put("org-1", other);
        };
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirOrganizationClient.class)
                .updateOrganization("org-1", "3", renamed()))
                .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                    assertThat(failure.operation()).isEqualTo("organization-update");
                    assertThat(failure.type()).isEqualTo(FhirServerException.Type.CLIENT_ERROR);
                    assertThat(failure.httpStatus()).hasValue(412);
                    assertThat(failure.causeType()).isEqualTo("PreconditionFailedException");
                }));
        assertThat(requests).containsExactly("GET /fhir/Organization/org-1", "PUT /fhir/Organization/org-1");
        assertThat(ifMatches).containsExactly("\"3\"");
        assertThat(store.get("org-1").getName()).isEqualTo("Concurrent Synthetic Clinic");
        assertThat(store.get("org-1").getMeta().getVersionId()).isEqualTo("4");
    }

    @ParameterizedTest
    @CsvSource({"409, CLIENT_ERROR", "412, CLIENT_ERROR", "422, CLIENT_ERROR", "500, SERVER_ERROR", "503, UNAVAILABLE"})
    void putFailuresAreTranslatedAndNeverRetried(int status, FhirServerException.Type type) {
        seed("org-1", "3", CURRENT);
        putFailure = status;
        context().run(ctx -> assertFailure(() -> ctx.getBean(FhirOrganizationClient.class)
                .updateOrganization("org-1", "3", renamed()), "organization-update", type, status));
        assertThat(requests).containsExactly("GET /fhir/Organization/org-1", "PUT /fhir/Organization/org-1");
    }

    @ParameterizedTest
    @CsvSource({"404, CLIENT_ERROR", "410, CLIENT_ERROR", "500, SERVER_ERROR", "503, UNAVAILABLE"})
    void readPhaseFailuresSendNoPut(int status, FhirServerException.Type type) {
        getFailure = status;
        context().run(ctx -> assertFailure(() -> ctx.getBean(FhirOrganizationClient.class)
                .updateOrganization("org-1", "3", renamed()), "organization-update", type, status));
        assertThat(requests).containsExactly("GET /fhir/Organization/org-1");
    }

    @Test
    void missingOrganizationIsA404WithoutPut() {
        context().run(ctx -> assertFailure(() -> ctx.getBean(FhirOrganizationClient.class)
                .updateOrganization("org-1", "3", renamed()), "organization-update", FhirServerException.Type.CLIENT_ERROR, 404));
        assertThat(requests).containsExactly("GET /fhir/Organization/org-1");
        assertThat(store).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"resourceType\":\"Organization\",\"id\":\"org-1\",\"name\":\"Synthetic Clinic\"}",
        "{\"resourceType\":\"Organization\",\"id\":\"other-org\",\"meta\":{\"versionId\":\"3\"},\"name\":\"Synthetic Clinic\"}",
        "{\"resourceType\":\"Organization\",\"id\":\"org-1\",\"meta\":{\"versionId\":\"bad_version\"},\"name\":\"Synthetic Clinic\"}",
        "{\"resourceType\":\"Patient\",\"id\":\"org-1\",\"meta\":{\"versionId\":\"3\"}}",
        "{\"resourceType\":\"Organization\",\"id\":\"org-1\",\"meta\":{\"versionId\":\"3\"},\"implicitRules\":\"urn:synthetic:rules\",\"name\":\"Synthetic Clinic\"}",
        "{\"resourceType\":\"Organization\",\"id\":\"org-1\",\"meta\":{\"versionId\":\"3\"},\"modifierExtension\":[{\"url\":\"urn:synthetic:modifier\",\"valueBoolean\":true}],\"name\":\"Synthetic Clinic\"}",
        "{\"resourceType\":\"Organization\",\"id\":\"org-1\",\"name\":"
    })
    void unversionedMismatchedUnsafeOrMalformedCurrentResourcesSendNoPut(String currentBody) {
        getBody = currentBody;
        context().run(ctx -> assertFailure(() -> ctx.getBean(FhirOrganizationClient.class)
                .updateOrganization("org-1", "3", renamed()), "organization-update", FhirServerException.Type.INVALID_RESPONSE, null));
        assertThat(requests).containsExactly("GET /fhir/Organization/org-1");
    }

    @Test
    void currentResourceWithoutAnyVersionSendsNoPut() {
        seed("org-1", null, CURRENT);
        etag = false;
        context().run(ctx -> assertFailure(() -> ctx.getBean(FhirOrganizationClient.class)
                .updateOrganization("org-1", "3", renamed()), "organization-update", FhirServerException.Type.INVALID_RESPONSE, null));
        assertThat(requests).containsExactly("GET /fhir/Organization/org-1");
    }

    @Test
    void invalidInputsSendNothing() {
        seed("org-1", "3", CURRENT);
        context().run(ctx -> {
            var client = ctx.getBean(FhirOrganizationClient.class);
            for (String id : new String[] {null, "", "Organization/org-1", "org_1", "x".repeat(65)}) {
                assertThatThrownBy(() -> client.updateOrganization(id, "3", renamed()))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("A valid logical FHIR Organization ID is required");
            }
            for (String version : new String[] {null, "", " 3", "W/\"3\"", "3_1", "x".repeat(65)}) {
                assertThatThrownBy(() -> client.updateOrganization("org-1", version, renamed()))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("A valid expected FHIR Organization version ID is required");
            }
            for (OrganizationWriteRequest request : Arrays.asList(null, named(null), named(" "),
                    new OrganizationWriteRequest(null, null, null, "N", null, null, null, "bad/parent"))) {
                assertThatThrownBy(() -> client.updateOrganization("org-1", "3", request))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Invalid Organization write request");
            }
            assertThatThrownBy(() -> client.updateOrganization("org-1", "3",
                    new OrganizationWriteRequest(null, null, null, "N", null, null, null, "org-1")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("An Organization cannot be part of itself");
        });
        assertThat(requests).isEmpty();
    }

    @Test
    void acceptsVersionFromReturnedResourceWhenContentLocationIsAbsent() {
        seed("org-1", "3", CURRENT);
        contentLocation = false;
        context().run(ctx -> assertThat(ctx.getBean(FhirOrganizationClient.class)
                .updateOrganization("org-1", "3", renamed())).isEqualTo("4"));
        assertThat(requests).hasSize(2);
    }

    @Test
    void acceptsContentLocationWithoutResponseBody() {
        seed("org-1", "3", CURRENT);
        returnBody = false;
        context().run(ctx -> assertThat(ctx.getBean(FhirOrganizationClient.class)
                .updateOrganization("org-1", "3", renamed())).isEqualTo("4"));
    }

    @Test
    void responseWithoutAnyNewVersionIsInvalid() {
        seed("org-1", "3", CURRENT);
        contentLocation = false;
        returnBody = false;
        assertInvalidUpdateResponse();
    }

    @Test
    void responseNamingAnotherOrganizationIsInvalid() {
        seed("org-1", "3", CURRENT);
        locationId = "other-org";
        assertInvalidUpdateResponse();
    }

    @Test
    void responseNamingAnotherResourceTypeIsInvalid() {
        seed("org-1", "3", CURRENT);
        locationType = "Location";
        assertInvalidUpdateResponse();
    }

    @Test
    void responseWithUnchangedVersionIsInvalid() {
        seed("org-1", "3", CURRENT);
        locationVersion = "3";
        bodyVersion = "3";
        assertInvalidUpdateResponse();
    }

    @Test
    void responseWithInvalidVersionIsInvalid() {
        seed("org-1", "3", CURRENT);
        contentLocation = false;
        bodyVersion = "bad_version";
        assertInvalidUpdateResponse();
    }

    @Test
    void responseWhoseBodyAndHeaderVersionsDisagreeIsInvalid() {
        seed("org-1", "3", CURRENT);
        bodyVersion = "9";
        assertInvalidUpdateResponse();
    }

    @Test
    void responseClaimingCreationIsInvalid() {
        seed("org-1", "3", CURRENT);
        putStatus = 201;
        assertInvalidUpdateResponse();
    }

    private void assertInvalidUpdateResponse() {
        context().run(ctx -> assertFailure(() -> ctx.getBean(FhirOrganizationClient.class)
                .updateOrganization("org-1", "3", renamed()), "organization-update", FhirServerException.Type.INVALID_RESPONSE, null));
        // The write may have been committed; the client reports it but never retries.
        assertThat(requests).containsExactly("GET /fhir/Organization/org-1", "PUT /fhir/Organization/org-1");
    }

    @Test
    void createReadUpdateReadAndStaleUpdateRoundTrip() {
        context().run(ctx -> {
            var client = ctx.getBean(FhirOrganizationClient.class);
            var mapper = ctx.getBean(OrganizationMapper.class);

            String id = client.createOrganization(new OrganizationWriteRequest(List.of(new Identifier("urn:synthetic:org", "RT-1")),
                    true, null, "Round Trip Synthetic Clinic", null, null, null, null));
            assertThat(id).isEqualTo("server-1");
            OrganizationDto created = mapper.toDto(client.readOrganization(id));
            assertThat(created.versionId()).isEqualTo("1");
            assertThat(created.name()).isEqualTo("Round Trip Synthetic Clinic");

            // Edit the read projection: keep the identifier, rename and deactivate.
            var edited = new OrganizationWriteRequest(created.identifiers(), false, created.types(),
                    "Renamed Round Trip Clinic", created.aliases(), null, null, null);
            assertThat(client.updateOrganization(id, created.versionId(), edited)).isEqualTo("2");
            OrganizationDto updated = mapper.toDto(client.readOrganization(id));
            assertThat(updated.versionId()).isEqualTo("2");
            assertThat(updated.name()).isEqualTo("Renamed Round Trip Clinic");
            assertThat(updated.active()).isFalse();
            assertThat(updated.identifiers()).isEqualTo(created.identifiers());

            // A second update based on the outdated version 1 must not overwrite version 2.
            assertThatThrownBy(() -> client.updateOrganization(id, created.versionId(), named("Lost Update")))
                    .isInstanceOfSatisfying(FhirServerException.class,
                            failure -> assertThat(failure.httpStatus()).hasValue(412));
            assertThat(mapper.toDto(client.readOrganization(id)).name()).isEqualTo("Renamed Round Trip Clinic");
        });
        assertThat(requests).containsExactly("POST /fhir/Organization", "GET /fhir/Organization/server-1",
                "GET /fhir/Organization/server-1", "PUT /fhir/Organization/server-1", "GET /fhir/Organization/server-1",
                "GET /fhir/Organization/server-1", "GET /fhir/Organization/server-1");
        assertThat(ifMatches).containsExactly("\"1\"");
    }

    @Test
    void conflictIsLoggedOnceWithoutIdentifiersNamesUrlsOrDiagnostics(CapturedOutput output) {
        seed("secret-org-77", "5", CURRENT);
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirOrganizationClient.class)
                .updateOrganization("secret-org-77", "3", renamed())).isInstanceOf(FhirServerException.class));
        assertThat(output.getAll())
                .contains("FHIR request failed: operation=organization-update, type=CLIENT_ERROR, httpStatus=412, "
                        + "issues=[], cause=PreconditionFailedException, durationMs=")
                .doesNotContain("secret-org-77", "Synthetic Clinic", "+46000000000", "SENSITIVE", "127.0.0.1", "/Organization");
        assertThat(output.getAll().split("FHIR request failed", -1)).hasSize(2);
    }

    @Test
    void serverConflictLogContainsNoServerDiagnostics(CapturedOutput output) {
        seed("org-1", "3", CURRENT);
        putFailure = 409;
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirOrganizationClient.class)
                .updateOrganization("org-1", "3", renamed())).isInstanceOf(FhirServerException.class));
        assertThat(output.getAll())
                .contains("FHIR request failed: operation=organization-update, type=CLIENT_ERROR, httpStatus=409, "
                        + "issues=[error:conflict], cause=ResourceVersionConflictException, durationMs=")
                .doesNotContain("SENSITIVE", "Synthetic Clinic", "127.0.0.1", "/Organization");
    }
}
