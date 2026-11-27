package com.medinexus.medicore.fhir.client;

/**
 * A conditional Patient create matched one or more existing Patients with the same personnummer, so no new
 * Patient was created. Carries neither the personnummer nor the ID of the existing Patient.
 */
public class DuplicatePatientException extends RuntimeException {

    public DuplicatePatientException() {
        super("A Patient with the same personnummer already exists");
    }
}
