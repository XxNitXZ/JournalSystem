package com.medinexus.medicore.patient.service;

/**
 * The Patient was changed after the client read it (its FHIR version differs from the {@code If-Match} version), or
 * the FHIR server rejected the update because of a concurrent change. Nothing was overwritten. Carries no data.
 */
public class PatientVersionConflictException extends RuntimeException {

    public PatientVersionConflictException() {
        super("The patient was changed by someone else");
    }
}
