package com.medinexus.medicore.patient.service;

/**
 * An update without the version of the Patient that the client read ({@code If-Match}). Updates are only accepted
 * against a known version, so that concurrent changes are never overwritten silently.
 */
public class PatientVersionRequiredException extends RuntimeException {

    public PatientVersionRequiredException() {
        super("The version of the patient record is required");
    }
}
