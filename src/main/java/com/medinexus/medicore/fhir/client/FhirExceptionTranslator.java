package com.medinexus.medicore.fhir.client;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;

import org.hl7.fhir.instance.model.api.IBaseOperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.OperationOutcomeIssueComponent;

import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.rest.client.exceptions.FhirClientConnectionException;
import ca.uhn.fhir.rest.client.exceptions.InvalidResponseException;
import ca.uhn.fhir.rest.client.exceptions.NonFhirResponseException;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;

import com.medinexus.medicore.fhir.client.FhirServerException.Type;

/**
 * Translates HAPI FHIR client exceptions into {@link FhirServerException}.
 *
 * <p>Classification uses exception types and HTTP status codes only, never
 * (localized) message text. The HAPI exception is not attached as a cause:
 * its message chain can contain request URLs with query parameters, server
 * diagnostics or response bodies. From an OperationOutcome only the coded
 * {@code severity} and {@code code} of each issue are kept; free-text fields
 * such as {@code diagnostics} are dropped.
 */
final class FhirExceptionTranslator {

    private static final int MAX_ISSUES = 10;
    private static final int MAX_CAUSE_DEPTH = 20;

    private FhirExceptionTranslator() {
    }

    static FhirServerException translate(String operation, RuntimeException exception) {
        // Order matters: the client-side exceptions below are subclasses of
        // BaseServerResponseException, and HAPI reports status 500 for them
        // even though no HTTP response was received.
        if (exception instanceof FhirClientConnectionException connectionException) {
            Type type;
            if (hasCause(connectionException, DataFormatException.class)) {
                type = Type.INVALID_RESPONSE;
            } else if (hasCause(connectionException, InterruptedIOException.class)) {
                // SocketTimeoutException (read), ConnectTimeoutException (connect)
                // and ConnectionPoolTimeoutException (connection request).
                type = Type.TIMEOUT;
            } else {
                type = Type.UNAVAILABLE;
            }
            return new FhirServerException(operation, type, null, List.of(), rootCauseType(connectionException));
        }
        if (exception instanceof NonFhirResponseException nonFhirException) {
            int status = nonFhirException.getStatusCode();
            Type type = status >= 400 ? typeForStatus(status) : Type.INVALID_RESPONSE;
            return new FhirServerException(operation, type, status, List.of(), simpleName(nonFhirException));
        }
        if (exception instanceof InvalidResponseException invalidResponse) {
            return new FhirServerException(operation, Type.INVALID_RESPONSE, invalidResponse.getStatusCode(),
                    List.of(), simpleName(invalidResponse));
        }
        if (exception instanceof BaseServerResponseException responseException) {
            int status = responseException.getStatusCode();
            return new FhirServerException(operation, typeForStatus(status), status,
                    issues(responseException.getOperationOutcome()), simpleName(responseException));
        }
        if (exception instanceof DataFormatException) {
            return new FhirServerException(operation, Type.INVALID_RESPONSE, null, List.of(), rootCauseType(exception));
        }
        throw new IllegalArgumentException("Not a FHIR client exception: " + simpleName(exception));
    }

    private static Type typeForStatus(int status) {
        if (status == 502 || status == 503 || status == 504) {
            return Type.UNAVAILABLE;
        }
        if (status >= 400 && status < 500) {
            return Type.CLIENT_ERROR;
        }
        if (status >= 500 && status < 600) {
            return Type.SERVER_ERROR;
        }
        return Type.INVALID_RESPONSE;
    }

    /** Coded {@code severity:code} pairs only; never diagnostics, details or expressions. */
    private static List<String> issues(IBaseOperationOutcome outcome) {
        if (!(outcome instanceof OperationOutcome operationOutcome)) {
            return List.of();
        }
        List<String> issues = new ArrayList<>();
        for (OperationOutcomeIssueComponent issue : operationOutcome.getIssue()) {
            if (issues.size() == MAX_ISSUES) {
                break;
            }
            String severity = issue.getSeverity() == null ? null : issue.getSeverity().toCode();
            String code = issue.getCode() == null ? null : issue.getCode().toCode();
            issues.add(codeOrUnknown(severity) + ":" + codeOrUnknown(code));
        }
        return issues;
    }

    /** Coded values come from fixed FHIR value sets; anything unexpected becomes "unknown". */
    private static String codeOrUnknown(String code) {
        return code == null || !code.matches("[a-z][a-z-]{0,63}") ? "unknown" : code;
    }

    private static boolean hasCause(Throwable exception, Class<? extends Throwable> type) {
        Throwable current = exception.getCause();
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String rootCauseType(Throwable exception) {
        Throwable root = exception;
        for (int depth = 0; root.getCause() != null && root.getCause() != root && depth < MAX_CAUSE_DEPTH; depth++) {
            root = root.getCause();
        }
        return simpleName(root);
    }

    private static String simpleName(Throwable throwable) {
        return throwable.getClass().getSimpleName();
    }
}
