package com.medinexus.medicore.messaging.service;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.identityapi.IdentityClient;
import com.medinexus.medicore.identityapi.IdentityClient.DirectoryUser;
import com.medinexus.medicore.messaging.dto.MessageConversationResponse;
import com.medinexus.medicore.messaging.dto.MessageHistoryResponse;
import com.medinexus.medicore.messaging.dto.MessageInboxResponse;
import com.medinexus.medicore.messaging.dto.MessageRecipientResponse;
import com.medinexus.medicore.messaging.dto.MessageSentResponse;
import com.medinexus.medicore.messaging.dto.ReplyMessageRequest;
import com.medinexus.medicore.messaging.dto.SendMessageRequest;
import com.medinexus.medicore.messaging.entity.Message;
import com.medinexus.medicore.messaging.repository.MessageRepository;
import com.medinexus.medicore.messaging.repository.MessageRepository.CounterpartSummary;

/**
 * Messages between patients and doctors/staff (moved from the monolith in NIT-336).
 *
 * <p>Two sources of truth: the caller's own id and role come from the verified JWT (D1); everything about the
 * <b>other</b> account (exists? which role? which name?) comes live from Identity through {@link IdentityClient}
 * (D4). If Identity can't answer, the call fails (503/502) and nothing is saved.
 */
@Service
public class MessageService {
    private final IdentityClient identity;
    private final MessageRepository messages;

    public MessageService(IdentityClient identity, MessageRepository messages) {
        this.identity = identity;
        this.messages = messages;
    }

    /** A patient writes to a doctor or staff member. The sender is always the caller. */
    @Transactional
    public MessageSentResponse send(UserDto principal, SendMessageRequest request) {
        requirePatient(principal);
        if (request == null || request.recipientId() == null || request.recipientId() <= 0) {
            throw new IllegalArgumentException("Invalid message request");
        }
        validateContent(request.content());
        if (principal.id().equals(request.recipientId())) throw denied();
        requireCounterpart(request.recipientId(), DirectoryUser::isProfessional);
        Message saved = messages.saveAndFlush(new Message(principal.id(), request.recipientId(), request.content()));
        return new MessageSentResponse(saved.getId(), saved.getCreatedAt());
    }

    /** The doctors and staff a patient can write to, sorted by name (I2). */
    public List<MessageRecipientResponse> recipients(UserDto principal) {
        requirePatient(principal);
        return identity.usersWithRoles(Role.DOCTOR, Role.STAFF).stream().filter(DirectoryUser::isProfessional)
                .map(user -> new MessageRecipientResponse(user.id(), user.displayName(), user.role())).toList();
    }

    /** A patient's conversations with doctors and staff, newest first. */
    @Transactional(readOnly = true)
    public List<MessageConversationResponse> conversations(UserDto principal) {
        requirePatient(principal);
        List<CounterpartSummary> summaries = messages.findCounterparts(principal.id());
        Map<Long, DirectoryUser> directory = directory(summaries);
        return summaries.stream()
                .filter(summary -> hasRole(directory, summary, DirectoryUser::isProfessional))
                .map(summary -> {
                    DirectoryUser professional = directory.get(summary.getCounterpartId());
                    return new MessageConversationResponse(professional.id(), professional.displayName(),
                            professional.role(), summary.getMessageCount(), summary.getLastMessageAt());
                }).toList();
    }

    /** One conversation from the patient's side; always scoped to the caller. */
    @Transactional(readOnly = true)
    public List<MessageHistoryResponse> thread(UserDto principal, Long recipientId) {
        requirePatient(principal);
        if (recipientId == null || recipientId <= 0) throw new IllegalArgumentException("Invalid recipient");
        if (recipientId.equals(principal.id())) throw denied();
        requireCounterpart(recipientId, DirectoryUser::isProfessional);
        // No messages with this professional is a safe, empty thread.
        return history(messages.findThread(principal.id(), recipientId));
    }

    /** A doctor's or staff member's inbox: patients who have written to them, newest first. */
    @Transactional(readOnly = true)
    public List<MessageInboxResponse> inbox(UserDto principal) {
        requireProfessional(principal);
        List<CounterpartSummary> summaries = messages.findCounterparts(principal.id()).stream()
                .filter(summary -> summary.getIncomingCount() > 0).toList();
        Map<Long, DirectoryUser> directory = directory(summaries);
        return summaries.stream()
                .filter(summary -> hasRole(directory, summary, DirectoryUser::isPatient))
                .map(summary -> new MessageInboxResponse(summary.getCounterpartId(),
                        directory.get(summary.getCounterpartId()).displayName(), summary.getMessageCount(),
                        summary.getLastMessageAt()))
                .toList();
    }

    /** One conversation from the professional's side; empty until the patient has written first. */
    @Transactional(readOnly = true)
    public List<MessageHistoryResponse> inboxThread(UserDto principal, Long patientId) {
        requireProfessional(principal);
        if (patientId == null || patientId <= 0) throw new IllegalArgumentException("Invalid patient");
        if (patientId.equals(principal.id())) throw denied();
        requireCounterpart(patientId, DirectoryUser::isPatient);
        if (!messages.existsBySenderIdAndRecipientId(patientId, principal.id())) return List.of();
        return history(messages.findThread(patientId, principal.id()));
    }

    /** A professional answers a patient who has written to them; a new conversation can't be started this way. */
    @Transactional
    public MessageSentResponse reply(UserDto principal, Long patientId, ReplyMessageRequest request) {
        requireProfessional(principal);
        if (patientId == null || patientId <= 0 || request == null) {
            throw new IllegalArgumentException("Invalid message request");
        }
        validateContent(request.content());
        if (patientId.equals(principal.id())) throw denied();
        requireCounterpart(patientId, DirectoryUser::isPatient);
        if (!messages.existsBySenderIdAndRecipientId(patientId, principal.id())) throw denied();
        Message saved = messages.saveAndFlush(new Message(principal.id(), patientId, request.content()));
        return new MessageSentResponse(saved.getId(), saved.getCreatedAt());
    }

    /** One I1 call for every counterpart of a list. */
    private Map<Long, DirectoryUser> directory(List<CounterpartSummary> summaries) {
        return identity.usersById(summaries.stream().map(CounterpartSummary::getCounterpartId).toList());
    }

    /** Leaves out accounts Identity doesn't know, and accounts with the wrong role (the Lab 1 SQL role filter). */
    private static boolean hasRole(Map<Long, DirectoryUser> directory, CounterpartSummary summary,
            Predicate<DirectoryUser> role) {
        DirectoryUser user = directory.get(summary.getCounterpartId());
        return user != null && role.test(user);
    }

    /** The other account must exist (else 404) and have the expected role (else 403). */
    private void requireCounterpart(Long id, Predicate<DirectoryUser> role) {
        DirectoryUser user = identity.user(id).orElseThrow(MessageRecipientNotFoundException::new);
        if (!role.test(user)) throw denied();
    }

    private static List<MessageHistoryResponse> history(List<Message> thread) {
        return thread.stream().map(message -> new MessageHistoryResponse(message.getId(), message.getSenderId(),
                message.getRecipientId(), message.getContent(), message.getCreatedAt())).toList();
    }

    private static void validateContent(String content) {
        if (content == null || content.isBlank() || content.codePointCount(0, content.length()) > 5000) {
            throw new IllegalArgumentException("Invalid message request");
        }
    }

    /** The caller's role comes from the signed token (D1); SecurityConfig has already checked it per route. */
    private static void requirePatient(UserDto principal) {
        if (!signedIn(principal) || principal.role() != Role.PATIENT) throw denied();
    }

    private static void requireProfessional(UserDto principal) {
        if (!signedIn(principal) || principal.role() != Role.DOCTOR && principal.role() != Role.STAFF) throw denied();
    }

    private static boolean signedIn(UserDto principal) {
        return principal != null && principal.id() != null && principal.id() > 0;
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Access denied");
    }
}
