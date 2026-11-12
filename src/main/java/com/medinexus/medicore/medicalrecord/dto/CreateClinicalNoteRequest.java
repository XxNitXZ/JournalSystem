package com.medinexus.medicore.medicalrecord.dto;

import java.util.Map;
import java.util.Set;

import com.medinexus.medicore.medicalrecord.service.ClinicalNoteException;

/**
 * Body of {@code POST /api/patients/{patientId}/notes} (US-05.01). The Patient comes from the path and the author
 * from the signed-in clinician's linked Practitioner, never from the body: any other property (such as
 * {@code patientId}, {@code author} or {@code practitionerId}) or a non-text value is rejected. The content is kept
 * exactly as sent; {@code ClinicalNoteService} validates and normalizes it.
 *
 * @param encounterId the visit (an Encounter of this Patient) the note belongs to
 * @param content     the note text
 */
public record CreateClinicalNoteRequest(String encounterId, String content) {

    private static final Set<String> FIELDS = Set.of("encounterId", "content");

    public static CreateClinicalNoteRequest from(Map<String, Object> body) {
        if (body == null || !FIELDS.containsAll(body.keySet())) throw new ClinicalNoteException("INVALID_REQUEST");
        Object encounterId = body.get("encounterId");
        Object content = body.get("content");
        if (encounterId != null && !(encounterId instanceof String) || content != null && !(content instanceof String)) {
            throw new ClinicalNoteException("INVALID_REQUEST");
        }
        String encounter = (String) encounterId;
        return new CreateClinicalNoteRequest(encounter == null || encounter.isBlank() ? null : encounter.strip(),
                (String) content);
    }
}
