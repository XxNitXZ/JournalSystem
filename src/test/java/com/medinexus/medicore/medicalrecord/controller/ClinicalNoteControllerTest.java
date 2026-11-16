package com.medinexus.medicore.medicalrecord.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.medinexus.medicore.medicalrecord.dto.ClinicalNoteDto;
import com.medinexus.medicore.medicalrecord.dto.CreateClinicalNoteRequest;
import com.medinexus.medicore.medicalrecord.service.ClinicalNoteException;
import com.medinexus.medicore.medicalrecord.service.ClinicalNoteService;
import com.medinexus.medicore.patient.service.PatientNotFoundException;

/** US-05.01: clinical note endpoints, route security, CSRF, strict bodies and status mapping. Fictional data. */
@WebMvcTest(ClinicalNoteController.class)
@Import(SecurityConfig.class)
class ClinicalNoteControllerTest {

    private static final String BODY = "{\"encounterId\":\"syn-enc-1\",\"content\":\"Mår bättre.\\nÅterbesök.\"}";
    private static final CreateClinicalNoteRequest REQUEST = new CreateClinicalNoteRequest("syn-enc-1",
            "Mår bättre.\nÅterbesök.");
    private static final ClinicalNoteDto NOTE = new ClinicalNoteDto("note-1", "syn-patient-1",
            new ClinicalNoteDto.EncounterContext("syn-enc-1", "2026-11-10T14:30:00+01:00", "Consultation"),
            "Dr Ali Khan", "2026-11-12T09:30:00+00:00", "current", "Clinical note", "Mår bättre.\nÅterbesök.", true);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthenticationService authenticationService;

    @MockitoBean
    private ClinicalNoteService service;

    private static UserDto user(Role role) {
        return new UserDto(42L, "user@example.test", role);
    }

    private static RequestPostProcessor as(Role role) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(user(role), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void doctorsAndStaffCreateAndReadNotes(Role role) throws Exception {
        when(service.create(user(role), "syn-patient-1", REQUEST)).thenReturn(NOTE);
        when(service.listForPatient(user(role), "syn-patient-1")).thenReturn(List.of(NOTE));

        mvc.perform(post("/api/patients/syn-patient-1/notes").with(csrf()).with(as(role))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/patients/syn-patient-1/notes"))
                .andExpect(content().json("""
                        {"id":"note-1","patientId":"syn-patient-1",
                         "encounter":{"id":"syn-enc-1","start":"2026-11-10T14:30:00+01:00","type":"Consultation"},
                         "author":"Dr Ali Khan","createdAt":"2026-11-12T09:30:00+00:00","status":"current",
                         "type":"Clinical note","content":"Mår bättre.\\nÅterbesök.","contentAvailable":true}
                        """, JsonCompareMode.STRICT));
        mvc.perform(get("/api/patients/syn-patient-1/notes").with(as(role)))
                .andExpect(status().isOk())
                .andExpect(content().json("[{\"id\":\"note-1\",\"author\":\"Dr Ali Khan\"}]"));
        verify(service).create(user(role), "syn-patient-1", REQUEST);
    }

    @Test
    void patientsReadOnlyTheirOwnNotesAndCannotCreate() throws Exception {
        when(service.listOwn(user(Role.PATIENT))).thenReturn(List.of(NOTE));
        mvc.perform(get("/api/patients/me/notes").with(as(Role.PATIENT)))
                .andExpect(status().isOk()).andExpect(content().json("[{\"id\":\"note-1\"}]"));
        mvc.perform(get("/api/patients/me/notes").param("patientId", "syn-other").with(as(Role.PATIENT)))
                .andExpect(status().isOk());
        verify(service, times(2)).listOwn(user(Role.PATIENT));

        mvc.perform(get("/api/patients/syn-patient-1/notes").with(as(Role.PATIENT))).andExpect(status().isForbidden());
        mvc.perform(post("/api/patients/syn-patient-1/notes").with(csrf()).with(as(Role.PATIENT))
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(post("/api/patients/me/notes").with(csrf()).with(as(Role.PATIENT))
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        // Clinicians have no "own" notes.
        mvc.perform(get("/api/patients/me/notes").with(as(Role.DOCTOR))).andExpect(status().isForbidden());
    }

    @Test
    void anonymousRequestsMissingCsrfAndOtherMethodsAreRejected() throws Exception {
        mvc.perform(get("/api/patients/syn-patient-1/notes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/patients/me/notes")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/patients/syn-patient-1/notes").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/patients/syn-patient-1/notes").with(as(Role.DOCTOR))
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(put("/api/patients/syn-patient-1/notes").with(csrf()).with(as(Role.DOCTOR))
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/patients/syn-patient-1/notes").with(csrf()).with(as(Role.DOCTOR)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"encounterId\":\"e\",\"content\":\"x\",\"practitionerId\":\"p\"}",
            "{\"encounterId\":\"e\",\"content\":\"x\",\"patientId\":\"syn-other\"}",
            "{\"encounterId\":\"e\",\"content\":\"x\",\"author\":\"Practitioner/p\"}",
            "{\"encounterId\":1,\"content\":\"x\"}", "{\"encounterId\":\"e\",\"content\":[\"x\"]}"})
    void unknownPropertiesAndNonTextValuesAreRejected(String body) throws Exception {
        mvc.perform(post("/api/patients/syn-patient-1/notes").with(csrf()).with(as(Role.DOCTOR))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":\"INVALID_REQUEST\"}", JsonCompareMode.STRICT));
        verifyNoInteractions(service);
    }

    @Test
    void malformedJsonIsABadRequestWithoutEcho() throws Exception {
        mvc.perform(post("/api/patients/syn-patient-1/notes").with(csrf()).with(as(Role.DOCTOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Hemlig"))
                .andExpect(status().isBadRequest()).andExpect(content().string(""));
    }

    @Test
    void errorsAreMappedToStatusesWithCodesOnly() throws Exception {
        record Case(RuntimeException failure, int status, String body) {
        }
        for (Case c : List.of(
                new Case(new ClinicalNoteException("CONTENT_REQUIRED"), 400, "{\"code\":\"CONTENT_REQUIRED\"}"),
                new Case(new ClinicalNoteException("CONTENT_TOO_LONG"), 400, "{\"code\":\"CONTENT_TOO_LONG\"}"),
                new Case(new ClinicalNoteException("ENCOUNTER_NOT_FOR_PATIENT"), 422,
                        "{\"code\":\"ENCOUNTER_NOT_FOR_PATIENT\"}"),
                new Case(new ClinicalNoteException("PRACTITIONER_NOT_LINKED"), 422,
                        "{\"code\":\"PRACTITIONER_NOT_LINKED\"}"),
                new Case(new PatientNotFoundException(), 404, ""),
                new Case(new IllegalArgumentException("bad id"), 400, ""),
                new Case(new AccessDeniedException("x"), 403, ""),
                new Case(new FhirServerException("document-reference-create", FhirServerException.Type.TIMEOUT, null,
                        List.of(), null), 503, ""),
                new Case(new FhirServerException("document-reference-search", FhirServerException.Type.INVALID_RESPONSE,
                        null, List.of(), null), 502, ""),
                new Case(new FhirServerException("document-reference-create", FhirServerException.Type.CLIENT_ERROR,
                        400, List.of(), null), 422, ""))) {
            doThrow(c.failure()).when(service).create(any(), any(), any());
            var result = mvc.perform(post("/api/patients/syn-patient-1/notes").with(csrf()).with(as(Role.STAFF))
                    .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().is(c.status()));
            if (c.body().isEmpty()) result.andExpect(content().string(""));
            else result.andExpect(content().json(c.body(), JsonCompareMode.STRICT));
        }
        when(service.listOwn(user(Role.PATIENT))).thenThrow(new ClinicalNoteException("RECORD_NOT_LINKED"));
        mvc.perform(get("/api/patients/me/notes").with(as(Role.PATIENT))).andExpect(status().isNotFound())
                .andExpect(content().json("{\"code\":\"RECORD_NOT_LINKED\"}", JsonCompareMode.STRICT));
    }
}
