package com.medinexus.medicore.fhir.mapper;

import java.net.URI;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Set;

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
import com.medinexus.medicore.medicalrecord.dto.CreateConditionRequest;
import com.medinexus.medicore.medicalrecord.dto.CreateConditionRequest.ClinicalStatus;
import com.medinexus.medicore.medicalrecord.dto.CreateConditionRequest.VerificationStatus;
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

    /** Validates the entire create input before the client starts an HTTP request. */
    public Condition toResource(CreateConditionRequest request) {
        if (request == null || request.patientId() == null || !request.patientId().matches("[A-Za-z0-9.-]{1,64}")) throw invalid();
        if (request.verificationStatus() == VerificationStatus.ENTERED_IN_ERROR && request.clinicalStatus() != null) throw invalid();
        if (request.abatementDateTime() != null && (request.clinicalStatus() == null
                || !Set.of(ClinicalStatus.INACTIVE, ClinicalStatus.REMISSION, ClinicalStatus.RESOLVED).contains(request.clinicalStatus()))) throw invalid();
        boolean problemList = request.category().stream().flatMap(category -> category.coding().stream()).anyMatch(coding ->
                "http://terminology.hl7.org/CodeSystem/condition-category".equals(coding.system()) && "problem-list-item".equals(coding.code()));
        if (problemList && request.verificationStatus() != VerificationStatus.ENTERED_IN_ERROR && request.clinicalStatus() == null) throw invalid();

        Condition resource = new Condition();
        resource.setSubject(new Reference("Patient/" + request.patientId()));
        resource.setCode(createConcept(request.code(), true));
        if (request.clinicalStatus() != null) resource.setClinicalStatus(new CodeableConcept().addCoding(
                new org.hl7.fhir.r4.model.Coding(ClinicalStatus.SYSTEM, request.clinicalStatus().code(), null)));
        if (request.verificationStatus() != null) resource.setVerificationStatus(new CodeableConcept().addCoding(
                new org.hl7.fhir.r4.model.Coding(VerificationStatus.SYSTEM, request.verificationStatus().code(), null)));
        request.category().forEach(category -> resource.addCategory(createConcept(category, false)));
        if (request.severity() != null) resource.setSeverity(createConcept(request.severity(), false));
        if (request.onsetDateTime() != null) resource.setOnset(dateTime(request.onsetDateTime()));
        if (request.abatementDateTime() != null) resource.setAbatement(dateTime(request.abatementDateTime()));
        return resource;
    }

    private CodeableConcept createConcept(Concept source, boolean requireCoding) {
        if (source == null || requireCoding && source.coding().isEmpty()
                || !nonblank(source.text()) && source.coding().isEmpty()) throw invalid();
        CodeableConcept result = new CodeableConcept().setText(source.text());
        for (var coding : source.coding()) {
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

    private boolean nonblank(String value) { return value != null && !value.isBlank(); }

    private boolean absoluteUri(String value) {
        if (!nonblank(value)) return false;
        try { return URI.create(value).isAbsolute(); }
        catch (IllegalArgumentException exception) { return false; }
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid Condition creation request");
    }
}
