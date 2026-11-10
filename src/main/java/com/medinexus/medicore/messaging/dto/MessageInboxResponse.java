package com.medinexus.medicore.messaging.dto;

import java.time.Instant;

public record MessageInboxResponse(Long patientId, String displayName, Long messageCount, Instant lastMessageAt) {
}
