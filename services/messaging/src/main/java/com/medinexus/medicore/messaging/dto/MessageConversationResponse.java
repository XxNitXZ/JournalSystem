package com.medinexus.medicore.messaging.dto;

import java.time.Instant;
import com.medinexus.medicore.auth.entity.Role;

public record MessageConversationResponse(Long recipientId, String displayName, Role role,
        Long messageCount, Instant lastMessageAt) {
}
