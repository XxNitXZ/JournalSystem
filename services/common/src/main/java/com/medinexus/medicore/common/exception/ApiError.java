package com.medinexus.medicore.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The one error body every MediCore endpoint returns (NIT-328), e.g.
 * {@code {"status":409,"error":"Conflict","message":"The request conflicts with the current state.","code":"EMAIL_TAKEN"}}.
 *
 * <p>{@code message} is a fixed text chosen by status, never an exception message or user input, so an error can't
 * leak personal data. {@code code} is the machine-readable reason MediPortal already reads; it is left out when
 * there is none.
 *
 * @param status  HTTP status code, repeated in the body for clients that only see the body
 * @param error   standard reason phrase, e.g. {@code Not Found}
 * @param message short, safe text for people
 * @param code    optional reason in UPPER_SNAKE_CASE, e.g. {@code VERSION_CONFLICT}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(int status, String error, String message, String code) {

    public ApiError {
        if (code != null && !code.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Error codes are UPPER_SNAKE_CASE");
        }
    }

    public static ApiError of(HttpStatusCode status) {
        return of(status, null);
    }

    public static ApiError of(HttpStatusCode status, String code) {
        HttpStatus known = HttpStatus.resolve(status.value());
        String reason = known == null ? "Error" : known.getReasonPhrase();
        return new ApiError(status.value(), reason, messageFor(status.value()), code);
    }

    /** The error as a response: {@code return ApiError.response(HttpStatus.NOT_FOUND);} */
    public static ResponseEntity<ApiError> response(HttpStatusCode status) {
        return response(status, null);
    }

    public static ResponseEntity<ApiError> response(HttpStatusCode status, String code) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(of(status, code));
    }

    public static ResponseEntity<ApiError> response(int status) {
        return response(HttpStatusCode.valueOf(status), null);
    }

    public static ResponseEntity<ApiError> response(int status, String code) {
        return response(HttpStatusCode.valueOf(status), code);
    }

    /**
     * The same body as JSON text, for code that writes to the servlet response directly (the security filters run
     * before Spring MVC's converters). All fields are fixed texts or validated codes, so plain quoting is enough.
     */
    public String toJson() {
        return "{\"status\":" + status + ",\"error\":\"" + error + "\",\"message\":\"" + message + "\""
                + (code == null ? "" : ",\"code\":\"" + code + "\"") + "}";
    }

    private static String messageFor(int status) {
        return switch (status) {
            case 400 -> "The request is invalid.";
            case 401 -> "Sign in to continue.";
            case 403 -> "You do not have access to this.";
            case 404 -> "The requested resource was not found.";
            case 405 -> "This method is not allowed here.";
            case 409 -> "The request conflicts with the current state.";
            case 413 -> "The request is too large.";
            case 415 -> "This content type is not supported.";
            case 422 -> "The request could not be processed.";
            case 428 -> "A version is required for this change.";
            case 502 -> "A service MediCore depends on gave an invalid response.";
            case 503 -> "A service MediCore depends on is unavailable or did not answer in time.";
            case 504 -> "A service MediCore depends on did not answer in time.";
            default -> status >= 500 ? "Something went wrong. Please try again later." : "The request failed.";
        };
    }
}
