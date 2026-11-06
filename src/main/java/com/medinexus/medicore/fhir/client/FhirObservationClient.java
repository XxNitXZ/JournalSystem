package com.medinexus.medicore.fhir.client;

import org.hl7.fhir.r4.model.Observation;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.rest.client.api.IGenericClient;

@Component
public class FhirObservationClient {

    private final IGenericClient fhirClient;
    private final FhirRequestExecutor requestExecutor;

    public FhirObservationClient(IGenericClient fhirClient, FhirRequestExecutor requestExecutor) {
        this.fhirClient = fhirClient;
        this.requestExecutor = requestExecutor;
    }

    /** Reads GET [base]/Observation/[id]. A missing resource remains a translated HTTP 404. */
    public Observation readObservation(String id) {
        if (id == null || !id.matches("[A-Za-z0-9.-]{1,64}")) {
            throw new IllegalArgumentException("A valid logical FHIR Observation ID is required");
        }
        return requestExecutor.execute("observation-read", () -> fhirClient.read()
                .resource(Observation.class).withId(id).execute());
    }
}
