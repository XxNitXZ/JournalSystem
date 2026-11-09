package com.medinexus.medicore.patient.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.service.AuthenticationService;
import com.medinexus.medicore.config.SecurityConfig;
import com.medinexus.medicore.fhir.client.DuplicatePatientException;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.patient.dto.CreatePatientRequest;
import com.medinexus.medicore.patient.dto.PatientDto;
import com.medinexus.medicore.patient.dto.PatientDto.PatientIdentifier;
import com.medinexus.medicore.patient.dto.PatientDto.PatientName;
import com.medinexus.medicore.patient.service.PatientNotFoundException;
import com.medinexus.medicore.patient.service.PatientService;

/**
 * US-04.01 and US-04.02: HTTP contract and route security of POST /api/patients and GET /api/patients/{id}.
 * Synthetic test personnummer only.
 */
@WebMvcTest(PatientController.class)
@Import(SecurityConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class PatientControllerTest {

    private static final String PERSONNUMMER = "199001012385";
    private static final String BODY = """
            {"personnummer":"199001012385","family":"Testsson","given":["Tolvan","Syntet"],
             "gender":"FEMALE","birthDate":"1990-01-01"}
            """;
    private static final CreatePatientRequest REQUEST = new CreatePatientRequest(PERSONNUMMER, "Testsson",
            List.of("Tolvan", "Syntet"), CreatePatientRequest.Gender.FEMALE, LocalDate.of(1990, 1, 1));

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthenticationService authenticationService;

    @MockitoBean
    private PatientService patientService;

    private static UserDto user(Role role) {
        return new UserDto(42L, "user@example.test", role);
    }

    private static RequestPostProcessor as(Role role) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(user(role), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void doctorsAndStaffRegisterPatients(Role role) throws Exception {
        when(patientService.registerPatient(user(role), REQUEST)).thenReturn(new PatientDto("local-1",
                List.of(new PatientIdentifier("http://electronichealth.se/identifier/personnummer", PERSONNUMMER)),
                List.of(new PatientName("official", null, "Testsson", List.of("Tolvan", "Syntet"))), "female",
                "1990-01-01"));

        mvc.perform(post("/api/patients").with(csrf()).with(as(role))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/patients/local-1"))
                .andExpect(content().json("""
                        {"id":"local-1",
                         "identifiers":[{"system":"http://electronichealth.se/identifier/personnummer","value":"199001012385"}],
                         "names":[{"use":"official","text":null,"family":"Testsson","given":["Tolvan","Syntet"]}],
                         "gender":"female","birthDate":"1990-01-01"}
                        """));
        verify(patientService).registerPatient(user(role), REQUEST);
    }

    @Test
    void anonymousRequestsAreUnauthorized() throws Exception {
        mvc.perform(post("/api/patients").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verify(patientService, never()).registerPatient(any(), any());
    }

    @Test
    void patientAccountsAreForbidden() throws Exception {
        mvc.perform(post("/api/patients").with(csrf()).with(as(Role.PATIENT))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verify(patientService, never()).registerPatient(any(), any());
    }

    @Test
    void requestsWithoutCsrfTokenAreForbidden() throws Exception {
        mvc.perform(post("/api/patients").with(as(Role.STAFF)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verify(patientService, never()).registerPatient(any(), any());
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void otherMethodsOnPatientsRemainDenied(Role role) throws Exception {
        // No patient list or search (US-04.02 adds GET of one Patient by ID only).
        mvc.perform(get("/api/patients").with(as(role))).andExpect(status().isForbidden());
        mvc.perform(get("/api/patients/local-1/history").with(as(role))).andExpect(status().isForbidden());
        mvc.perform(post("/api/patients/local-1").with(csrf()).with(as(role))
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        // PATCH of one Patient by ID is US-04.04 (DOCTOR/STAFF, tested below); any other PATCH stays denied.
        for (var request : List.of(put("/api/patients/local-1"), patch("/api/patients"),
                patch("/api/patients/local-1/name"), delete("/api/patients/local-1"))) {
            mvc.perform(request.with(csrf()).with(as(role))).andExpect(status().isForbidden());
        }
        verify(patientService, never()).getPatient(any(), any());
    }

    // --- GET /api/patients/{id} (US-04.02). Synthetic Patient only. ---

    private static final PatientDto PROFILE = new PatientDto("syn-profile-41",
            List.of(new PatientIdentifier("http://electronichealth.se/identifier/personnummer", PERSONNUMMER)),
            List.of(new PatientName("official", null, "Testsson", List.of("Tolvan"))), "female", "1990-01-01");

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void doctorsAndStaffViewAPatientProfile(Role role) throws Exception {
        when(patientService.getPatient(user(role), "syn-profile-41"))
                .thenReturn(com.medinexus.medicore.patient.dto.PatientProfileDto.of(PROFILE, null));
        mvc.perform(get("/api/patients/syn-profile-41").with(as(role)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(content().json("""
                        {"id":"syn-profile-41",
                         "identifiers":[{"system":"http://electronichealth.se/identifier/personnummer","value":"199001012385"}],
                         "names":[{"use":"official","text":null,"family":"Testsson","given":["Tolvan"]}],
                         "gender":"female","birthDate":"1990-01-01"}
                        """));
        verify(patientService).getPatient(user(role), "syn-profile-41");
    }

    @Test
    void anonymousProfileRequestsAreUnauthorized() throws Exception {
        mvc.perform(get("/api/patients/syn-profile-41")).andExpect(status().isUnauthorized());
        verify(patientService, never()).getPatient(any(), any());
    }

    @Test
    void patientAccountsCannotViewProfilesInThisStory() throws Exception {
        mvc.perform(get("/api/patients/syn-profile-41").with(as(Role.PATIENT))).andExpect(status().isForbidden());
        verify(patientService, never()).getPatient(any(), any());
    }

    static Stream<Arguments> profileFailures() {
        return Stream.of(
                Arguments.of(new IllegalArgumentException("A valid logical FHIR Patient ID is required"), 400),
                Arguments.of(new AccessDeniedException("Access denied"), 403),
                Arguments.of(new PatientNotFoundException(), 404),
                Arguments.of(fhir(FhirServerException.Type.CLIENT_ERROR, 400), 422),
                Arguments.of(fhir(FhirServerException.Type.TIMEOUT, null), 503),
                Arguments.of(fhir(FhirServerException.Type.UNAVAILABLE, null), 503),
                Arguments.of(fhir(FhirServerException.Type.INVALID_RESPONSE, null), 502),
                Arguments.of(fhir(FhirServerException.Type.SERVER_ERROR, 500), 502));
    }

    @ParameterizedTest
    @MethodSource("profileFailures")
    void profileFailuresAreMappedToStatusesWithoutBody(RuntimeException failure, int expectedStatus) throws Exception {
        when(patientService.getPatient(any(), any())).thenThrow(failure);
        mvc.perform(get("/api/patients/syn-profile-41").with(as(Role.DOCTOR)))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().string(""));
    }

    @Test
    void anInvalidIdIsPassedToTheServiceWhichRejectsIt() throws Exception {
        when(patientService.getPatient(user(Role.STAFF), "bad_id"))
                .thenThrow(new IllegalArgumentException("A valid logical FHIR Patient ID is required"));
        mvc.perform(get("/api/patients/bad_id").with(as(Role.STAFF))).andExpect(status().isBadRequest())
                .andExpect(content().string(""));
    }

    @Test
    void registrationStillWorksNextToProfiles() throws Exception {
        when(patientService.registerPatient(user(Role.DOCTOR), REQUEST)).thenReturn(PROFILE);
        mvc.perform(post("/api/patients").with(csrf()).with(as(Role.DOCTOR))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/patients/syn-profile-41"));
    }

    @Test
    void aRoleRejectedByTheServiceIsForbidden() throws Exception {
        // For example a session that still says DOCTOR for an account that is no longer DOCTOR.
        when(patientService.registerPatient(any(), any())).thenThrow(new AccessDeniedException("Access denied"));
        mvc.perform(post("/api/patients").with(csrf()).with(as(Role.DOCTOR))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(content().string(""));
    }

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(new IllegalArgumentException("A valid Swedish personnummer is required"), 400),
                Arguments.of(new DuplicatePatientException(), 409),
                Arguments.of(fhir(FhirServerException.Type.CLIENT_ERROR, 422), 422),
                Arguments.of(fhir(FhirServerException.Type.CLIENT_ERROR, 400), 422),
                Arguments.of(fhir(FhirServerException.Type.TIMEOUT, null), 503),
                Arguments.of(fhir(FhirServerException.Type.UNAVAILABLE, null), 503),
                Arguments.of(fhir(FhirServerException.Type.UNAVAILABLE, 503), 503),
                Arguments.of(fhir(FhirServerException.Type.INVALID_RESPONSE, null), 502),
                Arguments.of(fhir(FhirServerException.Type.SERVER_ERROR, 500), 502));
    }

    private static FhirServerException fhir(FhirServerException.Type type, Integer status) {
        return new FhirServerException("patient-create", type, status, List.of(), "SyntheticCause");
    }

    @ParameterizedTest
    @MethodSource("failures")
    void failuresAreMappedToStatusesWithoutBody(RuntimeException failure, int expectedStatus) throws Exception {
        when(patientService.registerPatient(any(), any())).thenThrow(failure);
        mvc.perform(post("/api/patients").with(csrf()).with(as(Role.STAFF))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().string(""));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "not json 199001012385",
            "{\"personnummer\":\"199001012385\",\"family\":\"Testsson\",\"given\":[\"Tolvan\"],\"gender\":\"FEMALE\",\"birthDate\":\"1990-13-45\"}",
            "{\"personnummer\":\"199001012385\",\"family\":\"Testsson\",\"given\":[\"Tolvan\"],\"gender\":\"woman-199001012385\",\"birthDate\":\"1990-01-01\"}",
            "{\"personnummer\":{\"value\":\"199001012385\"},\"family\":\"Testsson\",\"given\":[\"Tolvan\"]}",
            "[\"199001012385\"]"})
    void unreadableJsonIsABadRequestThatNeverEchoesOrLogsTheInput(String body, CapturedOutput output) throws Exception {
        mvc.perform(post("/api/patients").with(csrf()).with(as(Role.DOCTOR))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(""));
        verify(patientService, never()).registerPatient(any(), any());
        Assertions.assertThat(output.getAll().contains(PERSONNUMMER)).as("log contains the personnummer").isFalse();
        Assertions.assertThat(output.getAll().contains("1990-13-45")).as("log contains the input").isFalse();
    }

    @Test
    void validationFailuresAreNotLoggedWithTheirInput(CapturedOutput output) throws Exception {
        when(patientService.registerPatient(eq(user(Role.DOCTOR)), any()))
                .thenThrow(new IllegalArgumentException("The birth date must match the personnummer"));
        mvc.perform(post("/api/patients").with(csrf()).with(as(Role.DOCTOR))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        Assertions.assertThat(output.getAll().contains(PERSONNUMMER)).as("log contains the personnummer").isFalse();
        Assertions.assertThat(output.getAll().contains("Testsson")).as("log contains the name").isFalse();
    }

    // --- Registry failures on registration ---

    @Test
    void aRegistryFailureAfterCreationIsReportedSoThatTheClientDoesNotRegisterAgain() throws Exception {
        when(patientService.registerPatient(any(), any())).thenThrow(
                new com.medinexus.medicore.patient.service.PatientRegistryException(new RuntimeException("db")));
        mvc.perform(post("/api/patients").with(csrf()).with(as(Role.DOCTOR))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("{\"code\":\"REGISTRY_FAILED\"}",
                        org.springframework.test.json.JsonCompareMode.STRICT));
    }

    // --- POST /api/patients/search ---

    private static final String SEARCH = "{\"query\":\"Tolvan Testsson\"}";

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void doctorsAndStaffSearchWithTheQueryInTheBody(Role role) throws Exception {
        when(patientService.searchPatients(user(role), "Tolvan Testsson")).thenReturn(List.of(PROFILE, PROFILE));
        mvc.perform(post("/api/patients/search").with(csrf()).with(as(role))
                        .contentType(MediaType.APPLICATION_JSON).content(SEARCH))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(content().json("[{\"id\":\"syn-profile-41\",\"gender\":\"female\"},{\"id\":\"syn-profile-41\"}]"));
        verify(patientService).searchPatients(user(role), "Tolvan Testsson");
    }

    @Test
    void searchRequiresAuthenticationTheClinicalRoleAndCsrf() throws Exception {
        mvc.perform(post("/api/patients/search").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(SEARCH))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/patients/search").with(csrf()).with(as(Role.PATIENT))
                .contentType(MediaType.APPLICATION_JSON).content(SEARCH)).andExpect(status().isForbidden());
        mvc.perform(post("/api/patients/search").with(as(Role.DOCTOR))
                .contentType(MediaType.APPLICATION_JSON).content(SEARCH)).andExpect(status().isForbidden());
        verify(patientService, never()).searchPatients(any(), any());
    }

    static Stream<Arguments> searchFailures() {
        return Stream.of(
                Arguments.of(new IllegalArgumentException("Search for at least two letters"), 400, ""),
                Arguments.of(new AccessDeniedException("Access denied"), 403, ""),
                Arguments.of(new com.medinexus.medicore.fhir.client.TooManyPatientsException(), 422,
                        "{\"code\":\"TOO_MANY_RESULTS\"}"),
                Arguments.of(fhir(FhirServerException.Type.UNAVAILABLE, null), 503, ""),
                Arguments.of(fhir(FhirServerException.Type.INVALID_RESPONSE, null), 502, ""));
    }

    @ParameterizedTest
    @MethodSource("searchFailures")
    void searchFailuresAreMappedWithoutEchoingTheQuery(RuntimeException failure, int expectedStatus, String body)
            throws Exception {
        when(patientService.searchPatients(any(), any())).thenThrow(failure);
        mvc.perform(post("/api/patients/search").with(csrf()).with(as(Role.STAFF))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"" + PERSONNUMMER + "\"}"))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().string(body.isEmpty() ? org.hamcrest.Matchers.emptyString()
                        : org.hamcrest.Matchers.equalTo(body)));
    }

    @Test
    void unreadableSearchBodiesAreBadRequests() throws Exception {
        for (String body : List.of("", "not json", "[\"Tolvan\"]")) {
            mvc.perform(post("/api/patients/search").with(csrf()).with(as(Role.DOCTOR))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verify(patientService, never()).searchPatients(any(), any());
    }

    // --- US-04.03: GET /api/patients/me ---

    @Test
    void aPatientReadsTheirOwnRecordIncludingTheEmail() throws Exception {
        when(patientService.getOwnRecord(user(Role.PATIENT)))
                .thenReturn(com.medinexus.medicore.patient.dto.PatientProfileDto.of(PROFILE, "user@example.test"));
        mvc.perform(get("/api/patients/me").with(as(Role.PATIENT)))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"email\":\"user@example.test\",\"gender\":\"female\"}"));
        verify(patientService).getOwnRecord(user(Role.PATIENT));
    }

    @Test
    void queryParametersCannotSelectAnotherRecord() throws Exception {
        when(patientService.getOwnRecord(user(Role.PATIENT)))
                .thenReturn(com.medinexus.medicore.patient.dto.PatientProfileDto.of(PROFILE, "user@example.test"));
        mvc.perform(get("/api/patients/me").param("id", "syn-other-42").with(as(Role.PATIENT)))
                .andExpect(status().isOk());
        verify(patientService).getOwnRecord(user(Role.PATIENT));
        verify(patientService, never()).getPatient(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void clinicalRolesHaveNoOwnRecord(Role role) throws Exception {
        mvc.perform(get("/api/patients/me").with(as(role))).andExpect(status().isForbidden());
        verify(patientService, never()).getOwnRecord(any());
        verify(patientService, never()).getPatient(any(), any());
    }

    @Test
    void anonymousRequestsForTheOwnRecordAreUnauthorized() throws Exception {
        mvc.perform(get("/api/patients/me")).andExpect(status().isUnauthorized());
        verify(patientService, never()).getOwnRecord(any());
    }

    @Test
    void patientsStillCannotReadPatientsById() throws Exception {
        mvc.perform(get("/api/patients/syn-profile-41").with(as(Role.PATIENT))).andExpect(status().isForbidden());
        verify(patientService, never()).getPatient(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RECORD_NOT_LINKED", "RECORD_NOT_FOUND"})
    void anUnavailableOwnRecordIsNotFoundWithACode(String code) throws Exception {
        when(patientService.getOwnRecord(any()))
                .thenThrow(new com.medinexus.medicore.patient.service.OwnPatientRecordException(code));
        mvc.perform(get("/api/patients/me").with(as(Role.PATIENT)))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"code\":\"" + code + "\"}", true));
    }

    @Test
    void ownRecordFhirFailuresAreMapped() throws Exception {
        when(patientService.getOwnRecord(any())).thenThrow(new FhirServerException("patient-read",
                FhirServerException.Type.TIMEOUT, null, List.of(), "SocketTimeoutException"));
        mvc.perform(get("/api/patients/me").with(as(Role.PATIENT))).andExpect(status().isServiceUnavailable());
    }

    // --- PATCH /api/patients/{id} (US-04.04). Fictional data only. ---

    private static final String UPDATE_BODY = "{\"gender\":\"FEMALE\"}";
    private static final com.medinexus.medicore.patient.dto.UpdatePatientRequest UPDATE =
            new com.medinexus.medicore.patient.dto.UpdatePatientRequest(CreatePatientRequest.Gender.FEMALE);

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void doctorsAndStaffUpdateTheGenderWithTheVersionTheyRead(Role role) throws Exception {
        when(patientService.updatePatient(user(role), "syn-profile-41", "2", UPDATE))
                .thenReturn(com.medinexus.medicore.patient.dto.PatientProfileDto.of(PROFILE, null, "3"));
        for (String ifMatch : List.of("W/\"2\"", "\"2\"", "2")) {
            mvc.perform(patch("/api/patients/syn-profile-41").with(csrf()).with(as(role)).header("If-Match", ifMatch)
                            .contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
                    .andExpect(status().isOk())
                    .andExpect(content().json("{\"id\":\"syn-profile-41\",\"version\":\"3\"}"));
        }
        verify(patientService, org.mockito.Mockito.times(3)).updatePatient(user(role), "syn-profile-41", "2", UPDATE);
    }

    @Test
    void patientsAnonymousUsersAndRequestsWithoutCsrfCannotUpdate() throws Exception {
        mvc.perform(patch("/api/patients/syn-profile-41").with(csrf()).with(as(Role.PATIENT)).header("If-Match", "2")
                .contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY)).andExpect(status().isForbidden());
        mvc.perform(patch("/api/patients/me").with(csrf()).with(as(Role.PATIENT)).header("If-Match", "2")
                .contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY)).andExpect(status().isForbidden());
        mvc.perform(patch("/api/patients/syn-profile-41").with(csrf()).header("If-Match", "2")
                .contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY)).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/patients/syn-profile-41").with(as(Role.DOCTOR)).header("If-Match", "2")
                .contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY)).andExpect(status().isForbidden());
        verify(patientService, never()).updatePatient(any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"givenName", "familyName", "personnummer", "birthDate", "id", "fhirPatientId",
            "identifiers", "email", "phoneNumber", "userId", "role"})
    void bodiesWithImmutableOrUnknownFieldsAreRejected(String field) throws Exception {
        String body = UPDATE_BODY.replace("}", ",\"" + field + "\":\"200512241971\"}");
        mvc.perform(patch("/api/patients/syn-profile-41").with(csrf()).with(as(Role.STAFF)).header("If-Match", "2")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(""));
        verify(patientService, never()).updatePatient(any(), any(), any(), any());
    }

    @Test
    void malformedBodiesAndGendersAreRejected() throws Exception {
        for (String body : List.of("{\"gender\":\"female\"}", "{\"gender\":42}", "[]", "{", "")) {
            mvc.perform(patch("/api/patients/syn-profile-41").with(csrf()).with(as(Role.STAFF)).header("If-Match", "2")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verify(patientService, never()).updatePatient(any(), any(), any(), any());
    }

    @Test
    void updateErrorsAreMappedWithCodes() throws Exception {
        when(patientService.updatePatient(any(), any(), any(), any()))
                .thenThrow(new com.medinexus.medicore.patient.service.PatientVersionRequiredException());
        mvc.perform(patch("/api/patients/syn-profile-41").with(csrf()).with(as(Role.DOCTOR))
                        .contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
                .andExpect(status().isPreconditionRequired())
                .andExpect(content().json("{\"code\":\"VERSION_REQUIRED\"}", true));
        verify(patientService).updatePatient(user(Role.DOCTOR), "syn-profile-41", null, UPDATE);

        org.mockito.Mockito.reset(patientService);
        when(patientService.updatePatient(any(), any(), any(), any()))
                .thenThrow(new com.medinexus.medicore.patient.service.PatientVersionConflictException());
        mvc.perform(patch("/api/patients/syn-profile-41").with(csrf()).with(as(Role.DOCTOR)).header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
                .andExpect(status().isConflict()).andExpect(content().json("{\"code\":\"VERSION_CONFLICT\"}", true));

        for (RuntimeException failure : List.<RuntimeException>of(new PatientNotFoundException(),
                new IllegalArgumentException("x"), new AccessDeniedException("x"),
                new FhirServerException("patient-update", FhirServerException.Type.TIMEOUT, null, List.of(), null),
                new FhirServerException("patient-update", FhirServerException.Type.UNAVAILABLE, null, List.of(), null),
                new FhirServerException("patient-update", FhirServerException.Type.INVALID_RESPONSE, null, List.of(),
                        null))) {
            org.mockito.Mockito.reset(patientService);
            when(patientService.updatePatient(any(), any(), any(), any())).thenThrow(failure);
            int expected = failure instanceof PatientNotFoundException ? 404
                    : failure instanceof IllegalArgumentException ? 400
                    : failure instanceof AccessDeniedException ? 403
                    : ((FhirServerException) failure).type() == FhirServerException.Type.INVALID_RESPONSE ? 502 : 503;
            mvc.perform(patch("/api/patients/syn-profile-41").with(csrf()).with(as(Role.DOCTOR)).header("If-Match", "2")
                    .contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
                    .andExpect(status().is(expected));
        }
    }

    @Test
    void theProfileIncludesTheVersionForEditing() throws Exception {
        when(patientService.getPatient(user(Role.STAFF), "syn-profile-41"))
                .thenReturn(com.medinexus.medicore.patient.dto.PatientProfileDto.of(PROFILE, null, "2"));
        mvc.perform(get("/api/patients/syn-profile-41").with(as(Role.STAFF)))
                .andExpect(status().isOk()).andExpect(content().json("{\"version\":\"2\"}"));
    }
}
