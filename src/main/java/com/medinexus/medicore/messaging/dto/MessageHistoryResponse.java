package com.medinexus.medicore.messaging.dto;

import java.time.Instant;

public record MessageHistoryResponse(Long id, Long senderId, Long recipientId, String content, Instant createdAt) {
}
