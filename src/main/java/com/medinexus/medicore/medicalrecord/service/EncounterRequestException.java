package com.medinexus.medicore.medicalrecord.service;

/**
 * A rejected Encounter request with a machine-readable code and no submitted values. 400: {@code INVALID_REQUEST},
 * {@code START_TIME_INVALID}, {@code START_TIME_IN_FUTURE}, {@code TYPE_INVALID}, {@code STATUS_INVALID},
 * {@code PRACTITIONER_REQUIRED}, {@code PRACTITIONER_NOT_AVAILABLE}, {@code ORGANIZATION_INVALID},
 * {@code LOCATION_INVALID}. 422: {@code PRACTITIONER_NOT_FOUND}, {@code ORGANIZATION_NOT_FOUND},
 * {@code LOCATION_NOT_FOUND} (referenced resource does not exist in FHIR). 404: {@code RECORD_NOT_LINKED} (a PATIENT
 * without an approved record).
 */
public class EncounterRequestException extends RuntimeException {

    private final String code;

    public EncounterRequestException(String code) {
        super("Encounter request rejected: " + code);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** The HTTP status for this code. */
    public int status() {
        return switch (code) {
            case "PRACTITIONER_NOT_FOUND", "ORGANIZATION_NOT_FOUND", "LOCATION_NOT_FOUND" -> 422;
            case "RECORD_NOT_LINKED" -> 404;
            default -> 400;
        };
    }
}
