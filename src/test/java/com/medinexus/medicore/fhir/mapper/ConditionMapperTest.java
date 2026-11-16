package com.medinexus.medicore.fhir.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.Condition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.medinexus.medicore.medicalrecord.dto.ConditionDto;
import com.medinexus.medicore.medicalrecord.dto.ConditionDto.TemporalType;
import com.medinexus.medicore.medicalrecord.dto.ConditionDto.TemporalValue;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.QuantityValue;

import ca.uhn.fhir.context.FhirContext;

class ConditionMapperTest {
    private static final FhirContext CONTEXT = FhirContext.forR4();
    private final ConditionMapper mapper = new ConditionMapper();

    private Condition parse(String fields) {
        return CONTEXT.newJsonParser().parseResource(Condition.class,
                "{\"resourceType\":\"Condition\",\"subject\":{\"reference\":\"Patient/synthetic-patient\"}" + fields + "}");
    }

    static Stream<Arguments> temporals() {
        QuantityValue quantity = new QuantityValue(new BigDecimal("12.50"), "<", "years", "http://unitsofmeasure.org", "a");
        List<Arguments> cases = List.of(
                Arguments.of("DateTime", "\"2026\"", new TemporalValue(TemporalType.DATE_TIME, new ConditionDto.TextValue("2026"))),
                Arguments.of("DateTime", "\"2026-06\"", new TemporalValue(TemporalType.DATE_TIME, new ConditionDto.TextValue("2026-06"))),
                Arguments.of("DateTime", "\"2026-06-15T12:34:56+02:00\"", new TemporalValue(TemporalType.DATE_TIME, new ConditionDto.TextValue("2026-06-15T12:34:56+02:00"))),
                Arguments.of("Age", "{\"value\":12.50,\"comparator\":\"<\",\"unit\":\"years\",\"system\":\"http://unitsofmeasure.org\",\"code\":\"a\"}",
                        new TemporalValue(TemporalType.AGE, new ConditionDto.AgeValue(quantity))),
                Arguments.of("Period", "{\"start\":\"2025-06\",\"end\":\"2026-06-15\"}",
                        new TemporalValue(TemporalType.PERIOD, new ConditionDto.PeriodValue("2025-06", "2026-06-15"))),
                Arguments.of("Period", "{\"end\":\"2026\"}", new TemporalValue(TemporalType.PERIOD, new ConditionDto.PeriodValue(null, "2026"))),
                Arguments.of("Range", "{\"low\":{\"value\":12.50,\"comparator\":\"<\",\"unit\":\"years\",\"system\":\"http://unitsofmeasure.org\",\"code\":\"a\"}}",
                        new TemporalValue(TemporalType.RANGE, new ConditionDto.RangeValue(quantity, null))),
                Arguments.of("String", "\"During childhood\"", new TemporalValue(TemporalType.STRING, new ConditionDto.TextValue("During childhood")))
        );
        return Stream.of("onset", "abatement").flatMap(field -> cases.stream().map(args -> {
            Object[] values = args.get();
            return Arguments.of(",\"" + field + values[0] + "\":" + values[1], field, values[2]);
        }));
    }

    @ParameterizedTest
    @MethodSource("temporals")
    void preservesEachTemporalTypePrecisionAndUnits(String fields, String field, TemporalValue expected) {
        var result = mapper.toDto(parse(fields));
        assertThat(field.equals("onset") ? result.onset() : result.abatement()).isEqualTo(expected);
    }

    @Test
    void preservesTerminologyStatusesCategorySeverityAndSubject() {
        Condition condition = parse("""
                ,"clinicalStatus":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/condition-clinical","code":"active","display":"Active"}]},
                "verificationStatus":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/condition-ver-status","code":"confirmed"}]},
                "category":[{"text":"First category"},{"text":"Second category"}],
                "severity":{"text":"Synthetic severity"},
                "code":{"text":"Synthetic diagnosis","coding":[
                  {"system":"urn:synthetic:diagnosis","version":"1","code":"test","display":"Example","userSelected":false},
                  {"system":"urn:synthetic:second","code":"other"}]}
                """);
        condition.setId("https://example.test/fhir/Condition/synthetic-1/_history/3");
        condition.getSubject().setDisplay("Synthetic patient").setType("Patient");
        condition.getSubject().getIdentifier().setSystem("urn:synthetic").setValue("test-id");
        var dto = mapper.toDto(condition);
        assertThat(dto.id()).isEqualTo("synthetic-1");
        assertThat(dto.subject()).isEqualTo(new ObservationDto.SubjectReference("Patient/synthetic-patient", "Patient",
                "Synthetic patient", new ObservationDto.Identifier("urn:synthetic", "test-id")));
        assertThat(dto.clinicalStatus().coding().getFirst().system()).isEqualTo("http://terminology.hl7.org/CodeSystem/condition-clinical");
        assertThat(dto.clinicalStatus().coding().getFirst().code()).isEqualTo("active");
        assertThat(dto.clinicalStatus().coding().getFirst().display()).isEqualTo("Active");
        assertThat(dto.verificationStatus().coding().getFirst().code()).isEqualTo("confirmed");
        assertThat(dto.code().coding()).containsExactly(
                new ObservationDto.Coding("urn:synthetic:diagnosis", "1", "test", "Example", false),
                new ObservationDto.Coding("urn:synthetic:second", null, "other", null, null));
        assertThat(dto.category()).extracting(Concept::text).containsExactly("First category", "Second category");
        assertThat(dto.severity().text()).isEqualTo("Synthetic severity");
        condition.getCategory().clear();
        condition.getCode().getCodingFirstRep().setCode("changed");
        assertThat(dto.category()).hasSize(2);
        assertThat(dto.code().coding().getFirst().code()).isEqualTo("test");
        assertThatThrownBy(() -> dto.category().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void preservesMissingOptionalFieldsWithoutInventedDefaults() {
        var dto = mapper.toDto(parse(""));
        assertThat(dto.id()).isNull();
        assertThat(dto.code()).isNull();
        assertThat(dto.clinicalStatus()).isNull();
        assertThat(dto.verificationStatus()).isNull();
        assertThat(dto.category()).isEmpty();
        assertThat(dto.severity()).isNull();
        assertThat(dto.onset()).isNull();
        assertThat(dto.abatement()).isNull();
    }

    @Test
    void rejectsMissingRequiredSubjectAndNullResource() {
        assertThatThrownBy(() -> mapper.toDto(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.toDto(new Condition())).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"onset", "abatement"})
    void rejectsUnexpectedTemporalTypesRatherThanFlatteningThem(String field) {
        Condition condition = parse("");
        // Generated HAPI setters already reject illegal choices. Simulate a malformed in-memory resource.
        ReflectionTestUtils.setField(condition, field, new BooleanType(false));
        assertThatThrownBy(() -> mapper.toDto(condition)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Condition temporal type is not supported by this projection");
    }
}
