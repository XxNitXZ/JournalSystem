package com.medinexus.medicore.fhir.client;

import org.hl7.fhir.r4.model.Practitioner;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.rest.client.api.IGenericClient;

@Component
public class FhirPractitionerClient {

    private final IGenericClient fhirClient;
    private final FhirRequestExecutor requestExecutor;

    public FhirPractitionerClient(IGenericClient fhirClient, FhirRequestExecutor requestExecutor) {
        this.fhirClient = fhirClient;
        this.requestExecutor = requestExecutor;
    }

    /** Reads GET [base]/Practitioner/[id]. A missing resource remains a translated HTTP 404. */
    public Practitioner readPractitioner(String id) {
        if (id == null || !id.matches("[A-Za-z0-9.-]{1,64}")) {
            throw new IllegalArgumentException("A valid logical FHIR Practitioner ID is required");
        }
        return requestExecutor.execute("practitioner-read", () -> fhirClient.read()
                .resource(Practitioner.class).withId(id).execute());
    }
}
