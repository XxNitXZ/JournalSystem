package com.medinexus.medicore.fhir.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName.NameUse;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.medinexus.medicore.patient.dto.PatientDto;
import com.medinexus.medicore.patient.dto.PatientDto.PatientIdentifier;
import com.medinexus.medicore.patient.dto.PatientDto.PatientName;

class PatientMapperTest {

    private final PatientMapper mapper = new PatientMapper();

    @Test
    void preservesIdentifiersNamesAndLogicalIdWithoutVersionOrServer() {
        Patient patient = new Patient();
        patient.setId("https://example.test/fhir/Patient/synthetic-1/_history/3");
        patient.addIdentifier().setSystem("urn:synthetic:patient").setValue("test-001");
        patient.addIdentifier().setValue("test-002");
        patient.addName().setUse(NameUse.OFFICIAL).setFamily("Synthetic").addGiven("Test").addGiven("Person");
        patient.addName().setUse(NameUse.NICKNAME).setText("Example");
        patient.setGender(AdministrativeGender.UNKNOWN);

        PatientDto result = mapper.toDto(patient);

        assertThat(result.id()).isEqualTo("synthetic-1");
        assertThat(result.identifiers()).containsExactly(new PatientIdentifier("urn:synthetic:patient", "test-001"),
                new PatientIdentifier(null, "test-002"));
        assertThat(result.names()).containsExactly(
                new PatientName("official", null, "Synthetic", java.util.List.of("Test", "Person")),
                new PatientName("nickname", "Example", null, java.util.List.of()));
        assertThat(result.gender()).isEqualTo("unknown");
    }

    @Test
    void missingOptionalFieldsMapToNullOrEmptyCollections() {
        PatientDto result = mapper.toDto(new Patient());
        assertThat(result.id()).isNull();
        assertThat(result.identifiers()).isEmpty();
        assertThat(result.names()).isEmpty();
        assertThat(result.gender()).isNull();
        assertThat(result.birthDate()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1980", "1980-06", "1980-06-15"})
    void preservesBirthDatePrecision(String date) {
        Patient patient = new Patient();
        patient.getBirthDateElement().setValueAsString(date);
        assertThat(mapper.toDto(patient).birthDate()).isEqualTo(date);
    }

    @Test
    void dtoIsIndependentOfSubsequentResourceChanges() {
        Patient patient = new Patient();
        patient.addName().addGiven("Synthetic");
        PatientDto result = mapper.toDto(patient);
        patient.getNameFirstRep().addGiven("Changed");
        assertThat(result.names().getFirst().given()).containsExactly("Synthetic");
        assertThatThrownBy(() -> result.names().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullResource() {
        assertThatThrownBy(() -> mapper.toDto(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
