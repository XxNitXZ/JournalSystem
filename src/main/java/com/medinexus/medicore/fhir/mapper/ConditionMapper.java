package com.medinexus.medicore.fhir.mapper;

import org.hl7.fhir.r4.model.Age;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Range;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Type;
import org.springframework.stereotype.Component;

import com.medinexus.medicore.medicalrecord.dto.ConditionDto;
import com.medinexus.medicore.medicalrecord.dto.ConditionDto.TemporalType;
import com.medinexus.medicore.medicalrecord.dto.ConditionDto.TemporalValue;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.QuantityValue;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.SubjectReference;

@Component
public class ConditionMapper {
    public ConditionDto toDto(Condition condition) {
        if (condition == null || !condition.hasSubject()) {
            throw new IllegalArgumentException("Condition subject is required");
        }
        return new ConditionDto(condition.getIdElement().getIdPart(), subject(condition.getSubject()),
                condition.hasCode() ? concept(condition.getCode()) : null,
                condition.hasClinicalStatus() ? concept(condition.getClinicalStatus()) : null,
                condition.hasVerificationStatus() ? concept(condition.getVerificationStatus()) : null,
                condition.getCategory().stream().map(this::concept).toList(),
                condition.hasSeverity() ? concept(condition.getSeverity()) : null,
                condition.hasOnset() ? temporal(condition.getOnset()) : null,
                condition.hasAbatement() ? temporal(condition.getAbatement()) : null);
    }

    private SubjectReference subject(Reference reference) {
        return new SubjectReference(reference.getReference(), reference.getType(), reference.getDisplay(),
                reference.hasIdentifier() ? new ObservationDto.Identifier(reference.getIdentifier().getSystem(),
                        reference.getIdentifier().getValue()) : null);
    }

    private Concept concept(CodeableConcept source) {
        return new Concept(source.getText(), source.getCoding().stream().map(coding -> new ObservationDto.Coding(
                coding.getSystem(), coding.getVersion(), coding.getCode(), coding.getDisplay(),
                coding.hasUserSelected() ? coding.getUserSelected() : null)).toList());
    }

    private QuantityValue quantity(Quantity quantity) {
        return new QuantityValue(quantity.getValue(), quantity.hasComparator() ? quantity.getComparator().toCode() : null,
                quantity.getUnit(), quantity.getSystem(), quantity.getCode());
    }

    private TemporalValue temporal(Type value) {
        if (value instanceof DateTimeType date) return new TemporalValue(TemporalType.DATE_TIME,
                new ConditionDto.TextValue(date.getValueAsString()));
        if (value instanceof Age age) return new TemporalValue(TemporalType.AGE, new ConditionDto.AgeValue(quantity(age)));
        if (value instanceof Period period) return new TemporalValue(TemporalType.PERIOD,
                new ConditionDto.PeriodValue(period.hasStart() ? period.getStartElement().getValueAsString() : null,
                        period.hasEnd() ? period.getEndElement().getValueAsString() : null));
        if (value instanceof Range range) return new TemporalValue(TemporalType.RANGE,
                new ConditionDto.RangeValue(range.hasLow() ? quantity(range.getLow()) : null,
                        range.hasHigh() ? quantity(range.getHigh()) : null));
        if (value instanceof StringType text) return new TemporalValue(TemporalType.STRING, new ConditionDto.TextValue(text.getValue()));
        throw new IllegalArgumentException("Condition temporal type is not supported by this projection");
    }
}
