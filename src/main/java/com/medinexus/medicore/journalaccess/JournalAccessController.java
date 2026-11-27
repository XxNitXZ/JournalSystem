package com.medinexus.medicore.journalaccess;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.medinexus.medicore.common.exception.ApiError;
import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.service.AccountTokenService;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.journalaccess.dto.JournalAccessRequestDetail;
import com.medinexus.medicore.journalaccess.dto.JournalAccessRequestSummary;
import com.medinexus.medicore.journalaccess.dto.JournalAccessStatusDto;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Journal access requests. DOCTOR: list, pending count, details, approve, reject and reopen
 * ({@code /api/journal-access-requests/**}). PATIENT: own status ({@code GET /api/journal-access/me}) and
 * the explicit request for access ({@code POST /api/journal-access/me/request}). Roles are
 * enforced by {@code SecurityConfig} and again by {@link JournalAccessService}; state-changing requests need a CSRF
 * token. Errors are 403, 404, 409 with a {@code code}, 400 for a malformed ID and 502/503 for FHIR failures.
 */
@RestController
public class JournalAccessController {

    private final JournalAccessService service;
    private final AccountTokenService accountTokens;

    public JournalAccessController(JournalAccessService service, AccountTokenService accountTokens) {
        this.service = service;
        this.accountTokens = accountTokens;
    }

    /**
     * The PATIENT's access status. Also renews the token cookie: once a doctor has approved, the new token carries the
     * patient's {@code fhirPatientId}, so their own record opens without signing in again.
     */
    @GetMapping("/api/journal-access/me")
    public JournalAccessStatusDto myStatus(@AuthenticationPrincipal UserDto principal, HttpServletResponse response) {
        JournalAccessStatusDto status = service.statusFor(principal);
        accountTokens.issue(principal.id(), response);
        return status;
    }

    /**
     * The signed-in PATIENT asks for access to their journal. No request body is read: the account is the session's,
     * and the record is found by MediCore. Returns the resulting status (also for repeated calls).
     */
    @PostMapping("/api/journal-access/me/request")
    public JournalAccessStatusDto requestAccess(@AuthenticationPrincipal UserDto principal) {
        return service.requestAccess(principal);
    }

    @GetMapping("/api/journal-access-requests")
    public List<JournalAccessRequestSummary> list(@AuthenticationPrincipal UserDto principal) {
        return service.list(principal);
    }

    @GetMapping("/api/journal-access-requests/pending/count")
    public Map<String, Long> pendingCount(@AuthenticationPrincipal UserDto principal) {
        return Map.of("pending", service.pendingCount(principal));
    }

    @GetMapping("/api/journal-access-requests/{id}")
    public JournalAccessRequestDetail detail(@AuthenticationPrincipal UserDto principal, @PathVariable Long id) {
        return service.detail(principal, id);
    }

    @PostMapping("/api/journal-access-requests/{id}/approve")
    public JournalAccessRequestSummary approve(@AuthenticationPrincipal UserDto principal, @PathVariable Long id) {
        return service.approve(principal, id);
    }

    @PostMapping("/api/journal-access-requests/{id}/reject")
    public JournalAccessRequestSummary reject(@AuthenticationPrincipal UserDto principal, @PathVariable Long id) {
        return service.reject(principal, id);
    }

    @PostMapping("/api/journal-access-requests/{id}/reopen")
    public JournalAccessRequestSummary reopen(@AuthenticationPrincipal UserDto principal, @PathVariable Long id) {
        return service.reopen(principal, id);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> denied() {
        return ApiError.response(HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(JournalAccessRequestNotFoundException.class)
    public ResponseEntity<ApiError> notFound() {
        return ApiError.response(HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> malformedId() {
        return ApiError.response(HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(JournalAccessConflictException.class)
    public ResponseEntity<ApiError> conflict(JournalAccessConflictException conflict) {
        return ApiError.response(HttpStatus.CONFLICT, conflict.code());
    }

    /** Already logged safely by FhirRequestExecutor. */
    @ExceptionHandler(FhirServerException.class)
    public ResponseEntity<ApiError> fhirFailure(FhirServerException failure) {
        return ApiError.response(failure.type() == FhirServerException.Type.TIMEOUT
                || failure.type() == FhirServerException.Type.UNAVAILABLE ? HttpStatus.SERVICE_UNAVAILABLE
                        : HttpStatus.BAD_GATEWAY);
    }
}
