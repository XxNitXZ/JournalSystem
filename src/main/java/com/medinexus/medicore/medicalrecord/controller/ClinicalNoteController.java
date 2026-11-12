package com.medinexus.medicore.medicalrecord.controller;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.medicalrecord.dto.ClinicalNoteDto;
import com.medinexus.medicore.medicalrecord.dto.CreateClinicalNoteRequest;
import com.medinexus.medicore.medicalrecord.service.ClinicalNoteException;
import com.medinexus.medicore.medicalrecord.service.ClinicalNoteService;
import com.medinexus.medicore.patient.service.PatientNotFoundException;

/**
 * Clinical notes of a Patient (US-05.01). Roles are enforced in {@code SecurityConfig} and again in
 * {@link ClinicalNoteService}; creating requires a CSRF token.
 * <ul>
 *   <li>{@code POST /api/patients/{patientId}/notes} (DOCTOR, STAFF with a linked Practitioner): 201 with the
 *       note;</li>
 *   <li>{@code GET /api/patients/{patientId}/notes} (DOCTOR, STAFF): the Patient's notes, newest first;</li>
 *   <li>{@code GET /api/patients/me/notes} (PATIENT): the signed-in patient's own notes; no ID is accepted.</li>
 * </ul>
 * Errors: 400/404/422 with a {@code code} from {@link ClinicalNoteException}; 400 malformed ID or body; 403 role or
 * access; 404 Patient not known to MediCore or missing; 503 FHIR timeout or unavailable; 502 invalid FHIR response
 * or FHIR server error; 422 other FHIR rejections. Bodies never echo input, note text or server details.
 */
@RestController
public class ClinicalNoteController {

    private final ClinicalNoteService notes;

    public ClinicalNoteController(ClinicalNoteService notes) {
        this.notes = notes;
    }

    @PostMapping("/api/patients/{patientId}/notes")
    public ResponseEntity<ClinicalNoteDto> create(@AuthenticationPrincipal UserDto principal,
            @PathVariable String patientId, @RequestBody Map<String, Object> body) {
        ClinicalNoteDto created = notes.create(principal, patientId, CreateClinicalNoteRequest.from(body));
        return ResponseEntity.created(URI.create("/api/patients/" + patientId + "/notes")).body(created);
    }

    @GetMapping("/api/patients/me/notes")
    public List<ClinicalNoteDto> own(@AuthenticationPrincipal UserDto principal) {
        return notes.listOwn(principal);
    }

    @GetMapping("/api/patients/{patientId}/notes")
    public List<ClinicalNoteDto> list(@AuthenticationPrincipal UserDto principal, @PathVariable String patientId) {
        return notes.listForPatient(principal, patientId);
    }

    @ExceptionHandler(ClinicalNoteException.class)
    public ResponseEntity<Map<String, String>> rejected(ClinicalNoteException rejected) {
        return ResponseEntity.status(rejected.status()).body(Map.of("code", rejected.code()));
    }

    @ExceptionHandler(PatientNotFoundException.class)
    public ResponseEntity<Void> notFound() {
        return ResponseEntity.notFound().build();
    }

    /** Malformed Patient ID or unreadable JSON (whose message may contain submitted values). */
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Void> invalid() {
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Void> denied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    /** Already logged safely by FhirRequestExecutor. */
    @ExceptionHandler(FhirServerException.class)
    public ResponseEntity<Void> fhirFailure(FhirServerException failure) {
        HttpStatus status = switch (failure.type()) {
            case TIMEOUT, UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case CLIENT_ERROR -> HttpStatus.UNPROCESSABLE_CONTENT;
            case INVALID_RESPONSE, SERVER_ERROR -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).build();
    }
}
