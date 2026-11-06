package com.medinexus.medicore.medicalrecord.dto;

import java.math.BigDecimal;

import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;

/** Internal create input: no caller-selected Observation ID or arbitrary subject URL. */
public record CreateObservationRequest(String patientId, Status status, Concept code,
        Value value, Concept dataAbsentReason) {

    public enum Status {
        REGISTERED, PRELIMINARY, FINAL, AMENDED, CORRECTED, CANCELLED, ENTERED_IN_ERROR, UNKNOWN
    }

    public sealed interface Value permits QuantityValue, CodedValue {
    }

    public record QuantityValue(BigDecimal value, String unit, String system, String code, Comparator comparator)
            implements Value {
    }

    public record CodedValue(Concept concept) implements Value {
    }

    public enum Comparator {
        LESS_THAN("<"), LESS_OR_EQUAL("<="), GREATER_OR_EQUAL(">="), GREATER_THAN(">");

        private final String code;

        Comparator(String code) { this.code = code; }

        public String code() { return code; }
    }
}
