package com.medinexus.medicore.patient.service;

/**
 * A rejected import request, with a machine-readable code and no submitted values: {@code INVALID_REQUEST},
 * {@code SEARCH_INVALID} (400), {@code ALREADY_REGISTERED} (409) and {@code PATIENT_NOT_IMPORTABLE} (422).
 */
public class PatientImportException extends RuntimeException {

    private final String code;

    public PatientImportException(String code) {
        super("Patient import rejected: " + code);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public int status() {
        return switch (code) {
            case "ALREADY_REGISTERED" -> 409;
            case "PATIENT_NOT_IMPORTABLE" -> 422;
            default -> 400;
        };
    }
}
