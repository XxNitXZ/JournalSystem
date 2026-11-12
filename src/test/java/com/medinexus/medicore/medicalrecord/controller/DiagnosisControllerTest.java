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
import com.medinexus.medicore.medicalrecord.dto.ClinicalNoteDto.EncounterContext;
import com.medinexus.medicore.medicalrecord.dto.DiagnosisDto;
import com.medinexus.medicore.medicalrecord.dto.NewDiagnosisRequest;
import com.medinexus.medicore.medicalrecord.service.DiagnosisRequestException;
import com.medinexus.medicore.medicalrecord.service.DiagnosisService;
import com.medinexus.medicore.patient.service.PatientNotFoundException;

/** US-05.02: diagnosis endpoints, route security, CSRF, strict bodies and status mapping. Fictional data. */
@WebMvcTest(DiagnosisController.class)
@Import(SecurityConfig.class)
class DiagnosisControllerTest {

    private static final String BODY = "{\"diagnosis\":\"Typ 2-diabetes\",\"code\":\"44054006\",\"codeSystem\":\"SNOMED_CT\","
            + "\"clinicalStatus\":\"ACTIVE\",\"verificationStatus\":\"CONFIRMED\",\"onsetDate\":\"2026-10-01\","
            + "\"encounterId\":\"syn-enc-1\"}";
    private static final NewDiagnosisRequest REQUEST = new NewDiagnosisRequest("Typ 2-diabetes", "44054006", "SNOMED_CT",
            "ACTIVE", "CONFIRMED", "2026-10-01", "syn-enc-1");
    private static final DiagnosisDto DIAGNOSIS = new DiagnosisDto("cond-1", "syn-patient-1", "Typ 2-diabetes",
            new DiagnosisDto.Code("http://snomed.info/sct", "SNOMED CT", "44054006", null), "active", "confirmed",
            "encounter-diagnosis", "2026-11-12T09:30:12Z", "2026-10-01",
            new EncounterContext("syn-enc-1", "2026-11-10T14:30:00+01:00", "Consultation"), "Dr Ali Khan");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthenticationService authenticationService;

    @MockitoBean
    private DiagnosisService service;

    private static UserDto user(Role role) {
        return new UserDto(42L, "user@example.test", role);
    }

    private static RequestPostProcessor as(Role role) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(user(role), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void doctorsAndStaffRecordAndReadDiagnoses(Role role) throws Exception {
        when(service.create(user(role), "syn-patient-1", REQUEST)).thenReturn(DIAGNOSIS);
        when(service.listForPatient(user(role), "syn-patient-1")).thenReturn(List.of(DIAGNOSIS));

        mvc.perform(post("/api/patients/syn-patient-1/conditions").with(csrf()).with(as(role))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/patients/syn-patient-1/conditions"))
                .andExpect(content().json("""
                        {"id":"cond-1","patientId":"syn-patient-1","diagnosis":"Typ 2-diabetes",
                         "code":{"system":"http://snomed.info/sct","systemName":"SNOMED CT","code":"44054006","display":null},
                         "clinicalStatus":"active","verificationStatus":"confirmed","category":"encounter-diagnosis",
                         "recordedDate":"2026-11-12T09:30:12Z","onset":"2026-10-01",
                         "encounter":{"id":"syn-enc-1","start":"2026-11-10T14:30:00+01:00","type":"Consultation"},
                         "recorder":"Dr Ali Khan"}
                        """, JsonCompareMode.STRICT));
        mvc.perform(get("/api/patients/syn-patient-1/conditions").with(as(role)))
                .andExpect(status().isOk())
                .andExpect(content().json("[{\"id\":\"cond-1\",\"diagnosis\":\"Typ 2-diabetes\"}]"));
        verify(service).create(user(role), "syn-patient-1", REQUEST);
    }

    @Test
    void patientsReadOnlyTheirOwnDiagnosesAndCannotWrite() throws Exception {
        when(service.listOwn(user(Role.PATIENT))).thenReturn(List.of(DIAGNOSIS));
        mvc.perform(get("/api/patients/me/conditions").with(as(Role.PATIENT)))
                .andExpect(status().isOk()).andExpect(content().json("[{\"id\":\"cond-1\"}]"));
        mvc.perform(get("/api/patients/me/conditions").param("patientId", "syn-other").with(as(Role.PATIENT)))
                .andExpect(status().isOk());
        verify(service, times(2)).listOwn(user(Role.PATIENT));

        mvc.perform(get("/api/patients/syn-patient-1/conditions").with(as(Role.PATIENT))).andExpect(status().isForbidden());
        mvc.perform(post("/api/patients/syn-patient-1/conditions").with(csrf()).with(as(Role.PATIENT))
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(post("/api/patients/me/conditions").with(csrf()).with(as(Role.PATIENT))
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(get("/api/patients/me/conditions").with(as(Role.DOCTOR))).andExpect(status().isForbidden());
    }

    @Test
    void anonymousRequestsMissingCsrfAndChangesAreRejected() throws Exception {
        mvc.perform(get("/api/patients/syn-patient-1/conditions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/patients/me/conditions")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/patients/syn-patient-1/conditions").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/patients/syn-patient-1/conditions").with(as(Role.DOCTOR))
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        // No update or delete exists, for any role.
        for (Role role : Role.values()) {
            mvc.perform(put("/api/patients/syn-patient-1/conditions/cond-1").with(csrf()).with(as(role))
                    .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
            mvc.perform(patch("/api/patients/syn-patient-1/conditions/cond-1").with(csrf()).with(as(role))
                    .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
            mvc.perform(delete("/api/patients/syn-patient-1/conditions/cond-1").with(csrf()).with(as(role)))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"diagnosis\":\"x\",\"patientId\":\"syn-other\"}",
            "{\"diagnosis\":\"x\",\"subject\":\"Patient/syn-other\"}", "{\"diagnosis\":\"x\",\"recorder\":\"Practitioner/p\"}",
            "{\"diagnosis\":\"x\",\"practitionerId\":\"p\"}", "{\"diagnosis\":\"x\",\"id\":\"cond-1\"}",
            "{\"diagnosis\":1}", "{\"diagnosis\":\"x\",\"onsetDate\":[\"2026\"]}"})
    void unknownPropertiesAndNonTextValuesAreRejected(String body) throws Exception {
        mvc.perform(post("/api/patients/syn-patient-1/conditions").with(csrf()).with(as(Role.DOCTOR))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":\"INVALID_REQUEST\"}", JsonCompareMode.STRICT));
        verifyNoInteractions(service);
    }

    @Test
    void errorsAreMappedToStatusesWithCodesOnly() throws Exception {
        record Case(RuntimeException failure, int status, String body) {
        }
        for (Case c : List.of(
                new Case(new DiagnosisRequestException("DIAGNOSIS_REQUIRED"), 400, "{\"code\":\"DIAGNOSIS_REQUIRED\"}"),
                new Case(new DiagnosisRequestException("CODE_INVALID"), 400, "{\"code\":\"CODE_INVALID\"}"),
                new Case(new DiagnosisRequestException("ONSET_IN_FUTURE"), 400, "{\"code\":\"ONSET_IN_FUTURE\"}"),
                new Case(new DiagnosisRequestException("ENCOUNTER_NOT_FOR_PATIENT"), 422,
                        "{\"code\":\"ENCOUNTER_NOT_FOR_PATIENT\"}"),
                new Case(new DiagnosisRequestException("PRACTITIONER_NOT_LINKED"), 422,
                        "{\"code\":\"PRACTITIONER_NOT_LINKED\"}"),
                new Case(new PatientNotFoundException(), 404, ""),
                new Case(new IllegalArgumentException("bad id"), 400, ""),
                new Case(new AccessDeniedException("x"), 403, ""),
                new Case(new FhirServerException("condition-create", FhirServerException.Type.TIMEOUT, null, List.of(),
                        null), 503, ""),
                new Case(new FhirServerException("condition-create", FhirServerException.Type.INVALID_RESPONSE, null,
                        List.of(), null), 502, ""))) {
            doThrow(c.failure()).when(service).create(any(), any(), any());
            var result = mvc.perform(post("/api/patients/syn-patient-1/conditions").with(csrf()).with(as(Role.STAFF))
                    .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().is(c.status()));
            if (c.body().isEmpty()) result.andExpect(content().string(""));
            else result.andExpect(content().json(c.body(), JsonCompareMode.STRICT));
        }
        when(service.listOwn(user(Role.PATIENT))).thenThrow(new DiagnosisRequestException("RECORD_NOT_LINKED"));
        mvc.perform(get("/api/patients/me/conditions").with(as(Role.PATIENT))).andExpect(status().isNotFound())
                .andExpect(content().json("{\"code\":\"RECORD_NOT_LINKED\"}", JsonCompareMode.STRICT));
        when(service.listForPatient(user(Role.DOCTOR), "syn-patient-1")).thenThrow(new FhirServerException(
                "condition-search", FhirServerException.Type.INVALID_RESPONSE, null, List.of(), null));
        mvc.perform(get("/api/patients/syn-patient-1/conditions").with(as(Role.DOCTOR))).andExpect(status().isBadGateway());
    }
}
