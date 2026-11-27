package com.medinexus.medicore.medicalrecord.service;

/**
 * A rejected clinical note request with a machine-readable code and no submitted values (US-05.01).
 * <ul>
 *   <li>400: {@code INVALID_REQUEST}, {@code ENCOUNTER_REQUIRED}, {@code ENCOUNTER_INVALID},
 *       {@code CONTENT_REQUIRED} (missing, empty or only whitespace), {@code CONTENT_TOO_LONG},
 *       {@code CONTENT_INVALID} (control characters or text that is not valid Unicode);</li>
 *   <li>422: {@code ENCOUNTER_NOT_FOUND}, {@code ENCOUNTER_NOT_FOR_PATIENT} (the visit belongs to another Patient),
 *       {@code PRACTITIONER_NOT_LINKED} (the clinician's account has no Practitioner, so cannot be an author),
 *       {@code PRACTITIONER_NOT_FOUND} (the linked Practitioner no longer exists in FHIR);</li>
 *   <li>404: {@code RECORD_NOT_LINKED} (a PATIENT without an approved record).</li>
 * </ul>
 */
public class ClinicalNoteException extends RuntimeException {

    private final String code;

    public ClinicalNoteException(String code) {
        super("Clinical note request rejected: " + code);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** The HTTP status for this code. */
    public int status() {
        return switch (code) {
            case "ENCOUNTER_NOT_FOUND", "ENCOUNTER_NOT_FOR_PATIENT", "PRACTITIONER_NOT_LINKED",
                    "PRACTITIONER_NOT_FOUND" -> 422;
            case "RECORD_NOT_LINKED" -> 404;
            default -> 400;
        };
    }
}
