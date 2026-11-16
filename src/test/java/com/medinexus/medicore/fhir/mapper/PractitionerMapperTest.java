package com.medinexus.medicore.fhir.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hl7.fhir.r4.model.HumanName.NameUse;
import org.hl7.fhir.r4.model.Practitioner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.medinexus.medicore.practitioner.dto.PractitionerDto;
import com.medinexus.medicore.practitioner.dto.PractitionerDto.PractitionerIdentifier;
import com.medinexus.medicore.practitioner.dto.PractitionerDto.PractitionerName;

class PractitionerMapperTest {

    private final PractitionerMapper mapper = new PractitionerMapper();

    @Test
    void preservesIdentifiersNamesAndLogicalIdWithoutVersionOrServer() {
        Practitioner practitioner = new Practitioner();
        practitioner.setId("https://example.test/fhir/Practitioner/synthetic-1/_history/3");
        practitioner.addIdentifier().setSystem("urn:synthetic:practitioner").setValue("test-001");
        practitioner.addIdentifier().setValue("test-002");
        practitioner.addName().setUse(NameUse.OFFICIAL).setFamily("Synthetic").addGiven("Test").addGiven("Person");
        practitioner.addName().setUse(NameUse.NICKNAME).setText("Example");
        practitioner.setActive(true);

        PractitionerDto result = mapper.toDto(practitioner);

        assertThat(result.id()).isEqualTo("synthetic-1");
        assertThat(result.identifiers()).containsExactly(new PractitionerIdentifier("urn:synthetic:practitioner", "test-001"),
                new PractitionerIdentifier(null, "test-002"));
        assertThat(result.names()).containsExactly(
                new PractitionerName("official", null, "Synthetic", java.util.List.of("Test", "Person")),
                new PractitionerName("nickname", "Example", null, java.util.List.of()));
        assertThat(result.active()).isTrue();
    }

    @Test
    void missingOptionalFieldsMapToNullOrEmptyCollections() {
        PractitionerDto result = mapper.toDto(new Practitioner());
        assertThat(result.id()).isNull();
        assertThat(result.identifiers()).isEmpty();
        assertThat(result.names()).isEmpty();
        assertThat(result.active()).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void preservesExplicitActiveStatus(boolean active) {
        Practitioner practitioner = new Practitioner();
        practitioner.setActive(active);
        assertThat(mapper.toDto(practitioner).active()).isEqualTo(active);
    }

    @Test
    void dtoIsIndependentOfSubsequentResourceChanges() {
        Practitioner practitioner = new Practitioner();
        practitioner.addName().addGiven("Synthetic");
        PractitionerDto result = mapper.toDto(practitioner);
        practitioner.getNameFirstRep().addGiven("Changed");
        assertThat(result.names().getFirst().given()).containsExactly("Synthetic");
        assertThatThrownBy(() -> result.names().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullResource() {
        assertThatThrownBy(() -> mapper.toDto(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
