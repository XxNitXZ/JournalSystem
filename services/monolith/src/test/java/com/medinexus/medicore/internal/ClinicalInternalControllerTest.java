package com.medinexus.medicore.internal;

import static com.medinexus.medicore.common.exception.ApiErrorMatchers.genericError;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.service.AccountTokenService;
import com.medinexus.medicore.auth.service.AuthenticationService;
import com.medinexus.medicore.config.SecurityConfig;
import com.medinexus.medicore.fhir.client.FhirPatientClient;
import com.medinexus.medicore.fhir.client.FhirPractitionerClient;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.fhir.mapper.PatientMapper;
import com.medinexus.medicore.patient.registry.PatientRegistry;
import com.medinexus.medicore.security.jwt.TestTokens;
import com.medinexus.medicore.security.jwt.UserClaims;

/** The Clinical internal API C1–C4 as the monolith serves it for Identity (docs/architecture.md §12, NIT-330). */
@WebMvcTest(ClinicalInternalController.class)
@Import({SecurityConfig.class, PatientMapper.class})
class ClinicalInternalControllerTest {

    private static final String PERSONNUMMER = "182003151236";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PatientRegistry registry;
    @MockitoBean
    private FhirPatientClient patients;
    @MockitoBean
    private FhirPractitionerClient practitioners;
    @MockitoBean
    private AuthenticationService authenticationService;
    @MockitoBean
    private AccountTokenService accountTokens;

    private static MockHttpServletRequestBuilder asService(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + TestTokens.tokens().serviceToken("identity"));
    }

    private static Patient patient(String id, String personnummer) {
        Patient patient = new Patient();
        patient.setId(id);
        patient.addIdentifier().setSystem("http://electronichealth.se/identifier/personnummer").setValue(personnummer);
        patient.addName().setUse(HumanName.NameUse.OFFICIAL).setFamily("Khan").addGiven("Ali");
        patient.setGender(AdministrativeGender.MALE);
        patient.getBirthDateElement().setValueAsString("1820-03-15");
        return patient;
    }

    @Test
    void onlyServiceTokensGetIn() throws Exception {
        mvc.perform(get("/internal/patients/fhir-1")).andExpect(status().isUnauthorized()).andExpect(genericError());
        for (Role role : Role.values()) {
            mvc.perform(get("/internal/patients/fhir-1").cookie(TestTokens.cookieFor(
                    new UserClaims(42L, "user@example.test", role, null, null, null, null))))
                    .andExpect(status().isForbidden()).andExpect(genericError());
        }
        verifyNoInteractions(registry, patients, practitioners);
    }

    @Test
    void c1ReturnsEveryRegisteredHitWithItsPersonnummerVerdict() throws Exception {
        when(registry.registeredIds()).thenReturn(List.of("fhir-1", "fhir-2"));
        String dashed = PERSONNUMMER.substring(0, 8) + "-" + PERSONNUMMER.substring(8);
        Patient conflicting = patient("fhir-2", PERSONNUMMER);
        conflicting.addIdentifier().setSystem("http://electronichealth.se/identifier/personnummer").setValue("182511044568");
        when(patients.searchPatients(any(), eq(List.of("fhir-1", "fhir-2"))))
                .thenReturn(List.of(patient("fhir-1", dashed), conflicting));

        mvc.perform(asService(get("/internal/patients/registered").param("personnummer", PERSONNUMMER)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"records":[{"id":"fhir-1","personnummerStatus":"VALID","personnummer":"182003151236"},
                                    {"id":"fhir-2","personnummerStatus":"AMBIGUOUS","personnummer":null}]}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void c1WithAnEmptyRegistryOrABadPersonnummerSearchesNothing() throws Exception {
        when(registry.registeredIds()).thenReturn(List.of());
        mvc.perform(asService(get("/internal/patients/registered").param("personnummer", PERSONNUMMER)))
                .andExpect(status().isOk()).andExpect(content().json("{\"records\":[]}", JsonCompareMode.STRICT));
        mvc.perform(asService(get("/internal/patients/registered").param("personnummer", "1820-03")))
                .andExpect(status().isBadRequest()).andExpect(genericError());
        verify(patients, never()).searchPatients(any(), any());
    }

    @Test
    void c2ReturnsTheRecordOfAKnownPatientOnly() throws Exception {
        when(registry.isKnown("fhir-1")).thenReturn(true);
        when(patients.readPatient("fhir-1")).thenReturn(patient("fhir-1", PERSONNUMMER));
        mvc.perform(asService(get("/internal/patients/fhir-1")))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"id":"fhir-1","givenNames":["Ali"],"familyName":"Khan","personnummerStatus":"VALID",
                         "personnummer":"182003151236","gender":"male","birthDate":"1820-03-15"}
                        """, JsonCompareMode.STRICT));

        // Unknown to MediCore: never read from FHIR.
        mvc.perform(asService(get("/internal/patients/someone-else"))).andExpect(status().isNotFound())
                .andExpect(genericError());
        verify(patients, never()).readPatient("someone-else");

        // FHIR returned another Patient than asked for.
        when(registry.isKnown("fhir-3")).thenReturn(true);
        when(patients.readPatient("fhir-3")).thenReturn(patient("fhir-other", PERSONNUMMER));
        mvc.perform(asService(get("/internal/patients/fhir-3"))).andExpect(status().isBadGateway());

        mvc.perform(asService(get("/internal/patients/bad_id"))).andExpect(status().isBadRequest());
    }

    @Test
    void c3VerifiesThePatientThenRegistersIt() throws Exception {
        when(patients.readPatient("fhir-1")).thenReturn(patient("fhir-1", PERSONNUMMER));
        mvc.perform(asService(post("/internal/registry/fhir-1"))).andExpect(status().isNoContent());
        verify(registry).recordImport("fhir-1", null);

        when(patients.readPatient("missing")).thenThrow(new FhirServerException("patient-read",
                FhirServerException.Type.CLIENT_ERROR, 404, List.of(), null));
        mvc.perform(asService(post("/internal/registry/missing"))).andExpect(status().isNotFound());
        verify(registry, never()).recordImport(eq("missing"), any());
    }

    @Test
    void c4AnswersWhetherThePractitionerExists() throws Exception {
        Practitioner practitioner = new Practitioner();
        practitioner.setId("prac-1");
        when(practitioners.readPractitioner("prac-1")).thenReturn(practitioner);
        mvc.perform(asService(get("/internal/practitioners/prac-1"))).andExpect(status().isNoContent());
        when(practitioners.readPractitioner("gone")).thenThrow(new FhirServerException("practitioner-read",
                FhirServerException.Type.CLIENT_ERROR, 410, List.of(), null));
        mvc.perform(asService(get("/internal/practitioners/gone"))).andExpect(status().isNotFound());
    }

    @Test
    void fhirOutagesAre503AndInvalidAnswers502() throws Exception {
        when(registry.isKnown("fhir-1")).thenReturn(true);
        when(patients.readPatient("fhir-1")).thenThrow(new FhirServerException("patient-read",
                FhirServerException.Type.TIMEOUT, null, List.of(), null));
        mvc.perform(asService(get("/internal/patients/fhir-1"))).andExpect(status().isServiceUnavailable())
                .andExpect(genericError());
        when(practitioners.readPractitioner("prac-1")).thenThrow(new FhirServerException("practitioner-read",
                FhirServerException.Type.INVALID_RESPONSE, null, List.of(), null));
        mvc.perform(asService(get("/internal/practitioners/prac-1"))).andExpect(status().isBadGateway())
                .andExpect(genericError());
    }
}
