package com.medinexus.medicore.fhir.client;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;

/**
 * Shared execution and safe logging for FHIR requests.
 * Never log original HAPI exceptions, request URLs or response bodies:
 * they can contain patient data.
 */
@Component
public class FhirRequestExecutor {

    private static final Logger log = LoggerFactory.getLogger(FhirRequestExecutor.class);

    /**
     * Runs one FHIR request.
     *
     * @param operation fixed operation name for logs and errors; never an ID or search value
     */
    public <T> T execute(String operation, Supplier<T> request) {
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
