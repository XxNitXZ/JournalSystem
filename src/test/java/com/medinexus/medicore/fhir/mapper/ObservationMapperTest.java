package com.medinexus.medicore.fhir.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import org.hl7.fhir.r4.model.Observation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.medinexus.medicore.medicalrecord.dto.ObservationDto;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.BooleanValue;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.IntegerValue;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.ObservationValue;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.PeriodValue;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.QuantityValue;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.RangeValue;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.RatioValue;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.StringValue;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.ValueType;

import ca.uhn.fhir.context.FhirContext;

class ObservationMapperTest {
    private static final FhirContext CONTEXT = FhirContext.forR4();
    private final ObservationMapper mapper = new ObservationMapper();

    private Observation parse(String fields) {
        return CONTEXT.newJsonParser().parseResource(Observation.class,
                "{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":{\"text\":\"Synthetic\"}" + fields + "}");
    }

    @ParameterizedTest
    @MethodSource("values")
    void preservesTypedValues(String fields, ObservationValue expected) {
        assertThat(mapper.toDto(parse("," + fields)).value()).isEqualTo(expected);
    }

    static Stream<Arguments> values() {
        QuantityValue low = new QuantityValue(new BigDecimal("1"), null, "mg", "http://unitsofmeasure.org", "mg");
        return Stream.of(
                Arguments.of("\"valueQuantity\":{\"value\":12.50,\"comparator\":\"<\",\"unit\":\"mg\",\"system\":\"http://unitsofmeasure.org\",\"code\":\"mg\"}",
                        new ObservationValue(ValueType.QUANTITY, new QuantityValue(new BigDecimal("12.50"), "<", "mg", "http://unitsofmeasure.org", "mg"))),
                Arguments.of("\"valueString\":\"Synthetic finding\"", new ObservationValue(ValueType.STRING, new StringValue("Synthetic finding"))),
                Arguments.of("\"valueBoolean\":false", new ObservationValue(ValueType.BOOLEAN, new BooleanValue(false))),
                Arguments.of("\"valueInteger\":0", new ObservationValue(ValueType.INTEGER, new IntegerValue(0))),
                Arguments.of("\"valueCodeableConcept\":{\"text\":\"Negative\",\"coding\":[{\"system\":\"urn:synthetic\",\"code\":\"neg\",\"display\":\"Negative\"}]}",
                        new ObservationValue(ValueType.CODEABLE_CONCEPT, new Concept("Negative", List.of(new ObservationDto.Coding("urn:synthetic", null, "neg", "Negative", null))))),
                Arguments.of("\"valueRange\":{\"low\":{\"value\":1,\"unit\":\"mg\",\"system\":\"http://unitsofmeasure.org\",\"code\":\"mg\"}}",
                        new ObservationValue(ValueType.RANGE, new RangeValue(low, null))),
                Arguments.of("\"valueRatio\":{\"numerator\":{\"value\":1,\"unit\":\"mg\",\"system\":\"http://unitsofmeasure.org\",\"code\":\"mg\"},\"denominator\":{\"value\":2}}",
                        new ObservationValue(ValueType.RATIO, new RatioValue(low, new QuantityValue(new BigDecimal("2"), null, null, null, null)))),
                Arguments.of("\"valueTime\":\"12:34:56\"", new ObservationValue(ValueType.TIME, new StringValue("12:34:56"))),
                Arguments.of("\"valueDateTime\":\"2026-06\"", new ObservationValue(ValueType.DATE_TIME, new StringValue("2026-06"))),
                Arguments.of("\"valueDateTime\":\"2026-06-15T12:00:00+02:00\"", new ObservationValue(ValueType.DATE_TIME, new StringValue("2026-06-15T12:00:00+02:00"))),
                Arguments.of("\"valuePeriod\":{\"start\":\"2026-06\"}", new ObservationValue(ValueType.PERIOD, new PeriodValue("2026-06", null))),
                Arguments.of("\"valueQuantity\":{\"unit\":\"mg\"}", new ObservationValue(ValueType.QUANTITY, new QuantityValue(null, null, "mg", null, null)))
        );
    }

    @Test
    void preservesLogicalIdSubjectAndAllCodeCodings() {
        Observation observation = parse("""
                ,"subject":{"reference":"Patient/synthetic-patient","type":"Patient","display":"Synthetic person",
                  "identifier":{"system":"urn:synthetic","value":"test-1"}},
                "valueString":"Synthetic"
                """);
        observation.setId("https://example.test/fhir/Observation/synthetic-1/_history/4");
        observation.getCode().addCoding().setSystem("urn:synthetic:code").setVersion("1").setCode("x")
                .setDisplay("Example").setUserSelected(false);
        observation.getCode().addCoding().setDisplay("Display only");
        ObservationDto dto = mapper.toDto(observation);
        assertThat(dto.id()).isEqualTo("synthetic-1");
        assertThat(dto.status()).isEqualTo("final");
        assertThat(dto.subject()).isEqualTo(new ObservationDto.SubjectReference("Patient/synthetic-patient", "Patient",
                "Synthetic person", new ObservationDto.Identifier("urn:synthetic", "test-1")));
        assertThat(dto.code().coding()).containsExactly(
                new ObservationDto.Coding("urn:synthetic:code", "1", "x", "Example", false),
                new ObservationDto.Coding(null, null, null, "Display only", null));
        observation.getCode().getCodingFirstRep().setCode("changed");
        assertThat(dto.code().coding().getFirst().code()).isEqualTo("x");
        assertThatThrownBy(() -> dto.code().coding().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void missingOptionalFieldsRemainAbsent() {
        ObservationDto dto = mapper.toDto(parse(""));
        assertThat(dto.id()).isNull();
        assertThat(dto.subject()).isNull();
        assertThat(dto.value()).isNull();
        assertThat(dto.dataAbsentReason()).isNull();
        assertThat(dto.code().coding()).isEmpty();
    }

    @Test
    void preservesAbsenceReasonWithoutInventingValue() {
        ObservationDto dto = mapper.toDto(parse(",\"dataAbsentReason\":{\"text\":\"Not measured\"}"));
        assertThat(dto.value()).isNull();
        assertThat(dto.dataAbsentReason().text()).isEqualTo("Not measured");
    }

    @Test
    void doesNotReinterpretNonPatientOrIdentifierOnlySubject() {
        assertThat(mapper.toDto(parse(",\"subject\":{\"reference\":\"Device/test-device\"}")).subject().reference())
                .isEqualTo("Device/test-device");
        var subject = mapper.toDto(parse(",\"subject\":{\"identifier\":{\"value\":\"test-id\"}}")).subject();
        assertThat(subject.reference()).isNull();
        assertThat(subject.identifier().value()).isEqualTo("test-id");
    }

    @Test
    void rejectsMissingRequiredFieldsAndNullResource() {
        assertThatThrownBy(() -> mapper.toDto(null)).isInstanceOf(IllegalArgumentException.class);
        Observation noStatus = new Observation(); noStatus.getCode().setText("Synthetic");
        assertThatThrownBy(() -> mapper.toDto(noStatus)).isInstanceOf(IllegalArgumentException.class);
        Observation noCode = new Observation(); noCode.setStatus(Observation.ObservationStatus.FINAL);
        assertThatThrownBy(() -> mapper.toDto(noCode)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsupportedValuesAndConflictingAbsenceReason() {
        assertThatThrownBy(() -> mapper.toDto(parse(",\"valueSampledData\":{\"origin\":{\"value\":0},\"period\":1,\"dimensions\":1,\"data\":\"1 2 3\"}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Observation value type is not supported by this projection");
        assertThatThrownBy(() -> mapper.toDto(parse(",\"valueBoolean\":false,\"dataAbsentReason\":{\"text\":\"Unknown\"}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot both be present");
    }
    @ParameterizedTest
    @MethodSource("values")
    void mapsComponentUsingAllExistingSupportedValueTypes(String fields, ObservationValue expected) {
        ObservationDto dto = mapper.toDto(parse(",\"component\":[{\"code\":{\"text\":\"Synthetic component\"}," + fields + "}]"));
        assertThat(dto.value()).isNull();
        assertThat(dto.components()).hasSize(1);
        assertThat(dto.components().getFirst().code().text()).isEqualTo("Synthetic component");
        assertThat(dto.components().getFirst().value()).isEqualTo(expected);
        assertThat(dto.components().getFirst().dataAbsentReason()).isNull();
    }

    @Test
    void preservesComponentOrderMissingValuesAndAbsenceReason() {
        Observation observation = parse("""
                ,"valueString":"Top level",
                "component":[
                  {"code":{"text":"First"},"valueBoolean":false},
                  {"code":{"text":"Second"},"dataAbsentReason":{"text":"Not measured"}},
                  {"code":{"text":"Third"}}
                ]
                """);
        ObservationDto dto = mapper.toDto(observation);
        assertThat(dto.value()).isEqualTo(new ObservationValue(ValueType.STRING, new StringValue("Top level")));
        assertThat(dto.components()).extracting(c -> c.code().text()).containsExactly("First", "Second", "Third");
        assertThat(dto.components().get(1).value()).isNull();
        assertThat(dto.components().get(1).dataAbsentReason().text()).isEqualTo("Not measured");
        assertThat(dto.components().get(2).value()).isNull();
        assertThat(dto.components().get(2).dataAbsentReason()).isNull();
        observation.getComponentFirstRep().getCode().setText("Changed");
        observation.getComponent().clear();
        assertThat(dto.components().getFirst().code().text()).isEqualTo("First");
        assertThatThrownBy(() -> dto.components().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void observationsWithoutComponentsHaveEmptyImmutableList() {
        var components = mapper.toDto(parse("")).components();
        assertThat(components).isEmpty();
        assertThatThrownBy(() -> components.add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMalformedComponentsAndConflictingValues() {
        assertThatThrownBy(() -> mapper.toDto(parse(",\"component\":[{\"valueInteger\":1}]")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Observation component code is required");
        assertThatThrownBy(() -> mapper.toDto(parse("""
                ,"component":[{"code":{"text":"Synthetic"},"valueBoolean":false,"dataAbsentReason":{"text":"Unknown"}}]
                """)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot both be present");
    }

    @Test
    void rejectsUnsupportedComponentValuesWithoutDiscardingThem() {
        assertThatThrownBy(() -> mapper.toDto(parse("""
                ,"component":[{"code":{"text":"Synthetic"},
                  "valueSampledData":{"origin":{"value":0},"period":1,"dimensions":1,"data":"1 2 3"}}]
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Observation value type is not supported by this projection");
    }

}
