package com.medinexus.medicore.fhir.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hl7.fhir.r4.model.Address;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.Test;

import com.medinexus.medicore.patient.dto.CreatePatientRequest.Gender;
import com.medinexus.medicore.patient.dto.UpdatePatientRequest;

/** US-04.04: only the gender changes; names and everything else stay exactly as read. Fictional data. */
class PatientDemographicsUpdateTest {

    private final PatientMapper mapper = new PatientMapper();

    private static Patient rich() {
        Patient patient = new Patient();
        patient.setId("Patient/syn-1/_history/3");
        patient.getMeta().setVersionId("3").addTag("urn:example:tag", "kept", null);
        patient.addIdentifier().setSystem("http://electronichealth.se/identifier/personnummer").setValue("200412201971");
        patient.addName().setUse(HumanName.NameUse.NICKNAME).addGiven("Alle");
        patient.addName().setUse(HumanName.NameUse.OFFICIAL).setFamily("Khan").addGiven("Ali").addPrefix("Dr")
                .setText("Dr Ali Khan");
        patient.setGender(AdministrativeGender.MALE);
        patient.getBirthDateElement().setValueAsString("2004-12-20");
        patient.addTelecom().setSystem(ContactPoint.ContactPointSystem.EMAIL).setValue("external@example.test");
        patient.addAddress(new Address().setCity("Stockholm"));
        patient.addExtension("urn:example:extension", new StringType("kept"));
        patient.setActive(true);
        return patient;
    }

    @Test
    void onlyTheGenderChanges() {
        for (Gender gender : Gender.values()) {
            Patient patient = rich();
            mapper.applyGender(patient, new UpdatePatientRequest(gender));
            assertThat(patient.getGender().toCode()).isEqualTo(gender.name().toLowerCase());

            // Everything else, names and existing telecom included, is exactly as read.
            Patient expected = rich();
            expected.setGender(patient.getGender());
            assertThat(patient.equalsDeep(expected)).isTrue();
            assertThat(patient.getName().get(1).getText()).isEqualTo("Dr Ali Khan");
            assertThat(patient.getTelecomFirstRep().getValue()).isEqualTo("external@example.test");
        }
    }

    @Test
    void aMissingGenderIsRejectedWithoutChangingThePatient() {
        Patient patient = rich();
        assertThatThrownBy(() -> mapper.applyGender(patient, new UpdatePatientRequest(null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(patient.equalsDeep(rich())).isTrue();
        assertThatThrownBy(() -> mapper.applyGender(null, new UpdatePatientRequest(Gender.MALE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.applyGender(rich(), null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theVersionComesFromTheIdOrTheMeta() {
        assertThat(PatientMapper.versionOf(rich())).isEqualTo("3");
        Patient metaOnly = new Patient();
        metaOnly.setId("syn-1");
        metaOnly.getMeta().setVersionId("7");
        assertThat(PatientMapper.versionOf(metaOnly)).isEqualTo("7");
        Patient none = new Patient();
        none.setId("syn-1");
        assertThat(PatientMapper.versionOf(none)).isNull();
        assertThat(PatientMapper.versionOf(null)).isNull();
    }
}
