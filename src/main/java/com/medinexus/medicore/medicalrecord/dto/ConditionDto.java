package com.medinexus.medicore.medicalrecord.dto;

import java.util.List;

import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.QuantityValue;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.SubjectReference;

/** Read projection, not a full FHIR profile validator. No defaults are invented for optional statuses/code. */
public record ConditionDto(String id, SubjectReference subject, Concept code, Concept clinicalStatus,
        Concept verificationStatus, List<Concept> category, Concept severity,
        TemporalValue onset, TemporalValue abatement) {
    public ConditionDto {
        category = List.copyOf(category);
    }

    public enum TemporalType { DATE_TIME, AGE, PERIOD, RANGE, STRING }

    public record TemporalValue(TemporalType type, TemporalData data) {
    }

    public sealed interface TemporalData permits TextValue, AgeValue, PeriodValue, RangeValue {
    }

    /** Used for lexical dateTime and string values, distinguished by TemporalType. */
    public record TextValue(String value) implements TemporalData {
    }

    public record AgeValue(QuantityValue quantity) implements TemporalData {
    }

    public record PeriodValue(String start, String end) implements TemporalData {
    }

    public record RangeValue(QuantityValue low, QuantityValue high) implements TemporalData {
    }
}
