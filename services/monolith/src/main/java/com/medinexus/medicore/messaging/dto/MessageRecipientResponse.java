package com.medinexus.medicore.messaging.dto;

import com.medinexus.medicore.auth.entity.Role;

public record MessageRecipientResponse(Long id, String displayName, Role role) {
}
