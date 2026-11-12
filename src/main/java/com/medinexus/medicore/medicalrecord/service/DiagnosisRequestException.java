package com.medinexus.medicore.medicalrecord.service;

/**
 * A rejected diagnosis request with a machine-readable code and no submitted values (US-05.02).
 * <ul>
 *   <li>400: {@code INVALID_REQUEST}, {@code DIAGNOSIS_REQUIRED}, {@code DIAGNOSIS_TOO_LONG},
 *       {@code DIAGNOSIS_INVALID}, {@code CODE_SYSTEM_REQUIRED} (a code without system), {@code CODE_REQUIRED} (a
 *       system without code), {@code CODE_SYSTEM_INVALID}, {@code CODE_INVALID}, {@code CLINICAL_STATUS_INVALID},
 *       {@code VERIFICATION_STATUS_INVALID}, {@code ONSET_INVALID}, {@code ONSET_IN_FUTURE},
 *       {@code ENCOUNTER_INVALID};</li>
 *   <li>422: {@code ENCOUNTER_NOT_FOUND}, {@code ENCOUNTER_NOT_FOR_PATIENT}, {@code PRACTITIONER_NOT_LINKED} (the
 *       clinician's account has no Practitioner to record the diagnosis), {@code PRACTITIONER_NOT_FOUND};</li>
 *   <li>404: {@code RECORD_NOT_LINKED} (a PATIENT without an approved record).</li>
 * </ul>
 */
public class DiagnosisRequestException extends RuntimeException {

    private final String code;

    public DiagnosisRequestException(String code) {
        super("Diagnosis request rejected: " + code);
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
