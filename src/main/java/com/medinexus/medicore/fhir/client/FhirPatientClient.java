package com.medinexus.medicore.fhir.client;

import org.hl7.fhir.r4.model.Patient;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.rest.client.api.IGenericClient;

@Component
public class FhirPatientClient {

    private final IGenericClient fhirClient;
    private final FhirRequestExecutor requestExecutor;

    public FhirPatientClient(IGenericClient fhirClient, FhirRequestExecutor requestExecutor) {
        this.fhirClient = fhirClient;
        this.requestExecutor = requestExecutor;
    }

    /** Reads GET [base]/Patient/[id]. A missing resource remains a translated HTTP 404. */
    public Patient readPatient(String id) {
        if (id == null || !id.matches("[A-Za-z0-9.-]{1,64}")) {
            throw new IllegalArgumentException("A valid logical FHIR Patient ID is required");
        }
        return requestExecutor.execute("patient-read", () -> fhirClient.read()
                .resource(Patient.class).withId(id).execute());
    }
}
