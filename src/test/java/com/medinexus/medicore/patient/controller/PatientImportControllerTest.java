package com.medinexus.medicore.patient.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.service.AuthenticationService;
import com.medinexus.medicore.config.SecurityConfig;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.patient.dto.PatientCandidateDto;
import com.medinexus.medicore.patient.dto.PatientDiscoveryResponse;
import com.medinexus.medicore.patient.service.PatientImportException;
import com.medinexus.medicore.patient.service.PatientImportService;
import com.medinexus.medicore.patient.service.PatientNotFoundException;

/** Import endpoints: route security, CSRF, strict bodies and status mapping. Fictional data only. */
@WebMvcTest(PatientImportController.class)
@Import(SecurityConfig.class)
class PatientImportControllerTest {

    private static final PatientCandidateDto CANDIDATE = new PatientCandidateDto("ext-1", "Ali Khan", "200412201971",
            "male", "2004-12-20", false, true, true);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthenticationService authenticationService;

    @MockitoBean
    private PatientImportService service;

    private static UserDto user(Role role) {
        return new UserDto(42L, "user@example.test", role);
    }

    private static RequestPostProcessor as(Role role) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(user(role), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void doctorsAndStaffSearchAndImport(Role role) throws Exception {
        when(service.discover(user(role), "NAME", "Ali Khan"))
                .thenReturn(new PatientDiscoveryResponse(List.of(CANDIDATE), false));
        when(service.importPatient(user(role), "ext-1")).thenReturn(CANDIDATE);

        mvc.perform(post("/api/patients/import/search").with(csrf()).with(as(role))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"NAME\",\"value\":\"Ali Khan\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"patients":[{"id":"ext-1","name":"Ali Khan","personnummer":"200412201971","gender":"male",
                          "birthDate":"2004-12-20","registered":false,"importable":true,"journalMatchable":true,
                          "personnummerStatus":"VALID"}],
                         "truncated":false}
                        """, JsonCompareMode.STRICT));
        mvc.perform(post("/api/patients/import").with(csrf()).with(as(role))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fhirPatientId\":\"ext-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/patients/ext-1"))
                .andExpect(content().json("{\"id\":\"ext-1\",\"name\":\"Ali Khan\"}"));
        verify(service).importPatient(user(role), "ext-1");
    }

    @Test
    void patientsAnonymousUsersAndRequestsWithoutCsrfAreRejected() throws Exception {
        for (String path : List.of("/api/patients/import/search", "/api/patients/import")) {
            mvc.perform(post(path).with(csrf()).with(as(Role.PATIENT)).contentType(MediaType.APPLICATION_JSON)
                    .content("{}")).andExpect(status().isForbidden());
            mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post(path).with(as(Role.DOCTOR)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
        // GET /api/patients/import is the existing profile route (an ID "import"); the search path has no GET.
        mvc.perform(get("/api/patients/import/search").with(as(Role.DOCTOR))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void bodiesAreReadStrictly() throws Exception {
        for (String body : List.of("{\"fhirPatientId\":\"ext-1\",\"name\":\"x\"}", "{\"fhirPatientId\":1}",
                "{\"fhirPatientId\":\"ext-1\",\"userId\":7}")) {
            mvc.perform(post("/api/patients/import").with(csrf()).with(as(Role.STAFF))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().json("{\"code\":\"INVALID_REQUEST\"}", JsonCompareMode.STRICT));
        }
        mvc.perform(post("/api/patients/import/search").with(csrf()).with(as(Role.STAFF))
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"NAME\",\"value\":\"Ali\",\"page\":2}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/patients/import").with(csrf()).with(as(Role.STAFF))
                .contentType(MediaType.APPLICATION_JSON).content("{")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void serviceErrorsAreMappedWithCodes() throws Exception {
        record Case(RuntimeException failure, int status, String code) {
        }
        for (Case c : List.of(new Case(new PatientImportException("ALREADY_REGISTERED"), 409, "ALREADY_REGISTERED"),
                new Case(new PatientImportException("PATIENT_NOT_IMPORTABLE"), 422, "PATIENT_NOT_IMPORTABLE"),
                new Case(new PatientImportException("SEARCH_INVALID"), 400, "SEARCH_INVALID"),
                new Case(new PatientNotFoundException(), 404, null),
                new Case(new FhirServerException("patient-read", FhirServerException.Type.TIMEOUT, null, List.of(), null), 503, null),
                new Case(new FhirServerException("patient-read", FhirServerException.Type.INVALID_RESPONSE, null, List.of(), null), 502, null))) {
            org.mockito.Mockito.reset(service);
            when(service.importPatient(any(), any())).thenThrow(c.failure());
            var result = mvc.perform(post("/api/patients/import").with(csrf()).with(as(Role.DOCTOR))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"fhirPatientId\":\"ext-1\"}"))
                    .andExpect(status().is(c.status()));
            if (c.code() != null) result.andExpect(content().json("{\"code\":\"" + c.code() + "\"}", JsonCompareMode.STRICT));
        }
        org.mockito.Mockito.reset(service);
        when(service.discover(any(), any(), any())).thenThrow(new FhirServerException("patient-discovery",
                FhirServerException.Type.UNAVAILABLE, null, List.of(), null));
        mvc.perform(post("/api/patients/import/search").with(csrf()).with(as(Role.DOCTOR))
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"NAME\",\"value\":\"Ali\"}"))
                .andExpect(status().isServiceUnavailable());
    }
}
