package com.medinexus.medicore.patient.service;

/**
 * The Patient was created in HAPI FHIR, but MediCore could not record it in its local registry, so it is not yet
 * searchable or viewable in MediCore. Registering it again is rejected as a duplicate; an operator must add the
 * logged FHIR ID to {@code registered_patients}. Carries no patient data.
 */
public class PatientRegistryException extends RuntimeException {

    public PatientRegistryException(Throwable cause) {
        super("The Patient was created but could not be recorded in MediCore", cause);
    }
}
