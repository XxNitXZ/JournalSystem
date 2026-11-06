package com.medinexus.medicore.fhir.mapper;

import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Range;
import org.hl7.fhir.r4.model.Ratio;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.TimeType;
import org.hl7.fhir.r4.model.Type;
import org.springframework.stereotype.Component;

import com.medinexus.medicore.medicalrecord.dto.ObservationDto;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.ObservationValue;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.QuantityValue;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.ValueType;

@Component
public class ObservationMapper {

    public ObservationDto toDto(Observation observation) {
        if (observation == null || !observation.hasStatus() || !observation.hasCode()) {
            throw new IllegalArgumentException("Observation status and code are required");
        }
        if (observation.hasComponent()) {
            throw new IllegalArgumentException("Observation components are not supported by this projection");
        }
        if (observation.hasValue() && observation.hasDataAbsentReason()) {
            throw new IllegalArgumentException("Observation value and dataAbsentReason cannot both be present");
        }
        return new ObservationDto(observation.getIdElement().getIdPart(), observation.getStatus().toCode(),
                observation.hasSubject() ? subject(observation.getSubject()) : null,
                concept(observation.getCode()), observation.hasValue() ? value(observation.getValue()) : null,
                observation.hasDataAbsentReason() ? concept(observation.getDataAbsentReason()) : null);
    }

    private ObservationDto.SubjectReference subject(Reference reference) {
        // Preserve the reference verbatim: do not turn Group/Device/contained references into Patient IDs.
        return new ObservationDto.SubjectReference(reference.getReference(), reference.getType(), reference.getDisplay(),
                reference.hasIdentifier() ? new ObservationDto.Identifier(reference.getIdentifier().getSystem(),
                        reference.getIdentifier().getValue()) : null);
    }

    private Concept concept(CodeableConcept source) {
        return new Concept(source.getText(), source.getCoding().stream().map(coding -> new ObservationDto.Coding(
                coding.getSystem(), coding.getVersion(), coding.getCode(), coding.getDisplay(),
                coding.hasUserSelected() ? coding.getUserSelected() : null)).toList());
    }

    private QuantityValue quantity(Quantity quantity) {
        return new QuantityValue(quantity.getValue(),
                quantity.hasComparator() ? quantity.getComparator().toCode() : null,
                quantity.getUnit(), quantity.getSystem(), quantity.getCode());
    }

    private ObservationValue value(Type value) {
        if (value instanceof Quantity q) return new ObservationValue(ValueType.QUANTITY, quantity(q));
        if (value instanceof CodeableConcept c) return new ObservationValue(ValueType.CODEABLE_CONCEPT, concept(c));
        if (value instanceof StringType s) return new ObservationValue(ValueType.STRING, new ObservationDto.StringValue(s.getValue()));
        if (value instanceof BooleanType b) return new ObservationValue(ValueType.BOOLEAN, new ObservationDto.BooleanValue(b.getValue()));
        if (value instanceof IntegerType i) return new ObservationValue(ValueType.INTEGER, new ObservationDto.IntegerValue(i.getValue()));
        if (value instanceof Range r) return new ObservationValue(ValueType.RANGE,
                new ObservationDto.RangeValue(r.hasLow() ? quantity(r.getLow()) : null, r.hasHigh() ? quantity(r.getHigh()) : null));
        if (value instanceof Ratio r) return new ObservationValue(ValueType.RATIO,
                new ObservationDto.RatioValue(r.hasNumerator() ? quantity(r.getNumerator()) : null,
                        r.hasDenominator() ? quantity(r.getDenominator()) : null));
        if (value instanceof TimeType t) return new ObservationValue(ValueType.TIME, new ObservationDto.StringValue(t.getValueAsString()));
        if (value instanceof DateTimeType d) return new ObservationValue(ValueType.DATE_TIME, new ObservationDto.StringValue(d.getValueAsString()));
        if (value instanceof Period p) return new ObservationValue(ValueType.PERIOD,
                new ObservationDto.PeriodValue(p.hasStart() ? p.getStartElement().getValueAsString() : null,
                        p.hasEnd() ? p.getEndElement().getValueAsString() : null));
        throw new IllegalArgumentException("Observation value type is not supported by this projection");
    }
}
