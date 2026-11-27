package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.StringReader;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.ConnectionPoolTimeoutException;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.rest.client.exceptions.FhirClientConnectionException;
import ca.uhn.fhir.rest.client.exceptions.InvalidResponseException;
import ca.uhn.fhir.rest.client.exceptions.NonFhirResponseException;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;

import com.medinexus.medicore.fhir.client.FhirServerException.Type;

/** Translation of HAPI exceptions; no network access. */
class FhirExceptionTranslatorTest {

    /** Stands in for server diagnostics, URLs and patient data that must never be carried over. */
    private static final String SECRET = "MARKER-SECRET Jane Doe 19900101-1234 http://fhir.example/Patient?identifier=1";

    private static FhirServerException translate(RuntimeException exception) {
        FhirServerException translated = FhirExceptionTranslator.translate("capabilities", exception);
        assertThat(translated.getCause()).as("original HAPI exception is not attached").isNull();
        assertThat(translated.getMessage()).doesNotContain("MARKER").doesNotContain("http://");
        assertThat(translated.issues()).noneMatch(issue -> issue.contains("MARKER"));
        assertThat(translated.operation()).isEqualTo("capabilities");
        return translated;
    }

    private static FhirClientConnectionException connectionFailure(Throwable cause) {
        return new FhirClientConnectionException("HAPI-1361: GET " + SECRET, cause);
    }

    @Test
    void readTimeoutIsTimeout() {
        FhirServerException translated = translate(connectionFailure(new SocketTimeoutException(SECRET)));
        assertThat(translated.type()).isEqualTo(Type.TIMEOUT);
        assertThat(translated.httpStatus()).isEmpty();
        assertThat(translated.causeType()).isEqualTo("SocketTimeoutException");
    }

    @Test
    void connectTimeoutIsTimeout() {
        FhirServerException translated = translate(connectionFailure(new ConnectTimeoutException(SECRET)));
        assertThat(translated.type()).isEqualTo(Type.TIMEOUT);
        assertThat(translated.causeType()).isEqualTo("ConnectTimeoutException");
    }

    @Test
    void connectionPoolTimeoutIsTimeout() {
        FhirServerException translated = translate(connectionFailure(new ConnectionPoolTimeoutException(SECRET)));
        assertThat(translated.type()).isEqualTo(Type.TIMEOUT);
        assertThat(translated.causeType()).isEqualTo("ConnectionPoolTimeoutException");
    }

    @Test
    void refusedConnectionIsUnavailable() {
        FhirServerException translated = translate(connectionFailure(new IOException(SECRET, new ConnectException(SECRET))));
        assertThat(translated.type()).isEqualTo(Type.UNAVAILABLE);
        assertThat(translated.httpStatus()).isEmpty();
        assertThat(translated.causeType()).isEqualTo("ConnectException");
    }

    @Test
    void unknownHostIsUnavailable() {
        FhirServerException translated = translate(connectionFailure(new UnknownHostException(SECRET)));
        assertThat(translated.type()).isEqualTo(Type.UNAVAILABLE);
        assertThat(translated.causeType()).isEqualTo("UnknownHostException");
    }

    @Test
    void unparseableResponseIsInvalidResponse() {
        FhirServerException translated = translate(connectionFailure(new DataFormatException(SECRET)));
        assertThat(translated.type()).isEqualTo(Type.INVALID_RESPONSE);
        assertThat(translated.httpStatus()).isEmpty();
    }

    @Test
    void dataFormatExceptionOnItsOwnIsInvalidResponse() {
        assertThat(translate(new DataFormatException(SECRET)).type()).isEqualTo(Type.INVALID_RESPONSE);
    }

    @Test
    void invalidResponseExceptionIsInvalidResponse() {
        FhirServerException translated = translate(new InvalidResponseException(SECRET, 200));
        assertThat(translated.type()).isEqualTo(Type.INVALID_RESPONSE);
        assertThat(translated.httpStatus()).hasValue(200);
    }

    @ParameterizedTest
    @CsvSource({"200, INVALID_RESPONSE", "503, UNAVAILABLE", "500, SERVER_ERROR", "404, CLIENT_ERROR"})
    void nonFhirResponseIsClassifiedByStatus(int status, Type expected) {
        NonFhirResponseException exception =
                NonFhirResponseException.newInstance(status, "text/html", new StringReader("<html>" + SECRET + "</html>"));
        FhirServerException translated = translate(exception);
        assertThat(translated.type()).isEqualTo(expected);
        assertThat(translated.httpStatus()).hasValue(status);
        assertThat(translated.causeType()).isEqualTo("NonFhirResponseException");
    }

    @ParameterizedTest
    @CsvSource({
            "400, CLIENT_ERROR", "401, CLIENT_ERROR", "403, CLIENT_ERROR", "404, CLIENT_ERROR",
            "409, CLIENT_ERROR", "410, CLIENT_ERROR", "422, CLIENT_ERROR",
            "500, SERVER_ERROR", "501, SERVER_ERROR",
            "502, UNAVAILABLE", "503, UNAVAILABLE", "504, UNAVAILABLE"
    })
    void httpErrorsAreClassifiedByStatus(int status, Type expected) {
        FhirServerException translated = translate(BaseServerResponseException.newInstance(status, SECRET));
        assertThat(translated.type()).isEqualTo(expected);
        assertThat(translated.httpStatus()).hasValue(status);
        assertThat(translated.issues()).isEmpty();
    }

    @Test
    void operationOutcomeKeepsOnlyCodedSeverityAndCode() {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue().setSeverity(IssueSeverity.ERROR).setCode(IssueType.NOTFOUND).setDiagnostics(SECRET);
        outcome.addIssue().setSeverity(IssueSeverity.WARNING).setCode(IssueType.INFORMATIONAL)
                .addExpression(SECRET).getDetails().setText(SECRET);
        outcome.addIssue().setDiagnostics(SECRET);

        FhirServerException translated = translate(new ResourceNotFoundException(SECRET, outcome));

        assertThat(translated.type()).isEqualTo(Type.CLIENT_ERROR);
        assertThat(translated.httpStatus()).hasValue(404);
        assertThat(translated.issues()).containsExactly("error:not-found", "warning:informational", "unknown:unknown");
        assertThat(translated.causeType()).isEqualTo("ResourceNotFoundException");
        assertThat(translated.getMessage())
                .isEqualTo("FHIR request 'capabilities' failed: CLIENT_ERROR (HTTP 404), "
                        + "issues=[error:not-found, warning:informational, unknown:unknown], cause=ResourceNotFoundException");
    }

    @Test
    void operationOutcomeIssuesAreLimited() {
        OperationOutcome outcome = new OperationOutcome();
        for (int i = 0; i < 25; i++) {
            outcome.addIssue().setSeverity(IssueSeverity.ERROR).setCode(IssueType.PROCESSING).setDiagnostics(SECRET);
        }
        assertThat(translate(new ResourceNotFoundException(SECRET, outcome)).issues()).hasSize(10);
    }

    @Test
    void otherExceptionsAreRejected() {
        assertThatThrownBy(() -> FhirExceptionTranslator.translate("capabilities", new IllegalStateException(SECRET)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("MARKER");
    }
}
