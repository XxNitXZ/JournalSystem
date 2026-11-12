package com.medinexus.medicore.medicalrecord.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
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
import com.medinexus.medicore.medicalrecord.dto.EncounterSummaryDto;
import com.medinexus.medicore.medicalrecord.dto.NewEncounterRequest;
import com.medinexus.medicore.medicalrecord.service.EncounterRequestException;
import com.medinexus.medicore.medicalrecord.service.EncounterService;
import com.medinexus.medicore.patient.service.PatientNotFoundException;
import com.medinexus.medicore.practitioner.controller.PractitionerController;
import com.medinexus.medicore.practitioner.dto.PractitionerOption;
import com.medinexus.medicore.practitioner.service.PractitionerDirectory;

/** US-05.05: Encounter and Practitioner endpoints, route security, CSRF and status mapping. Fictional data. */
@WebMvcTest({EncounterController.class, PractitionerController.class})
@Import(SecurityConfig.class)
class EncounterControllerTest {

    private static final String BODY = """
            {"startTime":"2026-11-10T14:30:00+01:00","practitionerId":"syn-practitioner-1","type":"CONSULTATION"}
            """;
    private static final NewEncounterRequest REQUEST = new NewEncounterRequest("2026-11-10T14:30:00+01:00",
            "syn-practitioner-1", "CONSULTATION", null, null, null);
    private static final EncounterSummaryDto VISIT = new EncounterSummaryDto("enc-1", "2026-11-10T14:30:00+01:00", null,
            "finished", "Consultation", List.of("Dr Ali Khan"), null, null);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthenticationService authenticationService;

    @MockitoBean
    private EncounterService service;

    @MockitoBean
    private PractitionerDirectory directory;

    private static UserDto user(Role role) {
        return new UserDto(42L, "user@example.test", role);
    }

    private static RequestPostProcessor as(Role role) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(user(role), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void doctorsAndStaffCreateAndListVisits(Role role) throws Exception {
        when(service.create(user(role), "syn-patient-1", REQUEST)).thenReturn(VISIT);
        when(service.listForPatient(user(role), "syn-patient-1")).thenReturn(List.of(VISIT));

        mvc.perform(post("/api/patients/syn-patient-1/encounters").with(csrf()).with(as(role))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/patients/syn-patient-1/encounters"))
                .andExpect(content().json("""
                        {"id":"enc-1","start":"2026-11-10T14:30:00+01:00","end":null,"status":"finished",
                         "type":"Consultation","practitioners":["Dr Ali Khan"],"organization":null,"location":null}
                        """, JsonCompareMode.STRICT));
        mvc.perform(get("/api/patients/syn-patient-1/encounters").with(as(role)))
                .andExpect(status().isOk())
                .andExpect(content().json("[{\"id\":\"enc-1\",\"type\":\"Consultation\"}]"));
        verify(service).create(user(role), "syn-patient-1", REQUEST);
    }

    @Test
    void patientsReadOnlyTheirOwnVisitsAndCannotCreate() throws Exception {
        when(service.listOwn(user(Role.PATIENT))).thenReturn(List.of(VISIT));
        mvc.perform(get("/api/patients/me/encounters").with(as(Role.PATIENT)))
                .andExpect(status().isOk()).andExpect(content().json("[{\"id\":\"enc-1\"}]"));
        // Query parameters cannot select another record.
        mvc.perform(get("/api/patients/me/encounters").param("patientId", "syn-other").with(as(Role.PATIENT)))
                .andExpect(status().isOk());
        verify(service, org.mockito.Mockito.times(2)).listOwn(user(Role.PATIENT));

        mvc.perform(get("/api/patients/syn-patient-1/encounters").with(as(Role.PATIENT))).andExpect(status().isForbidden());
        mvc.perform(post("/api/patients/syn-patient-1/encounters").with(csrf()).with(as(Role.PATIENT))
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(post("/api/patients/me/encounters").with(csrf()).with(as(Role.PATIENT))
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(get("/api/practitioners").with(as(Role.PATIENT))).andExpect(status().isForbidden());
        verify(service, never()).create(any(), any(), any());
        verify(service, never()).listForPatient(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void cliniciansHaveNoOwnVisitView(Role role) throws Exception {
        mvc.perform(get("/api/patients/me/encounters").with(as(role))).andExpect(status().isForbidden());
        verify(service, never()).listOwn(any());
    }

    @Test
    void anonymousRequestsAndMissingCsrfTokensAreRejected() throws Exception {
        mvc.perform(get("/api/patients/syn-patient-1/encounters")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/patients/me/encounters")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/practitioners")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/patients/syn-patient-1/encounters").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/patients/syn-patient-1/encounters").with(as(Role.DOCTOR))
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        verifyNoInteractions(service, directory);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void otherMethodsOnEncountersRemainDenied(Role role) throws Exception {
        for (var request : List.of(put("/api/patients/syn-patient-1/encounters"),
                patch("/api/patients/syn-patient-1/encounters"), delete("/api/patients/syn-patient-1/encounters"),
                get("/api/patients/syn-patient-1/encounters/enc-1"), post("/api/practitioners"))) {
            mvc.perform(request.with(csrf()).with(as(role))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service, directory);
    }

    @ParameterizedTest
    @ValueSource(strings = {"patientId", "subject", "id", "participants", "period"})
    void bodiesWithOtherFieldsAreRejected(String field) throws Exception {
        mvc.perform(post("/api/patients/syn-patient-1/encounters").with(csrf()).with(as(Role.DOCTOR))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("}", ",\"" + field + "\":\"x\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":\"INVALID_REQUEST\"}", JsonCompareMode.STRICT));
        for (String body : List.of("{\"startTime\":1}", "[]", "{")) {
            mvc.perform(post("/api/patients/syn-patient-1/encounters").with(csrf()).with(as(Role.DOCTOR))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verify(service, never()).create(any(), any(), any());
    }

    @Test
    void serviceErrorsAreMappedWithCodes() throws Exception {
        record Case(RuntimeException failure, int status, String code) {
        }
        for (Case c : List.of(
                new Case(new EncounterRequestException("START_TIME_INVALID"), 400, "START_TIME_INVALID"),
                new Case(new EncounterRequestException("PRACTITIONER_NOT_AVAILABLE"), 400, "PRACTITIONER_NOT_AVAILABLE"),
                new Case(new EncounterRequestException("PRACTITIONER_NOT_FOUND"), 422, "PRACTITIONER_NOT_FOUND"),
                new Case(new PatientNotFoundException(), 404, null),
                new Case(new IllegalArgumentException("x"), 400, null),
                new Case(new AccessDeniedException("x"), 403, null),
                new Case(new FhirServerException("encounter-create", FhirServerException.Type.TIMEOUT, null, List.of(), null), 503, null),
                new Case(new FhirServerException("encounter-create", FhirServerException.Type.UNAVAILABLE, null, List.of(), null), 503, null),
                new Case(new FhirServerException("encounter-create", FhirServerException.Type.INVALID_RESPONSE, null, List.of(), null), 502, null),
                new Case(new FhirServerException("encounter-create", FhirServerException.Type.CLIENT_ERROR, 422, List.of(), null), 422, null))) {
            org.mockito.Mockito.reset(service);
            when(service.create(any(), any(), any())).thenThrow(c.failure());
            var result = mvc.perform(post("/api/patients/syn-patient-1/encounters").with(csrf()).with(as(Role.STAFF))
                    .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().is(c.status()));
            if (c.code() != null) result.andExpect(content().json("{\"code\":\"" + c.code() + "\"}", JsonCompareMode.STRICT));
            else result.andExpect(content().string(""));
        }
        org.mockito.Mockito.reset(service);
        when(service.listOwn(any())).thenThrow(new EncounterRequestException("RECORD_NOT_LINKED"));
        mvc.perform(get("/api/patients/me/encounters").with(as(Role.PATIENT)))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"code\":\"RECORD_NOT_LINKED\"}", JsonCompareMode.STRICT));
    }

    @Test
    void cliniciansListSelectablePractitioners() throws Exception {
        when(directory.available(user(Role.DOCTOR))).thenReturn(List.of(new PractitionerOption("p-1", "Dr Ali Khan", true)));
        mvc.perform(get("/api/practitioners").with(as(Role.DOCTOR)))
                .andExpect(status().isOk())
                .andExpect(content().json("[{\"id\":\"p-1\",\"name\":\"Dr Ali Khan\",\"own\":true}]", JsonCompareMode.STRICT));
        when(directory.available(user(Role.STAFF))).thenThrow(new FhirServerException("practitioner-read",
                FhirServerException.Type.UNAVAILABLE, null, List.of(), null));
        mvc.perform(get("/api/practitioners").with(as(Role.STAFF))).andExpect(status().isServiceUnavailable());
    }
}
