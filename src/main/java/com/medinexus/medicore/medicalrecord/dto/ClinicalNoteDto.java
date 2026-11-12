package com.medinexus.medicore.medicalrecord.dto;

/**
 * One clinical note as shown in a patient record (US-05.01): readable values only, never the raw DocumentReference
 * or its base64 data. IDs are for the frontend's own use (list keys, the selected visit), not for display.
 *
 * @param id               the DocumentReference's logical ID
 * @param patientId        the Patient the note belongs to (the requested, authorized Patient)
 * @param encounter        the visit the note belongs to, or null if it is not one of this Patient's visits or could
 *                         not be read
 * @param author           the author's (Practitioner's) name, or null if it is not recorded or cannot be read
 * @param createdAt        {@code DocumentReference.date} as stored (ISO 8601), otherwise the last update, or null
 * @param status           the FHIR R4 status code, e.g. {@code current}
 * @param type             the note type's text, e.g. "Clinical note", or null
 * @param content          the decoded plain text, or null when {@code contentAvailable} is false
 * @param contentAvailable false if the note has no inline UTF-8 plain-text content that MediCore can show
 */
public record ClinicalNoteDto(String id, String patientId, EncounterContext encounter, String author,
        String createdAt, String status, String type, String content, boolean contentAvailable) {

    /** The visit of a note: its ID (for selection), start as stored and readable visit type. */
    public record EncounterContext(String id, String start, String type) {
    }
}
