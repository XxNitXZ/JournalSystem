package com.medinexus.medicore.fhir.mapper;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.stereotype.Component;

import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest.EncounterClass;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest.ParticipantType;
import com.medinexus.medicore.medicalrecord.dto.EncounterDto;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.PeriodValue;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.QuantityValue;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.SubjectReference;

@Component
public class EncounterMapper {

    public EncounterDto toDto(Encounter encounter) {
        if (encounter == null || !encounter.hasStatus() || encounter.getStatus() == null
                || encounter.getStatus() == Encounter.EncounterStatus.NULL || !encounter.hasClass_()) {
            throw new IllegalArgumentException("Encounter status and class are required");
        }
        return new EncounterDto(encounter.getIdElement().getIdPart(), encounter.getStatus().toCode(),
                coding(encounter.getClass_()),
                encounter.getType().stream().map(this::concept).toList(),
                encounter.hasServiceType() ? concept(encounter.getServiceType()) : null,
                encounter.hasPriority() ? concept(encounter.getPriority()) : null,
                encounter.hasSubject() ? reference(encounter.getSubject()) : null,
                encounter.getParticipant().stream().map(this::participant).toList(),
                encounter.hasPeriod() ? period(encounter.getPeriod()) : null,
                encounter.hasLength() ? quantity(encounter.getLength()) : null,
                encounter.getReasonCode().stream().map(this::concept).toList(),
                encounter.getReasonReference().stream().map(this::reference).toList(),
                encounter.getDiagnosis().stream().map(this::diagnosis).toList(),
                encounter.getLocation().stream().map(this::location).toList(),
                encounter.hasServiceProvider() ? reference(encounter.getServiceProvider()) : null);
    }

    private EncounterDto.Participant participant(Encounter.EncounterParticipantComponent participant) {
        // Preserve the individual verbatim: PractitionerRole/RelatedPerson are not turned into Practitioner IDs.
        return new EncounterDto.Participant(participant.getType().stream().map(this::concept).toList(),
                participant.hasPeriod() ? period(participant.getPeriod()) : null,
                participant.hasIndividual() ? reference(participant.getIndividual()) : null);
    }

    private EncounterDto.Diagnosis diagnosis(Encounter.DiagnosisComponent diagnosis) {
        if (!diagnosis.hasCondition()) {
            throw new IllegalArgumentException("Encounter diagnosis condition is required");
        }
        return new EncounterDto.Diagnosis(reference(diagnosis.getCondition()),
                diagnosis.hasUse() ? concept(diagnosis.getUse()) : null,
                diagnosis.hasRank() ? diagnosis.getRank() : null);
    }

    private EncounterDto.LocationEntry location(Encounter.EncounterLocationComponent location) {
        if (!location.hasLocation()) {
            throw new IllegalArgumentException("Encounter location reference is required");
        }
        Encounter.EncounterLocationStatus status = location.getStatus();
        return new EncounterDto.LocationEntry(reference(location.getLocation()),
                status == null || status == Encounter.EncounterLocationStatus.NULL ? null : status.toCode(),
                location.hasPhysicalType() ? concept(location.getPhysicalType()) : null,
                location.hasPeriod() ? period(location.getPeriod()) : null);
    }

    private SubjectReference reference(Reference reference) {
        return new SubjectReference(reference.getReference(), reference.getType(), reference.getDisplay(),
                reference.hasIdentifier() ? new ObservationDto.Identifier(reference.getIdentifier().getSystem(),
                        reference.getIdentifier().getValue()) : null);
    }

    private ObservationDto.Coding coding(Coding coding) {
        return new ObservationDto.Coding(coding.getSystem(), coding.getVersion(), coding.getCode(), coding.getDisplay(),
                coding.hasUserSelected() ? coding.getUserSelected() : null);
    }

    private Concept concept(CodeableConcept source) {
        return new Concept(source.getText(), source.getCoding().stream().map(this::coding).toList());
    }

    private QuantityValue quantity(Quantity quantity) {
        return new QuantityValue(quantity.getValue(), quantity.hasComparator() ? quantity.getComparator().toCode() : null,
                quantity.getUnit(), quantity.getSystem(), quantity.getCode());
    }

    private PeriodValue period(Period period) {
        return new PeriodValue(period.hasStart() ? period.getStartElement().getValueAsString() : null,
                period.hasEnd() ? period.getEndElement().getValueAsString() : null);
    }

    /** Validates the entire create input before the client starts an HTTP request. */
    public Encounter toResource(CreateEncounterRequest request) {
        if (request == null || request.patientId() == null || !request.patientId().matches("[A-Za-z0-9.-]{1,64}")
                || request.status() == null || request.encounterClass() == null) throw invalid();

        Encounter resource = new Encounter();
        resource.setStatus(Encounter.EncounterStatus.fromCode(request.status().code()));
        resource.setClass_(new Coding(EncounterClass.SYSTEM, request.encounterClass().code(), null));
        resource.setSubject(new Reference("Patient/" + request.patientId()));
        addParticipants(resource, request.participants());
        request.type().forEach(type -> resource.addType(createConcept(type)));
        if (request.serviceType() != null) resource.setServiceType(createConcept(request.serviceType()));
        if (request.priority() != null) resource.setPriority(createConcept(request.priority()));
        if (request.periodStart() != null || request.periodEnd() != null) {
            Period period = new Period();
            if (request.periodStart() != null) period.setStartElement(dateTime(request.periodStart()));
            if (request.periodEnd() != null) period.setEndElement(dateTime(request.periodEnd()));
            requireOrdered(request.periodStart(), request.periodEnd());
            resource.setPeriod(period);
        }
        request.reasonCode().forEach(reason -> resource.addReasonCode(createConcept(reason)));
        addLocations(resource, request.locations());
        if (request.serviceProviderId() != null) {
            if (!request.serviceProviderId().matches("[A-Za-z0-9.-]{1,64}")) throw invalid();
            resource.setServiceProvider(new Reference("Organization/" + request.serviceProviderId()));
        }
        return resource;
    }

    /**
     * Encounter.location.location = Location/{id} (validated logical ID, never a caller URL) in request order,
     * with the optional Encounter.location.status. Null entries and the same Location twice are rejected.
     */
    private void addLocations(Encounter resource, List<CreateEncounterRequest.EncounterLocation> locations) {
        Set<String> locationIds = new HashSet<>();
        for (CreateEncounterRequest.EncounterLocation location : locations) {
            if (location == null || location.locationId() == null
                    || !location.locationId().matches("[A-Za-z0-9.-]{1,64}")
                    || !locationIds.add(location.locationId())) throw invalid();
            Encounter.EncounterLocationComponent component = resource.addLocation()
                    .setLocation(new Reference("Location/" + location.locationId()));
            if (location.status() != null) {
                component.setStatus(Encounter.EncounterLocationStatus.fromCode(location.status().code()));
            }
        }
    }

    /**
     * Encounter.participant.individual = Practitioner/{id} (validated logical ID, never a caller URL) and
     * Encounter.participant.type from ParticipantType. Null entries, null roles, a Practitioner listed twice
     * and a role repeated within one participant are rejected; roles belong in one participant entry.
     */
    private void addParticipants(Encounter resource, List<CreateEncounterRequest.Participant> participants) {
        Set<String> practitionerIds = new HashSet<>();
        for (CreateEncounterRequest.Participant participant : participants) {
            if (participant == null || participant.practitionerId() == null
                    || !participant.practitionerId().matches("[A-Za-z0-9.-]{1,64}")
                    || !practitionerIds.add(participant.practitionerId())
                    || participant.types().stream().anyMatch(Objects::isNull)
                    || new HashSet<>(participant.types()).size() != participant.types().size()) throw invalid();
            Encounter.EncounterParticipantComponent component = resource.addParticipant()
                    .setIndividual(new Reference("Practitioner/" + participant.practitionerId()));
            participant.types().forEach(type -> component.addType(new CodeableConcept()
                    .addCoding(new Coding(ParticipantType.SYSTEM, type.code(), null))));
        }
    }

    private CodeableConcept createConcept(Concept source) {
        if (source == null || !nonblank(source.text()) && source.coding().isEmpty()) throw invalid();
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
                if (Math.abs(instantOffsetSeconds(text)) > 14 * 3600) throw invalid();
            }
            return new DateTimeType(text);
        } catch (DateTimeParseException exception) {
            throw invalid();
        }
    }

    /**
     * FHIR per-1: start must not be after end. Only periods that are reversed under every
     * possible interpretation are rejected: values without a timezone may use any offset
     * from -14:00 to +14:00, and partial dates cover their whole year, month or day.
     */
    private void requireOrdered(String start, String end) {
        if (start == null || end == null) return;
        boolean startZoned = start.contains("T");
        boolean endZoned = end.contains("T");
        if (!startZoned && !endZoned) {
            // Both share the same unspecified local timezone.
            if (!localEndExclusive(end).isAfter(localStart(start))) throw invalid();
            return;
        }
        Instant earliestStart = startZoned ? instant(start) : localStart(start).toInstant(ZoneOffset.ofHours(14));
        boolean reversed = endZoned ? instant(end).isBefore(earliestStart)
                : !localEndExclusive(end).toInstant(ZoneOffset.ofHours(-14)).isAfter(earliestStart);
        if (reversed) throw invalid();
    }

    private Instant instant(String text) {
        return OffsetDateTime.parse(text.replaceFirst("(\\.[0-9]{9})[0-9]+", "$1")).toInstant();
    }

    private int instantOffsetSeconds(String text) {
        return OffsetDateTime.parse(text.replaceFirst("(\\.[0-9]{9})[0-9]+", "$1")).getOffset().getTotalSeconds();
    }

    private LocalDateTime localStart(String text) {
        if (text.length() == 4) return Year.parse(text).atDay(1).atStartOfDay();
        if (text.length() == 7) return YearMonth.parse(text).atDay(1).atStartOfDay();
        return LocalDate.parse(text).atStartOfDay();
    }

    private LocalDateTime localEndExclusive(String text) {
        if (text.length() == 4) return Year.parse(text).plusYears(1).atDay(1).atStartOfDay();
        if (text.length() == 7) return YearMonth.parse(text).plusMonths(1).atDay(1).atStartOfDay();
        return LocalDate.parse(text).plusDays(1).atStartOfDay();
    }

    private boolean nonblank(String value) { return value != null && !value.isBlank(); }

    private boolean absoluteUri(String value) {
        if (!nonblank(value)) return false;
        try { return URI.create(value).isAbsolute(); }
        catch (IllegalArgumentException exception) { return false; }
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid Encounter creation request");
    }
}
