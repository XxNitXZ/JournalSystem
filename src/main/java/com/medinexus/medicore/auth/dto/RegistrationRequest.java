package com.medinexus.medicore.auth.dto;

import com.medinexus.medicore.auth.entity.Role;

public record RegistrationRequest(String email, String password, Role role) {
}
