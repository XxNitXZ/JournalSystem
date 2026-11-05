package com.medinexus.medicore.auth.dto;

import com.medinexus.medicore.auth.entity.Role;

public record RegistrationResponse(Long id, String email, Role role) {
}
