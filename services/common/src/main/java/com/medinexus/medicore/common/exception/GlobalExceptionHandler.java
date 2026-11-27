package com.medinexus.medicore.common.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The fallback for errors that a controller doesn't handle itself (NIT-328). Spring first looks for an
 * {@code @ExceptionHandler} in the controller that threw, and only then here. So the controllers' own handlers,
 * with their specific status codes and {@code code} values, always win; this class makes sure nothing else escapes
 * as Spring's default error page or a stack trace.
 *
 * <p>Logs never contain exception messages: they can include user input such as names or personnummer. Only the
 * exception type and the place it was thrown are logged.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingRequestHeaderException.class})
    public ResponseEntity<ApiError> badRequest() {
        return ApiError.response(HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> forbidden() {
        return ApiError.response(HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> notFound() {
        return ApiError.response(HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> methodNotAllowed() {
        return ApiError.response(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> unsupportedMediaType() {
        return ApiError.response(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    /**
     * Another MediCore service could not be reached or did not answer within the timeouts of
     * {@link com.medinexus.medicore.common.http.ServiceClients}. Same status as an unreachable FHIR server.
     */
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ApiError> serviceUnavailable(ResourceAccessException failure) {
        log.warn("Service call failed: {}", failure.getCause() == null ? "unknown" : failure.getCause().getClass()
                .getSimpleName());
        return ApiError.response(HttpStatus.SERVICE_UNAVAILABLE);
    }

    /** Another service answered with an error the caller didn't handle: its fault, not the client's. */
    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<ApiError> badGateway(RestClientResponseException failure) {
        log.warn("Service call answered {}", failure.getStatusCode().value());
        return ApiError.response(HttpStatus.BAD_GATEWAY);
    }

    /** Anything unexpected: a generic 500, and a log line with the type and location only. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception failure) {
        StackTraceElement[] trace = failure.getStackTrace();
        log.error("Unhandled {} at {}", failure.getClass().getName(), trace.length > 0 ? trace[0] : "unknown");
        return ApiError.response(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
