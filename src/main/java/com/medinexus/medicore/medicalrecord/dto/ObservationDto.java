package com.medinexus.medicore.medicalrecord.dto;

import java.math.BigDecimal;
import java.util.List;

/** A read projection, not a complete FHIR resource or clinical profile validator. */
public record ObservationDto(String id, String status, SubjectReference subject, Concept code,
        ObservationValue value, Concept dataAbsentReason) {

    public record SubjectReference(String reference, String type, String display, Identifier identifier) {
    }

    public record Identifier(String system, String value) {
    }

    public record Coding(String system, String version, String code, String display, Boolean userSelected) {
    }

    public record Concept(String text, List<Coding> coding) implements ValueData {
        public Concept {
            coding = List.copyOf(coding);
        }
    }

    public enum ValueType {
        QUANTITY, CODEABLE_CONCEPT, STRING, BOOLEAN, INTEGER, RANGE, RATIO, TIME, DATE_TIME, PERIOD
    }

    /** Explicit discriminator prevents a missing/false/zero value from being conflated. */
    public record ObservationValue(ValueType type, ValueData data) {
    }

    public sealed interface ValueData permits Concept, QuantityValue, StringValue, BooleanValue,
            IntegerValue, RangeValue, RatioValue, PeriodValue {
    }

    public record QuantityValue(BigDecimal value, String comparator, String unit, String system, String code)
            implements ValueData {
    }

    public record StringValue(String value) implements ValueData {
    }

    public record BooleanValue(Boolean value) implements ValueData {
    }

    public record IntegerValue(Integer value) implements ValueData {
    }

    public record RangeValue(QuantityValue low, QuantityValue high) implements ValueData {
    }

    public record RatioValue(QuantityValue numerator, QuantityValue denominator) implements ValueData {
    }

    /** Lexical dates retain precision and offsets; no timezone conversion is performed. */
    public record PeriodValue(String start, String end) implements ValueData {
    }
}
