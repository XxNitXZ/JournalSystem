package com.medinexus.medicore.patient.service;

/** The FHIR server has no Patient with the requested ID (HTTP 404 or 410). Carries neither the ID nor any data. */
public class PatientNotFoundException extends RuntimeException {

    public PatientNotFoundException() {
        super("Patient not found");
    }
}
