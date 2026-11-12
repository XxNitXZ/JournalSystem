package com.medinexus.medicore.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.sun.net.httpserver.HttpServer;
import ca.uhn.fhir.context.FhirContext;

@SpringBootTest
@Transactional
class ObservationIntegrationTest {
    private static final FhirContext FHIR = FhirContext.forR4();
    private static final List<String> CALLS = new CopyOnWriteArrayList<>();
    private static final List<Observation> STORED = new CopyOnWriteArrayList<>();
    private static int failureStatus;
    private static boolean wrongPatient;
    private static final HttpServer SERVER = server();
    private static final String BODY = """
            {"status":"FINAL","code":{"text":"Synthetic test","coding":[{"system":"urn:synthetic","code":"test"}]},
             "effectiveDateTime":"2026-06-15T12:34:56.123456+02:00",
             "valueQuantity":{"value":12.500,"unit":"mg","system":"http://unitsofmeasure.org","code":"mg"}}
            """;
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    private MockMvc mvc;
    private Long doctor;
    private Long staff;
    private Long patient;
    private Long other;
    private String patientId;
    private String otherPatientId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("medinexus.fhir.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/fhir");
    }
    @AfterAll static void stop() { SERVER.stop(0); }
    @BeforeEach void setup() {
        CALLS.clear(); STORED.clear(); failureStatus = 0; wrongPatient = false;
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        patientId = "synthetic-" + UUID.randomUUID();
        patient = account(Role.PATIENT, patientId);
        otherPatientId = "other-" + UUID.randomUUID();
        other = account(Role.PATIENT, otherPatientId);
        doctor = account(Role.DOCTOR, null); staff = account(Role.STAFF, null);
    }

    @ParameterizedTest @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void createsAndListsForClinicianAndOwnPatient(Role role) throws Exception {
        Long clinician = role == Role.DOCTOR ? doctor : staff;
        mvc.perform(post(path(patientId)).with(as(clinician, role)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value("created-1"))
                .andExpect(jsonPath("$.subject.reference").value("Patient/" + patientId))
                .andExpect(jsonPath("$.effective.data.value").value("2026-06-15T12:34:56.123456+02:00"));
        assertThat(STORED.getFirst().getValueQuantity().getValue()).isEqualByComparingTo("12.500");
        assertThat(STORED.getFirst().getEffectiveDateTimeType().getValueAsString()).isEqualTo("2026-06-15T12:34:56.123456+02:00");
        mvc.perform(get(path(patientId)).with(as(clinician, role))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get(path("me")).with(as(patient, Role.PATIENT))).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value("created-1"));
        mvc.perform(get(path(patientId)).with(as(other, Role.PATIENT))).andExpect(status().isForbidden());
        wrongPatient = true;
        mvc.perform(get(path("me")).with(as(other, Role.PATIENT))).andExpect(status().isBadGateway());
    }

    @Test
    void twoPatientsReceiveOnlyTheirOwnObservationsDespiteManipulatedUrls() throws Exception {
        for (String id : List.of(patientId, otherPatientId)) {
            String label = id.equals(patientId) ? "First patient observation" : "Second patient observation";
            mvc.perform(post(path(id)).with(as(doctor, Role.DOCTOR)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("Synthetic test", label)))
                    .andExpect(status().isCreated());
        }
        assertThat(STORED).hasSize(2);
        // Switch authenticated identities while retaining the same two resources on the FHIR server.
        for (Long accountId : List.of(patient, other)) {
            boolean first = accountId.equals(patient);
            String ownId = first ? patientId : otherPatientId;
            String foreignId = first ? otherPatientId : patientId;
            String expectedObservationId = first ? "created-1" : "created-2";
            String expectedLabel = first ? "First patient observation" : "Second patient observation";
            CALLS.clear();
            mvc.perform(get(path("me")).with(as(accountId, Role.PATIENT))
                            .param("patientId", foreignId).param("subject", "Patient/" + foreignId))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].id").value(expectedObservationId))
                    .andExpect(jsonPath("$[0].code.text").value(expectedLabel))
                    .andExpect(jsonPath("$[0].subject.reference").value("Patient/" + ownId));
            assertThat(CALLS).hasSize(2);
            assertThat(CALLS.get(0)).isEqualTo("GET /fhir/Patient/" + ownId);
            assertThat(URLDecoder.decode(CALLS.get(1), StandardCharsets.UTF_8))
                    .contains("subject=Patient/" + ownId).doesNotContain(foreignId);
            int before = CALLS.size();
            mvc.perform(get(path(foreignId)).with(as(accountId, Role.PATIENT)))
                    .andExpect(status().isForbidden());
            assertThat(CALLS).hasSize(before);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"2026", "2026-06", "2026-06-15", "2026-06-15T12:00:00Z", "2026-06-15T12:00:00.123456789123-05:30"})
    void preservesValidDatePrecision(String date) throws Exception {
        mvc.perform(post(path(patientId)).with(as(doctor, Role.DOCTOR)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(BODY.replace("2026-06-15T12:34:56.123456+02:00", date))).andExpect(status().isCreated());
        assertThat(STORED.getFirst().getEffectiveDateTimeType().getValueAsString()).isEqualTo(date);
    }

    @ParameterizedTest @ValueSource(strings = {"", "not-a-date", "0000", "2026-02-30", "2026-13", "2026-06-15T12:00:00", "2026-06-15T12:00:00+15:00"})
    void rejectsInvalidDatesBeforeRemoteRequests(String date) throws Exception {
        mvc.perform(post(path(patientId)).with(as(doctor, Role.DOCTOR)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(BODY.replace("2026-06-15T12:34:56.123456+02:00", date))).andExpect(status().isBadRequest());
        assertThat(CALLS).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "null", "{", "{\"status\":\"FINAL\",\"subject\":\"Patient/other\"}",
            "{\"senderId\":1}", "{\"patientId\":\"other\"}"})
    void rejectsMalformedOrForgedFieldsWithoutRemoteRequests(String body) throws Exception {
        mvc.perform(post(path(patientId)).with(as(doctor, Role.DOCTOR)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertThat(CALLS).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"subject", "patientId", "senderId", "issued"})
    void rejectsIdentityAndServerFieldsEvenInOtherwiseValidRequests(String field) throws Exception {
        String body = BODY.replaceFirst("\\{", "{\"" + field + "\":\"forged\",");
        mvc.perform(post(path(patientId)).with(as(doctor, Role.DOCTOR)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        assertThat(CALLS).isEmpty();
    }

    @Test void rejectsMissingDateConflictingValuesAndUnknownNestedFields() throws Exception {
        for (String body : List.of(
                BODY.replace("\"effectiveDateTime\":\"2026-06-15T12:34:56.123456+02:00\",", ""),
                BODY.replace("\"status\":\"FINAL\",", "\"status\":\"FINAL\",\"dataAbsentReason\":{\"text\":\"Unknown\"},"),
                BODY.replace("\"unit\":\"mg\"", "\"unit\":\"mg\",\"unknown\":true"))) {
            mvc.perform(post(path(patientId)).with(as(doctor, Role.DOCTOR)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        assertThat(CALLS).isEmpty();
    }

    @Test void supportsCodedAndAbsentValues() throws Exception {
        for (String value : List.of("\"valueCodeableConcept\":{\"text\":\"Synthetic coded result\"}", "\"dataAbsentReason\":{\"text\":\"Not measured\"}")) {
            String body = "{\"status\":\"FINAL\",\"code\":{\"text\":\"Synthetic\"},\"effectiveDateTime\":\"2026\"," + value + "}";
            mvc.perform(post(path(patientId)).with(as(staff, Role.STAFF)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated());
        }
        assertThat(STORED.get(0).getValue()).isInstanceOf(org.hl7.fhir.r4.model.CodeableConcept.class);
        assertThat(STORED.get(1).hasDataAbsentReason()).isTrue();
    }

    @Test void enforcesAnonymousRolesStaleAccountsCsrfAndRegistry() throws Exception {
        mvc.perform(get(path("me"))).andExpect(status().isUnauthorized());
        mvc.perform(post(path(patientId)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(post(path(patientId)).with(as(patient, Role.PATIENT)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(post(path(patientId)).with(as(doctor, Role.DOCTOR)).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(post(path(patientId)).with(as(patient, Role.DOCTOR)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(get(path("me")).with(as(doctor, Role.PATIENT))).andExpect(status().isForbidden());
        mvc.perform(get(path(patientId)).with(as(-1L, Role.STAFF))).andExpect(status().isForbidden());
        mvc.perform(get(path("unregistered")).with(as(doctor, Role.DOCTOR))).andExpect(status().isNotFound());
        mvc.perform(get(path("bad_id")).with(as(doctor, Role.DOCTOR))).andExpect(status().isBadRequest());
        Long unlinked = account(Role.PATIENT, null);
        mvc.perform(get(path("me")).with(as(unlinked, Role.PATIENT))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RECORD_NOT_LINKED"));
        assertThat(CALLS).isEmpty();
    }

    @ParameterizedTest @ValueSource(ints = {404, 410, 422, 500, 503})
    void translatesFhirFailuresSafely(int externalStatus) throws Exception {
        failureStatus = externalStatus;
        int expected = externalStatus == 404 || externalStatus == 410 ? 404 : externalStatus == 422 ? 422 : externalStatus == 503 ? 503 : 502;
        mvc.perform(get(path(patientId)).with(as(doctor, Role.DOCTOR))).andExpect(status().is(expected));
        assertThat(STORED).isEmpty();
    }

    @Test void failsClosedForMismatchedPatientResponse() throws Exception {
        wrongPatient = true;
        mvc.perform(post(path(patientId)).with(as(doctor, Role.DOCTOR)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadGateway());
        assertThat(STORED).isEmpty();
    }

    private String path(String id) { return "/api/patients/" + id + "/observations"; }
    private Long account(Role role, String fhirId) {
        return jdbc.queryForObject("INSERT INTO users (email,password_hash,role,fhir_patient_id) VALUES (?,'synthetic-hash',?,?) RETURNING id",
                Long.class, UUID.randomUUID() + "@example.test", role.name(), fhirId);
    }
    private RequestPostProcessor as(Long id, Role role) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(new UserDto(id, "synthetic@example.test", role),
                null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }
    private static HttpServer server() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/fhir/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                CALLS.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                if (failureStatus != 0) {
                    exchange.sendResponseHeaders(failureStatus, -1); exchange.close(); return;
                }
                Resource result;
                int status = 200;
                if (path.contains("/Patient/")) {
                    Patient patient = new Patient(); patient.setId(wrongPatient ? "wrong" : path.substring(path.lastIndexOf('/') + 1)); result = patient;
                } else if (exchange.getRequestMethod().equals("POST")) {
                    Observation observation = FHIR.newJsonParser().parseResource(Observation.class,
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    observation.setId("created-" + (STORED.size() + 1)); STORED.add(observation);
                    exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/fhir/Observation/" + observation.getIdElement().getIdPart());
                    exchange.sendResponseHeaders(201, -1); exchange.close(); return;
                } else {
                    Bundle bundle = new Bundle().setType(Bundle.BundleType.SEARCHSET);
                    String query = exchange.getRequestURI().getRawQuery();
                    String subject = query == null ? "" : java.util.Arrays.stream(query.split("&"))
                            .map(part -> URLDecoder.decode(part, StandardCharsets.UTF_8))
                            .filter(part -> part.startsWith("subject="))
                            .map(part -> part.substring("subject=".length())).findFirst().orElse("");
                    for (Observation observation : STORED) {
                        if (wrongPatient || subject.equals(observation.getSubject().getReference())) {
                            bundle.addEntry().setResource(observation.copy());
                        }
                    }
                    result = bundle;
                }
                byte[] bytes = FHIR.newJsonParser().encodeResourceToString(result).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            server.start(); return server;
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
}
