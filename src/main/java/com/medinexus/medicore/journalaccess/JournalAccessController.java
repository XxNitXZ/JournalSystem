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

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.journalaccess.dto.JournalAccessRequestDetail;
import com.medinexus.medicore.journalaccess.dto.JournalAccessRequestSummary;
import com.medinexus.medicore.journalaccess.dto.JournalAccessStatusDto;

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

    public JournalAccessController(JournalAccessService service) {
        this.service = service;
    }

    @GetMapping("/api/journal-access/me")
    public JournalAccessStatusDto myStatus(@AuthenticationPrincipal UserDto principal) {
        return service.statusFor(principal);
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
    public ResponseEntity<Void> denied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    @ExceptionHandler(JournalAccessRequestNotFoundException.class)
    public ResponseEntity<Void> notFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Void> malformedId() {
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(JournalAccessConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(JournalAccessConflictException conflict) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", conflict.code()));
    }

    /** Already logged safely by FhirRequestExecutor. */
    @ExceptionHandler(FhirServerException.class)
    public ResponseEntity<Void> fhirFailure(FhirServerException failure) {
        return ResponseEntity.status(failure.type() == FhirServerException.Type.TIMEOUT
                || failure.type() == FhirServerException.Type.UNAVAILABLE ? HttpStatus.SERVICE_UNAVAILABLE
                        : HttpStatus.BAD_GATEWAY).build();
    }
}
