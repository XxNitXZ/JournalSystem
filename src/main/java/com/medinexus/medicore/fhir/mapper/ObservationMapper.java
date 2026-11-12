package com.medinexus.medicore.fhir.mapper;

import java.net.URI;
import java.time.Year;
import java.time.YearMonth;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;

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
import com.medinexus.medicore.medicalrecord.dto.CreateObservationRequest;
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
        if (observation.hasValue() && observation.hasDataAbsentReason()) {
            throw new IllegalArgumentException("Observation value and dataAbsentReason cannot both be present");
        }
        return new ObservationDto(observation.getIdElement().getIdPart(), observation.getStatus().toCode(),
                observation.hasSubject() ? subject(observation.getSubject()) : null,
                concept(observation.getCode()), observation.hasValue() ? value(observation.getValue()) : null,
                observation.hasDataAbsentReason() ? concept(observation.getDataAbsentReason()) : null,
                observation.getComponent().stream().map(this::component).toList(),
                observation.hasEffective() ? effective(observation.getEffective()) : null,
                observation.hasIssued() ? observation.getIssuedElement().getValueAsString() : null);
    }


    public void validateMissingStatusCandidate(Observation observation) {
        if (observation == null || observation.hasStatus() || !observation.hasCode()
                || observation.hasValue() && observation.hasDataAbsentReason()) {
            throw new IllegalArgumentException("Observation is not a missing-status-only candidate");
        }
        concept(observation.getCode());
        if (observation.hasValue()) value(observation.getValue());
        if (observation.hasDataAbsentReason()) concept(observation.getDataAbsentReason());
        if (observation.hasEffective()) effective(observation.getEffective());
        if (observation.hasIssued()) observation.getIssuedElement().getValueAsString();
        observation.getComponent().forEach(this::component);
    }

    private ObservationValue effective(Type effective) {
        if (effective instanceof DateTimeType || effective instanceof Period) return value(effective);
        throw new IllegalArgumentException("Observation effective type is not supported by this projection");
    }

    private ObservationDto.Component component(Observation.ObservationComponentComponent component) {
        if (component == null || !component.hasCode()) {
            throw new IllegalArgumentException("Observation component code is required");
        }
        if (component.hasValue() && component.hasDataAbsentReason()) {
            throw new IllegalArgumentException("Observation component value and dataAbsentReason cannot both be present");
        }
        return new ObservationDto.Component(concept(component.getCode()),
                component.hasValue() ? value(component.getValue()) : null,
                component.hasDataAbsentReason() ? concept(component.getDataAbsentReason()) : null);
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

    /** Validates internal create input before any external request is made. */
    public Observation toResource(CreateObservationRequest request) {
        if (request == null || request.patientId() == null || !request.patientId().matches("[A-Za-z0-9.-]{1,64}")
                || request.status() == null || (request.value() == null) == (request.dataAbsentReason() == null)) {
            throw invalid();
        }
        Observation observation = new Observation();
        observation.setStatus(Observation.ObservationStatus.fromCode(
                request.status().name().toLowerCase(Locale.ROOT).replace('_', '-')));
        observation.setSubject(new Reference("Patient/" + request.patientId()));
        observation.setCode(concept(request.code()));
        if (request.effectiveDateTime() != null) observation.setEffective(dateTime(request.effectiveDateTime()));
        if (request.value() instanceof CreateObservationRequest.QuantityValue value) {
            if (value.value() == null || (value.system() == null) != (value.code() == null)
                    || value.system() != null && (!absoluteUri(value.system()) || !nonblank(value.code()))) {
                throw invalid();
            }
            Quantity quantity = new Quantity().setValue(value.value()).setUnit(value.unit())
                    .setSystem(value.system()).setCode(value.code());
            if (value.comparator() != null) quantity.setComparator(Quantity.QuantityComparator.fromCode(value.comparator().code()));
            observation.setValue(quantity);
        } else if (request.value() instanceof CreateObservationRequest.CodedValue value) {
            observation.setValue(concept(value.concept()));
        } else {
            observation.setDataAbsentReason(concept(request.dataAbsentReason()));
        }
        return observation;
    }

    private CodeableConcept concept(Concept input) {
        if (input == null || !nonblank(input.text()) && input.coding().isEmpty()) throw invalid();
        CodeableConcept result = new CodeableConcept().setText(input.text());
        for (var coding : input.coding()) {
            // Creation requires explicit code systems, rather than guessing a coding's meaning.
            if (!absoluteUri(coding.system()) || !nonblank(coding.code())) throw invalid();
            var mapped = result.addCoding().setSystem(coding.system()).setVersion(coding.version())
                    .setCode(coding.code()).setDisplay(coding.display());
            if (coding.userSelected() != null) mapped.setUserSelected(coding.userSelected());
        }
        return result;
    }

    private DateTimeType dateTime(String text) {
        try {
            if (!text.matches("[0-9]{4}.*") || text.startsWith("0000")) throw invalid();
            if (text.matches("[0-9]{4}")) Year.parse(text);
            else if (text.matches("[0-9]{4}-[0-9]{2}")) YearMonth.parse(text);
            else if (text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) LocalDate.parse(text);
            else {
                if (!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})")) throw invalid();
                // Validate calendar/offset without reducing the lexical precision stored in the FHIR value.
                String validation = text.replaceFirst("(\\.[0-9]{9})[0-9]+", "$1");
                if (Math.abs(OffsetDateTime.parse(validation).getOffset().getTotalSeconds()) > 14 * 3600) throw invalid();
            }
            return new DateTimeType(text);
        } catch (DateTimeParseException exception) {
            throw invalid();
        }
    }

    private boolean absoluteUri(String value) {
        if (!nonblank(value)) return false;
        try { return URI.create(value).isAbsolute(); }
        catch (IllegalArgumentException exception) { return false; }
    }

    private boolean nonblank(String value) { return value != null && !value.isBlank(); }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid Observation creation request");
    }
}
