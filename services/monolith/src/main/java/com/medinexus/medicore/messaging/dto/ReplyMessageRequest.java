package com.medinexus.medicore.messaging.dto;

import java.util.Map;
import java.util.Set;

public record ReplyMessageRequest(String content) {
    public static ReplyMessageRequest from(Map<String, Object> body) {
        if (body == null || !Set.of("content").containsAll(body.keySet())
                || body.get("content") != null && !(body.get("content") instanceof String)) {
            throw new IllegalArgumentException("Invalid message request");
        }
        return new ReplyMessageRequest((String) body.get("content"));
    }
}
