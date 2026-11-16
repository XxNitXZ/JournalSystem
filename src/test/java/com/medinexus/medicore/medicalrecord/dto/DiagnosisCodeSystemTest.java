package com.medinexus.medicore.medicalrecord.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** US-05.02: structural checks of diagnosis codes; no code is looked up or invented. */
class DiagnosisCodeSystemTest {

    @ParameterizedTest
    // Published SNOMED CT concept IDs (type 2 diabetes, hypertension, asthma, diabetes mellitus, myocardial infarction).
    @ValueSource(strings = {"44054006", "38341003", "195967001", "73211009", "22298006"})
    void realSnomedConceptIdsAreAccepted(String code) {
        assertThat(DiagnosisCodeSystem.SNOMED_CT.normalize(" " + code + " ")).isEqualTo(code);
    }

    @ParameterizedTest
    // Wrong check digit, too short, leading zero, letters, description/relationship partitions, too long.
    @ValueSource(strings = {"44054007", "38341004", "12345", "044054006", "4405400A", "", "44054016", "44054026",
            "1234567890123456789"})
    void malformedSnomedIdsAreRejected(String code) {
        assertThat(DiagnosisCodeSystem.SNOMED_CT.normalize(code)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"E11.9", "e11.9", "J45", "I10", "U07.1", "F32.A"})
    void icd10CodesAreAcceptedInUpperCase(String code) {
        assertThat(DiagnosisCodeSystem.ICD_10.normalize(code)).isEqualTo(code.toUpperCase());
    }

    @ParameterizedTest
    @ValueSource(strings = {"E1", "11.9", "E11.", "E11.12345", "EE1", "E11 9", "44054006"})
    void malformedIcd10CodesAreRejected(String code) {
        assertThat(DiagnosisCodeSystem.ICD_10.normalize(code)).isNull();
    }

    @Test
    void systemsUseTheOfficialFhirUris() {
        assertThat(DiagnosisCodeSystem.SNOMED_CT.system()).isEqualTo("http://snomed.info/sct");
        assertThat(DiagnosisCodeSystem.ICD_10.system()).isEqualTo("http://hl7.org/fhir/sid/icd-10");
        assertThat(DiagnosisCodeSystem.fromSystem("http://snomed.info/sct")).isEqualTo(DiagnosisCodeSystem.SNOMED_CT);
        assertThat(DiagnosisCodeSystem.fromSystem("urn:local")).isNull();
        assertThat(DiagnosisCodeSystem.SNOMED_CT.normalize(null)).isNull();
    }
}
