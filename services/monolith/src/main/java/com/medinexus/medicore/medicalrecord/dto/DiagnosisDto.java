package com.medinexus.medicore.medicalrecord.dto;

import com.medinexus.medicore.medicalrecord.dto.ClinicalNoteDto.EncounterContext;

/**
 * One diagnosis (FHIR Condition) as shown in a patient record (US-05.02): readable values only, never the raw
 * Condition. Conditions created outside MediCore (for example on imported Patients) may lack any of the optional
 * values, which are then null. IDs are for the frontend's own use (list keys), not for display.
 *
 * @param id                 the Condition's logical ID
 * @param patientId          the Patient the diagnosis belongs to (the requested, authorized Patient)
 * @param diagnosis          {@code code.text}, otherwise the first coding's display, or null
 * @param code               the first coding with a code, or null
 * @param clinicalStatus     the R4 clinical status code (e.g. {@code active}) or its text, or null
 * @param verificationStatus the R4 verification status code (e.g. {@code confirmed}) or its text, or null
 * @param category           the first category code (e.g. {@code encounter-diagnosis}) or its text, or null
 * @param recordedDate       {@code recordedDate} as stored, or null
 * @param onset              the onset as text (a date as stored, the start of a period, an age or range), or null
 * @param encounter          the visit, only if it is one of this Patient's Encounters, otherwise null
 * @param recorder           the recorder's (Practitioner's) name, or null
 */
public record DiagnosisDto(String id, String patientId, String diagnosis, Code code, String clinicalStatus,
        String verificationStatus, String category, String recordedDate, String onset, EncounterContext encounter,
        String recorder) {

    /** A diagnosis code: system URI, code, the system's readable name when known, and the coding's display. */
    public record Code(String system, String systemName, String code, String display) {
    }
}
