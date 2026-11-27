package com.medinexus.medicore.auth.service;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.fhir.client.FhirPatientClient;
import com.medinexus.medicore.fhir.client.FhirPractitionerClient;

/** Internal provisioning only. Callers must belong to a trusted operator workflow, never public registration. */
@Service
public class AccountFhirIdentityService {

    private final UserRepository repository;
    private final FhirPatientClient patients;
    private final FhirPractitionerClient practitioners;

    public AccountFhirIdentityService(UserRepository repository, FhirPatientClient patients,
            FhirPractitionerClient practitioners) {
        this.repository = repository;
        this.patients = patients;
        this.practitioners = practitioners;
    }

    /** Suspends any caller transaction; only the conditional repository update starts a write transaction. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void assign(Long accountId, String fhirId) {
        if (accountId == null || accountId <= 0 || fhirId == null || !fhirId.matches("[A-Za-z0-9.-]{1,64}")) {
            throw new IllegalArgumentException("A valid account ID and logical FHIR ID are required");
        }
        User account = load(accountId);
        Role role = account.getRole();
        if (role == null) throw conflict();
        String existing = reference(account, role);
        if (oppositeReference(account, role) != null || existing != null && !existing.equals(fhirId)) {
            throw conflict();
        }

        IBaseResource resource = role == Role.PATIENT ? patients.readPatient(fhirId)
                : practitioners.readPractitioner(fhirId);
        if (resource == null || !fhirId.equals(resource.getIdElement().getIdPart())) {
            throw new AccountFhirIdentityException("FHIR resource identity could not be verified");
        }

        int updated;
        try {
            updated = role == Role.PATIENT ? repository.assignPatientIfUnlinked(accountId, fhirId)
                    : repository.assignPractitionerIfUnlinked(accountId, role, fhirId);
        } catch (DataIntegrityViolationException exception) {
            // Never retain database messages/causes: PostgreSQL may include the conflicting identifier.
            throw new AccountFhirIdentityException("Identity association violates database constraints");
        }
        if (updated == 1) return;

        // Another assignment, deletion or role change may have occurred while FHIR was being read.
        User current = load(accountId);
        if (current.getRole() != role || oppositeReference(current, role) != null
                || !fhirId.equals(reference(current, role))) {
            throw conflict();
        }
    }

    private User load(Long accountId) {
        return repository.findById(accountId)
                .orElseThrow(() -> new AccountFhirIdentityException("Account does not exist"));
    }

    private String reference(User user, Role role) {
        return role == Role.PATIENT ? user.getFhirPatientId() : user.getFhirPractitionerId();
    }

    private String oppositeReference(User user, Role role) {
        return role == Role.PATIENT ? user.getFhirPractitionerId() : user.getFhirPatientId();
    }

    private AccountFhirIdentityException conflict() {
        return new AccountFhirIdentityException("Account association conflicts with the current account state");
    }
}
