package com.medinexus.medicore.messaging.service;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.messaging.dto.MessageSentResponse;
import com.medinexus.medicore.messaging.dto.SendMessageRequest;
import com.medinexus.medicore.messaging.entity.Message;
import com.medinexus.medicore.messaging.repository.MessageRepository;

@Service
public class MessageService {
    private final UserRepository users;
    private final MessageRepository messages;

    public MessageService(UserRepository users, MessageRepository messages) {
        this.users = users;
        this.messages = messages;
    }

    /** Principal comes exclusively from the authenticated security context. */
    @Transactional
    public MessageSentResponse send(UserDto principal, SendMessageRequest request) {
        if (principal == null || principal.id() == null || principal.id() <= 0) throw denied();
        User sender = users.findById(principal.id()).orElseThrow(MessageService::denied);
        if (sender.getRole() != Role.PATIENT) throw denied();
        if (request == null || request.recipientId() == null || request.recipientId() <= 0
                || request.content() == null || request.content().isBlank()
                || request.content().codePointCount(0, request.content().length()) > 5000) {
            throw new IllegalArgumentException("Invalid message request");
        }
        if (principal.id().equals(request.recipientId())) throw denied();
        User recipient = users.findById(request.recipientId()).orElseThrow(MessageRecipientNotFoundException::new);
        if (recipient.getRole() != Role.DOCTOR && recipient.getRole() != Role.STAFF) throw denied();
        Message saved = messages.saveAndFlush(new Message(principal.id(), request.recipientId(), request.content()));
        return new MessageSentResponse(saved.getId(), saved.getCreatedAt());
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Access denied");
    }
}
