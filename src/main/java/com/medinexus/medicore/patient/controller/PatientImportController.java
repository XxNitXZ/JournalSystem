package com.medinexus.medicore.patient.controller;

import java.net.URI;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.patient.dto.PatientCandidateDto;
import com.medinexus.medicore.patient.dto.PatientDiscoveryResponse;
import com.medinexus.medicore.patient.service.PatientImportException;
import com.medinexus.medicore.patient.service.PatientImportService;
import com.medinexus.medicore.patient.service.PatientNotFoundException;

/**
 * Import of existing FHIR Patients (DOCTOR and STAFF; CSRF):
 * <ul>
 *   <li>{@code POST /api/patients/import/search} {@code {"mode":"NAME|PERSONNUMMER|ID","value":"..."}}: searches the
 *       configured FHIR server (POST, so the search value is never part of a URL);</li>
 *   <li>{@code POST /api/patients/import} {@code {"fhirPatientId":"..."}}: records the existing Patient in MediCore's
 *       registry; 201 with the imported Patient.</li>
 * </ul>
 * Errors: 400/409/422 with a {@code code}, 403 role, 404 Patient not on the FHIR server, 503/502 FHIR failures.
 */
@RestController
public class PatientImportController {

    private static final Set<String> SEARCH_FIELDS = Set.of("mode", "value");
    private static final Set<String> IMPORT_FIELDS = Set.of("fhirPatientId");

    private final PatientImportService imports;

    public PatientImportController(PatientImportService imports) {
        this.imports = imports;
    }

    @PostMapping("/api/patients/import/search")
    public PatientDiscoveryResponse search(@AuthenticationPrincipal UserDto principal,
            @RequestBody Map<String, Object> body) {
        return imports.discover(principal, PatientImportService.text(body, SEARCH_FIELDS, "mode"),
                PatientImportService.text(body, SEARCH_FIELDS, "value"));
    }

    @PostMapping("/api/patients/import")
    public ResponseEntity<PatientCandidateDto> importPatient(@AuthenticationPrincipal UserDto principal,
            @RequestBody Map<String, Object> body) {
        PatientCandidateDto imported = imports.importPatient(principal,
                PatientImportService.text(body, IMPORT_FIELDS, "fhirPatientId"));
        return ResponseEntity.created(URI.create("/api/patients/" + imported.id())).body(imported);
    }

    @ExceptionHandler(PatientImportException.class)
    public ResponseEntity<Map<String, String>> rejected(PatientImportException rejected) {
        return ResponseEntity.status(rejected.status()).body(Map.of("code", rejected.code()));
    }

    @ExceptionHandler(PatientNotFoundException.class)
    public ResponseEntity<Void> notFound() {
        return ResponseEntity.notFound().build();
    }

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
