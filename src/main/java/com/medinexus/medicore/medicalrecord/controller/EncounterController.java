package com.medinexus.medicore.medicalrecord.controller;

import java.net.URI;
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
import com.medinexus.medicore.medicalrecord.dto.EncounterSummaryDto;
import com.medinexus.medicore.medicalrecord.dto.EncounterListResult;
import com.medinexus.medicore.medicalrecord.dto.NewEncounterRequest;
import com.medinexus.medicore.medicalrecord.service.EncounterRequestException;
import com.medinexus.medicore.medicalrecord.service.EncounterService;
import com.medinexus.medicore.patient.service.PatientNotFoundException;

/**
 * Encounters (visits) of a Patient (US-05.05). Roles are enforced in {@code SecurityConfig} and again in
 * {@link EncounterService}; creating requires a CSRF token.
 * <ul>
 *   <li>{@code POST /api/patients/{patientId}/encounters} (DOCTOR, STAFF): 201 with the new visit;</li>
 *   <li>{@code GET /api/patients/{patientId}/encounters} (DOCTOR, STAFF): the Patient's visits, newest first;</li>
 *   <li>{@code GET /api/patients/me/encounters} (PATIENT): the signed-in patient's own visits; no ID is accepted.</li>
 * </ul>
 * Errors: 400/404/422 with a {@code code} from {@link EncounterRequestException}; 400 malformed ID or body; 403
 * role or access; 404 Patient not known to MediCore or missing; 503 FHIR timeout or unavailable; 502 invalid FHIR
 * response or FHIR server error; 422 other FHIR rejections. Bodies never echo input or server details.
 */
@RestController
public class EncounterController {

    private final EncounterService encounters;

    public EncounterController(EncounterService encounters) {
        this.encounters = encounters;
    }

    @PostMapping("/api/patients/{patientId}/encounters")
    public ResponseEntity<EncounterSummaryDto> create(@AuthenticationPrincipal UserDto principal,
            @PathVariable String patientId, @RequestBody Map<String, Object> body) {
        EncounterSummaryDto created = encounters.create(principal, patientId, NewEncounterRequest.from(body));
        return ResponseEntity.created(URI.create("/api/patients/" + patientId + "/encounters")).body(created);
    }

    @GetMapping("/api/patients/me/encounters")
    public EncounterListResult own(@AuthenticationPrincipal UserDto principal) {
        return encounters.listOwn(principal);
    }

    @GetMapping("/api/patients/{patientId}/encounters")
    public EncounterListResult list(@AuthenticationPrincipal UserDto principal, @PathVariable String patientId) {
        return encounters.listForPatient(principal, patientId);
    }

    @ExceptionHandler(EncounterRequestException.class)
    public ResponseEntity<Map<String, String>> rejected(EncounterRequestException rejected) {
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
