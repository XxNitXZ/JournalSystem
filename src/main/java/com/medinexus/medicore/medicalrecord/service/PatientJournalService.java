package com.medinexus.medicore.medicalrecord.service;

import org.springframework.stereotype.Service;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.medicalrecord.dto.PatientJournalDto;

/** Delegates identity resolution and authorization to the existing record services. */
@Service
public class PatientJournalService {
    private final EncounterService encounters;
    private final DiagnosisService diagnoses;
    private final ClinicalNoteService clinicalNotes;
    private final ObservationService observations;

    public PatientJournalService(EncounterService encounters, DiagnosisService diagnoses,
            ClinicalNoteService clinicalNotes, ObservationService observations) {
        this.encounters = encounters;
        this.diagnoses = diagnoses;
        this.clinicalNotes = clinicalNotes;
        this.observations = observations;
    }

    public PatientJournalDto listOwn(UserDto principal) {
        return new PatientJournalDto(encounters.listOwn(principal), diagnoses.listOwn(principal),
                clinicalNotes.listOwn(principal), observations.listOwn(principal));
    }

    public PatientJournalDto listForPatient(UserDto principal, String patientId) {
        return new PatientJournalDto(encounters.listForPatient(principal, patientId),
                diagnoses.listForPatient(principal, patientId), clinicalNotes.listForPatient(principal, patientId),
                observations.listForPatient(principal, patientId));
    }
}
