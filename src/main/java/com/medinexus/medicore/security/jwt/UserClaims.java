package com.medinexus.medicore.security.jwt;

import java.util.Objects;

import com.medinexus.medicore.auth.entity.Role;

/**
 * What a user token says about the signed-in account. Every service reads these claims instead of looking the
 * account up (docs/architecture.md, D1).
 *
 * @param accountId          {@code sub}: the account id ({@code users.id})
 * @param fhirPatientId      logical FHIR Patient id of a linked PATIENT account, or null
 * @param fhirPractitionerId logical FHIR Practitioner id of a linked DOCTOR/STAFF account, or null
 */
public record UserClaims(Long accountId, String email, Role role, String givenName, String familyName,
        String fhirPatientId, String fhirPractitionerId) {

    public UserClaims {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(role, "role");
    }
}
