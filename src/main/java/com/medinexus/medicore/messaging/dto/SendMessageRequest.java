package com.medinexus.medicore.messaging.dto;

import java.util.Map;
import java.util.Set;

public record SendMessageRequest(Long recipientId, String content) {
    public static SendMessageRequest from(Map<String, Object> body) {
        if (body == null || !Set.of("recipientId", "content").containsAll(body.keySet())) {
            throw new IllegalArgumentException("Invalid message request");
        }
        Object id = body.get("recipientId");
        Object content = body.get("content");
        if (id != null && !(id instanceof Integer) && !(id instanceof Long)
                || content != null && !(content instanceof String)) {
            throw new IllegalArgumentException("Invalid message request");
        }
        return new SendMessageRequest(id == null ? null : ((Number) id).longValue(), (String) content);
    }
}
