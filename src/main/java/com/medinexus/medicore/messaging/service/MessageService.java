package com.medinexus.medicore.messaging.service;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.messaging.dto.MessageSentResponse;
import com.medinexus.medicore.messaging.dto.MessageConversationResponse;
import com.medinexus.medicore.messaging.dto.MessageHistoryResponse;
import com.medinexus.medicore.messaging.dto.MessageRecipientResponse;
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

    /** Directory access also checks the persisted role, rather than trusting the session snapshot. */
    @Transactional(readOnly = true)
    public List<MessageRecipientResponse> recipients(UserDto principal) {
        if (principal == null || principal.id() == null || principal.id() <= 0) throw denied();
        User account = users.findById(principal.id()).orElseThrow(MessageService::denied);
        if (account.getRole() != Role.PATIENT) throw denied();
        return users.findByRoleIn(List.of(Role.DOCTOR, Role.STAFF)).stream()
                .map(user -> new MessageRecipientResponse(user.getId(), displayName(user), user.getRole()))
                .sorted(Comparator.comparing(MessageRecipientResponse::displayName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(MessageRecipientResponse::displayName)
                        .thenComparing(MessageRecipientResponse::role)
                        .thenComparing(MessageRecipientResponse::id))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MessageConversationResponse> conversations(UserDto principal) {
        requirePatient(principal);
        return messages.findConversations(principal.id()).stream().map(summary -> {
            User professional = summary.getProfessional();
            return new MessageConversationResponse(professional.getId(), displayName(professional),
                    professional.getRole(), summary.getMessageCount(), summary.getLastMessageAt());
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<MessageHistoryResponse> thread(UserDto principal, Long recipientId) {
        requirePatient(principal);
        if (recipientId == null || recipientId <= 0) throw new IllegalArgumentException("Invalid recipient");
        if (recipientId.equals(principal.id())) throw denied();
        User professional = users.findById(recipientId).orElseThrow(MessageRecipientNotFoundException::new);
        if (professional.getRole() != Role.DOCTOR && professional.getRole() != Role.STAFF) throw denied();
        // Always scoped to this patient. No messages with this professional is a safe, empty thread.
        return messages.findThread(principal.id(), recipientId).stream()
                .map(message -> new MessageHistoryResponse(message.getId(), message.getSenderId(),
                        message.getRecipientId(), message.getContent(), message.getCreatedAt())).toList();
    }

    private void requirePatient(UserDto principal) {
        if (principal == null || principal.id() == null || principal.id() <= 0) throw denied();
        User account = users.findById(principal.id()).orElseThrow(MessageService::denied);
        if (account.getRole() != Role.PATIENT) throw denied();
    }

    private static String displayName(User user) {
        String name = Stream.of(user.getGivenName(), user.getFamilyName())
                .filter(part -> part != null && !part.isBlank()).map(String::strip)
                .collect(java.util.stream.Collectors.joining(" "));
        if (!name.isEmpty()) return name;
        return (user.getRole() == Role.DOCTOR ? "Doctor" : "Staff") + " #" + user.getId();
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Access denied");
    }
}
