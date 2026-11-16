package com.medinexus.medicore.auth.dto;

import com.medinexus.medicore.auth.entity.Role;

/**
 * Public account registration; first name, last name and personnummer ({@code YYYYMMDDXXXX} or
 * {@code YYYYMMDD-XXXX}) are required for every new account, whatever the role. The phone number is optional.
 */
public record RegistrationRequest(String email, String password, Role role, String givenName, String familyName,
        String personnummer, String phoneNumber) {
}
