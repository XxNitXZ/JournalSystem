package com.medinexus.medicore.messaging.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.messaging.entity.Message;

public interface MessageRepository extends JpaRepository<Message, Long> {
    interface ConversationSummary {
        User getProfessional();
        Long getMessageCount();
        Instant getLastMessageAt();
    }

    interface InboxSummary {
        User getPatient();
        Long getMessageCount();
        Instant getLastMessageAt();
    }

    @Query("""
            SELECT u AS patient, COUNT(m) AS messageCount, MAX(m.createdAt) AS lastMessageAt
            FROM Message m, User u
            WHERE ((m.senderId = :professionalId AND m.recipientId = u.id)
                OR (m.recipientId = :professionalId AND m.senderId = u.id))
              AND u.role = com.medinexus.medicore.auth.entity.Role.PATIENT
              AND EXISTS (SELECT incoming.id FROM Message incoming
                  WHERE incoming.senderId = u.id AND incoming.recipientId = :professionalId)
            GROUP BY u
            ORDER BY MAX(m.createdAt) DESC, u.id ASC
            """)
    List<InboxSummary> findInbox(@Param("professionalId") Long professionalId);

    boolean existsBySenderIdAndRecipientId(Long senderId, Long recipientId);

    @Query("""
            SELECT u AS professional, COUNT(m) AS messageCount, MAX(m.createdAt) AS lastMessageAt
            FROM Message m, User u
            WHERE ((m.senderId = :patientId AND m.recipientId = u.id)
                OR (m.recipientId = :patientId AND m.senderId = u.id))
              AND u.role IN (com.medinexus.medicore.auth.entity.Role.DOCTOR,
                             com.medinexus.medicore.auth.entity.Role.STAFF)
            GROUP BY u
            ORDER BY MAX(m.createdAt) DESC, u.id ASC
            """)
    List<ConversationSummary> findConversations(@Param("patientId") Long patientId);

    @Query("""
            SELECT m FROM Message m
            WHERE (m.senderId = :patientId AND m.recipientId = :professionalId)
               OR (m.senderId = :professionalId AND m.recipientId = :patientId)
            ORDER BY m.createdAt ASC, m.id ASC
            """)
    List<Message> findThread(@Param("patientId") Long patientId, @Param("professionalId") Long professionalId);
}
