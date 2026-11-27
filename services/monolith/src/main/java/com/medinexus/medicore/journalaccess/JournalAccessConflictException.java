package com.medinexus.medicore.journalaccess;

/**
 * A review action cannot be carried out in the request's current state. The code is safe to return to the client
 * and never contains patient data: {@code REQUEST_NOT_PENDING}, {@code REQUEST_NOT_REJECTED},
 * {@code ALREADY_PENDING}, {@code ACCOUNT_NOT_ELIGIBLE}, {@code LINK_CONFLICT}, {@code PERSONNUMMER_MISMATCH},
 * {@code FHIR_PATIENT_MISSING}.
 */
public class JournalAccessConflictException extends RuntimeException {

    private final String code;

    public JournalAccessConflictException(String code) {
        super("Journal access request conflict: " + code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
