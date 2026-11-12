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
import com.medinexus.medicore.medicalrecord.dto.DiagnosisDto;
import com.medinexus.medicore.medicalrecord.dto.NewDiagnosisRequest;
import com.medinexus.medicore.medicalrecord.service.DiagnosisRequestException;
import com.medinexus.medicore.medicalrecord.service.DiagnosisService;
import com.medinexus.medicore.patient.service.PatientNotFoundException;

/**
 * Diagnoses (FHIR Conditions) of a Patient (US-05.02). Roles are enforced in {@code SecurityConfig} and again in
 * {@link DiagnosisService}; recording requires a CSRF token.
 * <ul>
 *   <li>{@code POST /api/patients/{patientId}/conditions} (DOCTOR, STAFF with a linked Practitioner): 201 with the
 *       diagnosis;</li>
 *   <li>{@code GET /api/patients/{patientId}/conditions} (DOCTOR, STAFF): the Patient's diagnoses, newest first;</li>
 *   <li>{@code GET /api/patients/me/conditions} (PATIENT): the signed-in patient's own diagnoses; no ID is
 *       accepted.</li>
 * </ul>
 * There is no update or delete. Errors: 400/404/422 with a {@code code} from {@link DiagnosisRequestException}; 400
 * malformed ID or body; 403 role or access; 404 Patient not known to MediCore or missing; 503 FHIR timeout or
 * unavailable; 502 invalid FHIR response or FHIR server error; 422 other FHIR rejections. Bodies never echo input or
 * server details.
 */
@RestController
public class DiagnosisController {

    private final DiagnosisService diagnoses;

    public DiagnosisController(DiagnosisService diagnoses) {
        this.diagnoses = diagnoses;
    }

    @PostMapping("/api/patients/{patientId}/conditions")
    public ResponseEntity<DiagnosisDto> create(@AuthenticationPrincipal UserDto principal,
            @PathVariable String patientId, @RequestBody Map<String, Object> body) {
        DiagnosisDto created = diagnoses.create(principal, patientId, NewDiagnosisRequest.from(body));
        return ResponseEntity.created(URI.create("/api/patients/" + patientId + "/conditions")).body(created);
    }

    @GetMapping("/api/patients/me/conditions")
    public List<DiagnosisDto> own(@AuthenticationPrincipal UserDto principal) {
        return diagnoses.listOwn(principal);
    }

    @GetMapping("/api/patients/{patientId}/conditions")
    public List<DiagnosisDto> list(@AuthenticationPrincipal UserDto principal, @PathVariable String patientId) {
        return diagnoses.listForPatient(principal, patientId);
    }

    @ExceptionHandler(DiagnosisRequestException.class)
    public ResponseEntity<Map<String, String>> rejected(DiagnosisRequestException rejected) {
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
