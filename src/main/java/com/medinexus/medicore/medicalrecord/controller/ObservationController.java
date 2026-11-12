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
import com.medinexus.medicore.medicalrecord.dto.ObservationDto;
import com.medinexus.medicore.medicalrecord.dto.NewObservationRequest;
import com.medinexus.medicore.medicalrecord.service.ObservationService;
import com.medinexus.medicore.patient.service.PatientNotFoundException;
import com.medinexus.medicore.patient.service.OwnPatientRecordException;

@RestController
public class ObservationController {

    private final ObservationService observations;
    private final tools.jackson.databind.ObjectMapper json;

    public ObservationController(ObservationService observations, tools.jackson.databind.ObjectMapper json) {
        this.observations = observations;
        this.json = json;
    }

    @PostMapping("/api/patients/{patientId}/observations")
    public ResponseEntity<ObservationDto> create(@AuthenticationPrincipal UserDto principal,
            @PathVariable String patientId, @RequestBody String body) {
        ObservationDto created = observations.create(principal, patientId, request(body));
        return ResponseEntity.created(URI.create("/api/patients/" + patientId + "/observations")).body(created);
    }

    @GetMapping("/api/patients/me/observations")
    public List<ObservationDto> own(@AuthenticationPrincipal UserDto principal) {
        return observations.listOwn(principal);
    }

    @GetMapping("/api/patients/{patientId}/observations")
    public List<ObservationDto> list(@AuthenticationPrincipal UserDto principal, @PathVariable String patientId) {
        return observations.listForPatient(principal, patientId);
    }

    private NewObservationRequest request(String body) {
        try {
            return json.readerFor(NewObservationRequest.class)
                    .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(body);
        } catch (tools.jackson.core.JacksonException failure) {
            throw new IllegalArgumentException("Invalid Observation request");
        }
    }

    @ExceptionHandler(OwnPatientRecordException.class)
    public ResponseEntity<Map<String, String>> unlinked(OwnPatientRecordException failure) {
        return ResponseEntity.status(404).body(Map.of("code", failure.code()));
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
