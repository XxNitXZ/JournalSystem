package com.medinexus.medicore.fhir.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.HumanName.NameUse;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.medinexus.medicore.patient.dto.CreatePatientRequest;
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

    // --- toResource (US-04.01). Synthetic test personnummer only (Skatteverket's test series). ---

    private static final String PERSONNUMMER = "199001012385";

    private static CreatePatientRequest request(String personnummer, String family, List<String> given,
            CreatePatientRequest.Gender gender, LocalDate birthDate) {
        return new CreatePatientRequest(personnummer, family, given, gender, birthDate);
    }

    private static CreatePatientRequest valid() {
        return request(PERSONNUMMER, "Testsson", List.of("Tolvan", "Syntet"), CreatePatientRequest.Gender.FEMALE,
                LocalDate.of(1990, 1, 1));
    }

    @Test
    void buildsANewPatientWithThePersonnummerAsOfficialIdentifier() {
        Patient patient = mapper.toResource(valid());

        assertThat(patient.hasId()).isFalse();
        assertThat(patient.hasMeta()).isFalse();
        assertThat(patient.hasText()).isFalse();
        assertThat(patient.getIdentifier()).singleElement().satisfies(identifier -> {
            assertThat(identifier.getSystem()).isEqualTo("http://electronichealth.se/identifier/personnummer");
            assertThat(identifier.getValue()).isEqualTo(PERSONNUMMER);
            assertThat(identifier.getUse()).isEqualTo(Identifier.IdentifierUse.OFFICIAL);
        });
        assertThat(patient.getName()).singleElement().satisfies(name -> {
            assertThat(name.getUse()).isEqualTo(NameUse.OFFICIAL);
            assertThat(name.getFamily()).isEqualTo("Testsson");
            assertThat(name.getGiven()).extracting(Object::toString).containsExactly("Tolvan", "Syntet");
        });
        assertThat(patient.getGender()).isEqualTo(AdministrativeGender.FEMALE);
        assertThat(patient.getBirthDateElement().getValueAsString()).isEqualTo("1990-01-01");
        assertThat(patient.getActive()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(CreatePatientRequest.Gender.class)
    void mapsEveryGenderToAdministrativeGender(CreatePatientRequest.Gender gender) {
        Patient patient = mapper.toResource(request(PERSONNUMMER, "Testsson", List.of("Tolvan"), gender,
                LocalDate.of(1990, 1, 1)));
        assertThat(patient.getGender().toCode()).isEqualTo(gender.name().toLowerCase(java.util.Locale.ROOT));
    }

    @Test
    void stripsNamesAndRoundTripsThroughToDto() {
        Patient patient = mapper.toResource(request(PERSONNUMMER, "  Testsson ", List.of(" Tolvan "),
                CreatePatientRequest.Gender.OTHER, LocalDate.of(1990, 1, 1)));
        patient.setId("local-1");
        PatientDto dto = mapper.toDto(patient);
        assertThat(dto).isEqualTo(new PatientDto("local-1",
                List.of(new PatientIdentifier("http://electronichealth.se/identifier/personnummer", PERSONNUMMER)),
                List.of(new PatientName("official", null, "Testsson", List.of("Tolvan"))), "other", "1990-01-01"));
    }

    @Test
    void rejectsInvalidRegistrationDataWithMessagesThatNeverContainTheInput() {
        LocalDate birth = LocalDate.of(1990, 1, 1);
        List<String> given = List.of("Tolvan");
        CreatePatientRequest.Gender female = CreatePatientRequest.Gender.FEMALE;
        java.util.List<String> nullGiven = new java.util.ArrayList<>();
        nullGiven.add(null);
        for (CreatePatientRequest invalid : new CreatePatientRequest[] {
                // Personnummer: missing, 30 February, 10 digits, hyphen, invalid date (month 13), letters.
                request(null, "Testsson", given, female, birth),
                request("199002301234", "Testsson", given, female, birth),
                request("ABCDEFGHIJKL", "Testsson", given, female, birth),
                request("9001012385", "Testsson", given, female, birth),
                request("19900101-2385", "Testsson", given, female, birth),
                request("199013019808", "Testsson", given, female, LocalDate.of(1990, 1, 1)),
                // Birth date: different from the personnummer, or in the future (also when derived).
                request(PERSONNUMMER, "Testsson", given, female, LocalDate.of(1990, 1, 2)),
                request("209901019803", "Testsson", given, female, LocalDate.of(2099, 1, 1)),
                // Names: missing, blank, too long, control characters, no given name, blank or null given name,
                // too many given names.
                request(PERSONNUMMER, null, given, female, birth),
                request(PERSONNUMMER, "   ", given, female, birth),
                request(PERSONNUMMER, "x".repeat(101), given, female, birth),
                request(PERSONNUMMER, "Test\nsson", given, female, birth),
                request(PERSONNUMMER, "Testsson", null, female, birth),
                request(PERSONNUMMER, "Testsson", List.of(), female, birth),
                request(PERSONNUMMER, "Testsson", List.of(" "), female, birth),
                request(PERSONNUMMER, "Testsson", nullGiven, female, birth),
                request(PERSONNUMMER, "Testsson", java.util.Collections.nCopies(11, "Tolvan"), female, birth),
                // Gender missing.
                request(PERSONNUMMER, "Testsson", given, null, birth)}) {
            assertThatThrownBy(() -> mapper.toResource(invalid))
                    .isInstanceOfSatisfying(IllegalArgumentException.class, failure -> {
                        assertThat(failure.getMessage()).doesNotContainPattern("[0-9]{6}");
                        assertThat(failure.getMessage()).doesNotContain("Testsson", "Tolvan", "1990");
                    });
        }
        assertThatThrownBy(() -> mapper.toResource(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theBirthDateIsDerivedFromThePersonnummerWhenItIsNotGiven() {
        Patient patient = mapper.toResource(request(PERSONNUMMER, "Testsson", List.of("Tolvan"),
                CreatePatientRequest.Gender.FEMALE, null));
        assertThat(patient.getBirthDateElement().getValueAsString()).isEqualTo("1990-01-01");
        // A future date in the personnummer is rejected even when derived.
        assertThatThrownBy(() -> mapper.toResource(request("209901011230", "Testsson", List.of("Tolvan"),
                CreatePatientRequest.Gender.FEMALE, null))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aWrongLuhnCheckDigitDoesNotPreventRegistration() {
        // 200412201971: real past date, wrong check digit. Stored unchanged, birth date derived from the date part.
        Patient patient = mapper.toResource(request("200412201971", "Testsson", List.of("Tolvan"),
                CreatePatientRequest.Gender.MALE, null));
        assertThat(patient.getBirthDateElement().getValueAsString()).isEqualTo("2004-12-20");
        assertThat(patient.getIdentifier()).singleElement().satisfies(identifier -> {
            assertThat(identifier.getSystem()).isEqualTo("http://electronichealth.se/identifier/personnummer");
            assertThat(identifier.getValue()).isEqualTo("200412201971");
        });
        // A given birth date must still equal the date in the personnummer.
        assertThat(mapper.toResource(request("200412201971", "Testsson", List.of("Tolvan"),
                CreatePatientRequest.Gender.MALE, LocalDate.of(2004, 12, 20))).getBirthDateElement().getValueAsString())
                .isEqualTo("2004-12-20");
        assertThatThrownBy(() -> mapper.toResource(request("200412201971", "Testsson", List.of("Tolvan"),
                CreatePatientRequest.Gender.MALE, LocalDate.of(2004, 12, 21)))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsNamesAtTheLengthLimitAndTenGivenNames() {
        Patient patient = mapper.toResource(request(PERSONNUMMER, "x".repeat(100),
                java.util.Collections.nCopies(10, "Å".repeat(100)), CreatePatientRequest.Gender.UNKNOWN,
                LocalDate.of(1990, 1, 1)));
        assertThat(patient.getNameFirstRep().getFamily()).hasSize(100);
        assertThat(patient.getNameFirstRep().getGiven()).hasSize(10);
    }
}
