package com.medinexus.medicore.patient.service;

/**
 * The signed-in PATIENT has no readable own record: {@code RECORD_NOT_LINKED} (no approved link yet) or
 * {@code RECORD_NOT_FOUND} (the linked Patient no longer exists in FHIR). Carries no patient data.
 */
public class OwnPatientRecordException extends RuntimeException {

    private final String code;

    public OwnPatientRecordException(String code) {
        super("Own patient record unavailable: " + code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
