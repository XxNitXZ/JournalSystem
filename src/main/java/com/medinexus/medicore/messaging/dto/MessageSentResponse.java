package com.medinexus.medicore.messaging.dto;

import java.time.Instant;

public record MessageSentResponse(Long id, Instant createdAt) {
}
