package com.medinexus.medicore.fhir.client;

import java.util.List;
import java.util.OptionalInt;

/**
 * A failed request to the external FHIR server.
 *
 * <p>Carries only safe metadata: the operation name, an error category, the
 * HTTP status (if a response was received), sanitized OperationOutcome issues
 * ({@code severity:code}) and the simple class name of the root cause. It never
 * contains server diagnostics, response bodies, request URLs, query parameters
 * or the original HAPI exception, so it can be logged or reported safely.
 */
public class FhirServerException extends RuntimeException {

    /** Error categories for callers that need to react differently. */
    public enum Type {
        /** Read, connect or connection-pool timeout. */
        TIMEOUT,
        /** Server not reachable (refused, reset, unknown host) or HTTP 502, 503 or 504. */
        UNAVAILABLE,
        /** The response could not be parsed, was not FHIR or had the wrong resource type. */
        INVALID_RESPONSE,
        /** HTTP 4xx. */
        CLIENT_ERROR,
        /** HTTP 5xx other than 502, 503 and 504. */
        SERVER_ERROR
    }

    private final String operation;
    private final Type type;
    private final Integer httpStatus;
    private final List<String> issues;
    private final String causeType;

    /**
     * @param operation  fixed operation name chosen by the caller, e.g. {@code "capabilities"};
     *                   never an ID or a search value
     * @param httpStatus HTTP status, or {@code null} if no response was received
     * @param issues     sanitized OperationOutcome issues in the form {@code severity:code}
     * @param causeType  simple class name of the root cause
     */
    public FhirServerException(String operation, Type type, Integer httpStatus, List<String> issues, String causeType) {
        super(buildMessage(operation, type, httpStatus, issues, causeType));
        this.operation = operation;
        this.type = type;
        this.httpStatus = httpStatus;
        this.issues = issues == null ? List.of() : List.copyOf(issues);
        this.causeType = causeType;
    }

    public String operation() {
        return operation;
    }

    public Type type() {
        return type;
    }

    public OptionalInt httpStatus() {
        return httpStatus == null ? OptionalInt.empty() : OptionalInt.of(httpStatus);
    }

    public List<String> issues() {
        return issues;
    }

    public String causeType() {
        return causeType;
    }

    private static String buildMessage(String operation, Type type, Integer httpStatus, List<String> issues,
            String causeType) {
        StringBuilder message = new StringBuilder("FHIR request '").append(operation).append("' failed: ").append(type);
        if (httpStatus != null) {
            message.append(" (HTTP ").append(httpStatus).append(')');
        }
        if (issues != null && !issues.isEmpty()) {
            message.append(", issues=").append(issues);
        }
        if (causeType != null) {
            message.append(", cause=").append(causeType);
        }
        return message.toString();
    }
}
