package com.medinexus.medicore.organization.controller;

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
import com.medinexus.medicore.organization.dto.OrganizationOption;
import com.medinexus.medicore.organization.service.OrganizationDirectory;

/** {@code GET /api/organizations}: active named Organizations that can be chosen for an Encounter (DOCTOR and STAFF). */
@RestController
public class OrganizationController {

    private final OrganizationDirectory directory;

    public OrganizationController(OrganizationDirectory directory) {
        this.directory = directory;
    }

    @GetMapping("/api/organizations")
    public List<OrganizationOption> available(@AuthenticationPrincipal UserDto principal) {
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
