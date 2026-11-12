package com.medinexus.medicore.practitioner.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.practitioner.dto.PractitionerOption;
import com.medinexus.medicore.practitioner.service.PractitionerDirectory;

/** {@code GET /api/practitioners}: the Practitioners that can be chosen for an Encounter (DOCTOR and STAFF). */
@RestController
public class PractitionerController {

    private final PractitionerDirectory directory;

    public PractitionerController(PractitionerDirectory directory) {
        this.directory = directory;
    }

    @GetMapping("/api/practitioners")
    public List<PractitionerOption> available(@AuthenticationPrincipal UserDto principal) {
        return directory.available(principal);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Void> denied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    /** Already logged safely by FhirRequestExecutor. */
    @ExceptionHandler(FhirServerException.class)
    public ResponseEntity<Void> fhirFailure(FhirServerException failure) {
        return ResponseEntity.status(failure.type() == FhirServerException.Type.TIMEOUT
                || failure.type() == FhirServerException.Type.UNAVAILABLE ? HttpStatus.SERVICE_UNAVAILABLE
                        : HttpStatus.BAD_GATEWAY).build();
    }
}
