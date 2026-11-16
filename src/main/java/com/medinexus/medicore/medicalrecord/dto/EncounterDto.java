package com.medinexus.medicore.medicalrecord.dto;

import java.util.List;

import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Coding;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.PeriodValue;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.QuantityValue;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.SubjectReference;

/**
 * Read projection of an R4 Encounter, not a complete FHIR resource or profile validator.
 * All references (subject, participants, reasons, diagnoses, locations, service provider)
 * are preserved verbatim; they are neither resolved nor reinterpreted. No defaults are
 * invented for optional fields; dates keep their lexical precision.
 */
public record EncounterDto(String id, String status, Coding encounterClass, List<Concept> types,
        Concept serviceType, Concept priority, SubjectReference subject, List<Participant> participants,
        PeriodValue period, QuantityValue length, List<Concept> reasonCodes,
        List<SubjectReference> reasonReferences, List<Diagnosis> diagnoses, List<LocationEntry> locations,
        SubjectReference serviceProvider) {

    public EncounterDto {
        types = List.copyOf(types);
        participants = List.copyOf(participants);
        reasonCodes = List.copyOf(reasonCodes);
        reasonReferences = List.copyOf(reasonReferences);
        diagnoses = List.copyOf(diagnoses);
        locations = List.copyOf(locations);
    }

    /** Encounter.participant; individual may be Practitioner, PractitionerRole or RelatedPerson. */
    public record Participant(List<Concept> types, PeriodValue period, SubjectReference individual) {
        public Participant {
            types = List.copyOf(types);
        }
    }

    /** Encounter.diagnosis; condition references a Condition or Procedure. */
    public record Diagnosis(SubjectReference condition, Concept use, Integer rank) {
    }

    /** Encounter.location; the Location itself is not resolved here. */
    public record LocationEntry(SubjectReference location, String status, Concept physicalType, PeriodValue period) {
    }
}
