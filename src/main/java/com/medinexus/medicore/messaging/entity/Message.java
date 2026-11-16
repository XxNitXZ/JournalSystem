package com.medinexus.medicore.messaging.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "messages")
public class Message {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sender_id",updatable = false, nullable = false)
    private Long senderId;

    @Column(name = "recipient_id",updatable = false, nullable = false)
    private Long recipientId;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Message() {
    }

    public Message(Long senderId, Long recipientId, String content) {
        this.senderId = senderId;
        this.recipientId = recipientId;
        this.content = content;
    }

    @PrePersist
    private void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getSenderId() { return senderId; }
    public Long getRecipientId() { return recipientId; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
}
