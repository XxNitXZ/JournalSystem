package com.medinexus.medicore.fhir.client;

/** A patient search matched more Patients than MediCore returns; the search must be narrowed. */
public class TooManyPatientsException extends RuntimeException {

    public TooManyPatientsException() {
        super("Too many Patients match the search");
    }
}
