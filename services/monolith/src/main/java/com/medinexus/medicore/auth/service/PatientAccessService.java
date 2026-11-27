package com.medinexus.medicore.auth.service;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;

/** Ownership check only; callers must separately enforce operation-specific role restrictions. */
@Service
public class PatientAccessService {
    private final UserRepository repository;

    public PatientAccessService(UserRepository repository) {
        this.repository = repository;
    }

    /** The principal must come from the authenticated security context, never request input. */
    public void checkPatientAccess(UserDto principal, String patientId) {
        if (principal == null || principal.id() == null || principal.id() <= 0
                || patientId == null || !patientId.matches("[A-Za-z0-9.-]{1,64}")) {
            throw denied();
        }
        User account = repository.findById(principal.id()).orElseThrow(PatientAccessService::denied);
        Role role = account.getRole();
        if (role == Role.DOCTOR || role == Role.STAFF) return;
        if (role == Role.PATIENT && patientId.equals(account.getFhirPatientId())) return;
        throw denied();
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Access denied");
    }
}
