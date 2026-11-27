package com.medinexus.medicore.auth.dto;

import com.medinexus.medicore.auth.entity.Role;

/**
 * The signed-in user's own account ({@code GET/PATCH /api/auth/profile}). Names and personnummer are read-only after
 * registration; e-mail and phone number can be changed by the account holder. Never contains the password hash or
 * FHIR identifiers, and is only ever returned for the session's own account.
 */
public record AccountProfileDto(String givenName, String familyName, String personnummer, String email,
        String phoneNumber, Role role) {
}
