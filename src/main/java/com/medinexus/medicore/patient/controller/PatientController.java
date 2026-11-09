package com.medinexus.medicore.patient.controller;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.fhir.client.DuplicatePatientException;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.fhir.client.TooManyPatientsException;
import com.medinexus.medicore.patient.dto.CreatePatientRequest;
import com.medinexus.medicore.patient.dto.PatientDto;
import com.medinexus.medicore.patient.dto.PatientProfileDto;
import com.medinexus.medicore.patient.dto.UpdatePatientRequest;
import com.medinexus.medicore.patient.dto.PatientSearchRequest;
import com.medinexus.medicore.patient.service.OwnPatientRecordException;
import com.medinexus.medicore.patient.service.PatientNotFoundException;
import com.medinexus.medicore.patient.service.PatientRegistryException;
import com.medinexus.medicore.patient.service.PatientService;
import com.medinexus.medicore.patient.service.PatientVersionConflictException;
import com.medinexus.medicore.patient.service.PatientVersionRequiredException;

/**
 * Patient registration (US-04.01) and patient profiles (US-04.02) for DOCTOR and STAFF accounts. The URL rules
 * in {@code SecurityConfig} and {@link PatientService} both enforce the role.
 *
 * <p>Error responses have no body, so they never echo submitted data or server details:
 * 400 invalid data or Patient ID, 403 role, 404 Patient not found, 409 personnummer already registered,
 * 422 rejected by the FHIR server, 502 invalid or failed FHIR response, 503 FHIR server unavailable or timed
 * out (for a registration the outcome is then unknown; repeating it is safe because a completed registration
 * is reported as 409).
 */
@RestController
@RequestMapping("/api/patients")
public class PatientController {

    private final PatientService patientService;

    public PatientController(PatientService patientService) {
        this.patientService = patientService;
    }

    @PostMapping
    public ResponseEntity<PatientDto> register(@AuthenticationPrincipal UserDto principal,
            @RequestBody CreatePatientRequest request) {
        PatientDto patient = patientService.registerPatient(principal, request);
        return ResponseEntity.created(URI.create("/api/patients/" + patient.id())).body(patient);
    }

    /**
     * The signed-in PATIENT's own record. No patient ID is accepted: it comes only from the account's approved
     * link in PostgreSQL. 404 with {@code RECORD_NOT_LINKED} or {@code RECORD_NOT_FOUND} when there is no record.
     */
    @GetMapping("/me")
    public PatientProfileDto ownRecord(@AuthenticationPrincipal UserDto principal) {
        return patientService.getOwnRecord(principal);
    }

    @ExceptionHandler(OwnPatientRecordException.class)
    public ResponseEntity<Map<String, String>> ownRecordUnavailable(OwnPatientRecordException failure) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", failure.code()));
    }

    /** One Patient's profile, read directly from HAPI FHIR, with the e-mail of a linked PATIENT account. */
    @GetMapping("/{id}")
    public PatientProfileDto profile(@AuthenticationPrincipal UserDto principal, @PathVariable String id) {
        return patientService.getPatient(principal, id);
    }

    /**
     * Updates the gender of a registered Patient in HAPI FHIR (US-04.04, DOCTOR and STAFF). The body may contain only
     * {@code gender}; names, personnummer, birth date and ID are immutable, contact details are not FHIR data, and
     * any other property is rejected (400). {@code If-Match} must carry the
     * {@code version} of the profile the user edited ({@code "3"}, {@code W/"3"} or {@code 3}): missing is 428
     * {@code VERSION_REQUIRED}, a Patient changed in the meantime is 409 {@code VERSION_CONFLICT}.
     */
    @PatchMapping("/{id}")
    public PatientProfileDto update(@AuthenticationPrincipal UserDto principal, @PathVariable String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody Map<String, Object> body) {
        return patientService.updatePatient(principal, id, version(ifMatch), UpdatePatientRequest.from(body));
    }

    /** The version in an If-Match value, or null if there is none. */
    private static String version(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) return null;
        String value = ifMatch.strip();
        if (value.startsWith("W/")) value = value.substring(2);
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    @ExceptionHandler(PatientVersionRequiredException.class)
    public ResponseEntity<Map<String, String>> versionRequired() {
        return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED).body(Map.of("code", "VERSION_REQUIRED"));
    }

    @ExceptionHandler(PatientVersionConflictException.class)
    public ResponseEntity<Map<String, String>> versionConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "VERSION_CONFLICT"));
    }

    /**
     * Patient search by name or exact personnummer, limited to Patients known to MediCore. POST so that the
     * search text (possibly a personnummer) is never part of a URL.
     */
    @PostMapping("/search")
    public List<PatientDto> search(@AuthenticationPrincipal UserDto principal, @RequestBody PatientSearchRequest request) {
        return patientService.searchPatients(principal, request.query());
    }

    @ExceptionHandler(TooManyPatientsException.class)
    public ResponseEntity<Map<String, String>> tooManyPatients() {
        return ResponseEntity.unprocessableContent().body(Map.of("code", "TOO_MANY_RESULTS"));
    }

    /** Created in FHIR but not recorded locally; the client must not register the Patient again. */
    @ExceptionHandler(PatientRegistryException.class)
    public ResponseEntity<Map<String, String>> registryFailed() {
        return ResponseEntity.internalServerError().body(Map.of("code", "REGISTRY_FAILED"));
    }

    @ExceptionHandler(PatientNotFoundException.class)
    public ResponseEntity<Void> notFound() {
        return ResponseEntity.notFound().build();
    }

    /** Invalid data, including JSON that cannot be read (whose message may contain submitted values). */
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Void> invalidRegistration() {
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Void> denied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    @ExceptionHandler(DuplicatePatientException.class)
    public ResponseEntity<Void> duplicate() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    /** The failure itself has already been logged safely by FhirRequestExecutor. */
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
