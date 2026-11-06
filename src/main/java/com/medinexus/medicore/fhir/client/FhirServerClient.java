package com.medinexus.medicore.fhir.client;

import java.util.function.Supplier;

import org.hl7.fhir.r4.model.CapabilityStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;

/**
 * Read-only access to the external FHIR server.
 *
 * <p>Every operation runs through {@link #execute(String, Supplier)}: HAPI
 * client exceptions are translated into {@link FhirServerException} and logged
 * once, here, with safe metadata only. Never log the original HAPI exception,
 * its message or stack trace, request URLs or response bodies, and do not
 * register HAPI's LoggingInterceptor: these can contain patient data.
 */
@Component
public class FhirServerClient {

    private static final Logger log = LoggerFactory.getLogger(FhirServerClient.class);

    private final IGenericClient fhirClient;

    public FhirServerClient(IGenericClient fhirClient) {
        this.fhirClient = fhirClient;
    }

    /**
     * Requests the server's CapabilityStatement ({@code GET [base]/metadata}).
     *
     * @throws FhirServerException if the request fails
     */
    public CapabilityStatement fetchCapabilityStatement() {
        return execute("capabilities", () -> fhirClient.capabilities()
                .ofType(CapabilityStatement.class)
                .execute());
    }

    /**
     * Runs one FHIR request.
     *
     * @param operation fixed operation name for logs and errors; never an ID or search value
     */
    private <T> T execute(String operation, Supplier<T> request) {
        long start = System.nanoTime();
        try {
            T result = request.get();
            log.debug("FHIR request succeeded: operation={}, durationMs={}", operation, elapsedMillis(start));
            return result;
        } catch (BaseServerResponseException | DataFormatException exception) {
            FhirServerException failure = FhirExceptionTranslator.translate(operation, exception);
            log.warn("FHIR request failed: operation={}, type={}, httpStatus={}, issues={}, cause={}, durationMs={}",
                    operation, failure.type(),
                    failure.httpStatus().isPresent() ? failure.httpStatus().getAsInt() : "-",
                    failure.issues(), failure.causeType(), elapsedMillis(start));
            throw failure;
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
