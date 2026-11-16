package com.medinexus.medicore.auth.dto;

import com.medinexus.medicore.auth.entity.Role;

/**
 * The signed-in account as returned by registration, login and {@code /api/auth/me}.
 *
 * @param givenName  first name, or null for an account created before names were stored
 * @param familyName last name, or null for an account created before names were stored
 */
public record UserDto(Long id, String email, Role role, String givenName, String familyName) {

    /** An account without stored names (accounts created before names were required). */
    public UserDto(Long id, String email, Role role) {
        this(id, email, role, null, null);
    }
}
