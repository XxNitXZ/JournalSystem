package com.medinexus.medicore.fhir.client;

import org.hl7.fhir.r4.model.CapabilityStatement;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.rest.client.api.IGenericClient;

/**
 * Read-only access to the external FHIR server.
 *
 * <p>Every operation runs through {@link FhirRequestExecutor}: HAPI
 * client exceptions are translated into {@link FhirServerException} and logged
 * once by the executor, with safe metadata only. Never log the original HAPI exception,
 * its message or stack trace, request URLs or response bodies, and do not
 * register HAPI's LoggingInterceptor: these can contain patient data.
 */
@Component
public class FhirServerClient {

    private final IGenericClient fhirClient;
    private final FhirRequestExecutor requestExecutor;

    public FhirServerClient(IGenericClient fhirClient, FhirRequestExecutor requestExecutor) {
        this.fhirClient = fhirClient;
        this.requestExecutor = requestExecutor;
    }

    /**
     * Requests the server's CapabilityStatement ({@code GET [base]/metadata}).
     *
     * @throws FhirServerException if the request fails
     */
    public CapabilityStatement fetchCapabilityStatement() {
        return requestExecutor.execute("capabilities", () -> fhirClient.capabilities()
                .ofType(CapabilityStatement.class)
                .execute());
    }

}
