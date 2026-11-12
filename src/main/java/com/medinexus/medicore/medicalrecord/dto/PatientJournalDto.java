package com.medinexus.medicore.medicalrecord.dto;

import java.util.List;
import java.util.Objects;

/** Read-only composition of authorized record searches, not an atomic FHIR snapshot. */
public record PatientJournalDto(List<EncounterSummaryDto> encounters, List<DiagnosisDto> diagnoses,
        List<ClinicalNoteDto> clinicalNotes, ObservationSearchResult observations) {
    public PatientJournalDto {
        encounters = List.copyOf(encounters);
        diagnoses = List.copyOf(diagnoses);
        clinicalNotes = List.copyOf(clinicalNotes);
        Objects.requireNonNull(observations);
    }
}
