package com.medinexus.medicore.messaging.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.medinexus.medicore.messaging.entity.Message;

/**
 * Messages only. In Lab 1 the list queries joined {@code users} to get names and filter by role; accounts now live
 * in Identity's schema, so {@link #findCounterparts} groups by the other person's id, and {@code MessageService}
 * gets names and roles from Identity (D4).
 */
public interface MessageRepository extends JpaRepository<Message, Long> {

    /** Everyone one account has written to or heard from, with the totals of that conversation. */
    interface CounterpartSummary {
        /** The other account in the conversation. */
        Long getCounterpartId();

        /** Messages in both directions. */
        Long getMessageCount();

        /** Messages the other account sent to this one; the inbox shows a patient only once this is above 0. */
        Long getIncomingCount();

        Instant getLastMessageAt();
    }

    @Query("""
            SELECT c.counterpartId AS counterpartId, COUNT(*) AS messageCount,
                   SUM(c.incoming) AS incomingCount, MAX(c.createdAt) AS lastMessageAt
            FROM (SELECT CASE WHEN m.senderId = :accountId THEN m.recipientId ELSE m.senderId END AS counterpartId,
                         CASE WHEN m.recipientId = :accountId THEN 1L ELSE 0L END AS incoming,
                         m.createdAt AS createdAt
                  FROM Message m
                  WHERE m.senderId = :accountId OR m.recipientId = :accountId) c
            GROUP BY c.counterpartId
            ORDER BY MAX(c.createdAt) DESC, c.counterpartId ASC
            """)
    List<CounterpartSummary> findCounterparts(@Param("accountId") Long accountId);

    boolean existsBySenderIdAndRecipientId(Long senderId, Long recipientId);

    @Query("""
            SELECT m FROM Message m
            WHERE (m.senderId = :patientId AND m.recipientId = :professionalId)
               OR (m.senderId = :professionalId AND m.recipientId = :patientId)
            ORDER BY m.createdAt ASC, m.id ASC
            """)
    List<Message> findThread(@Param("patientId") Long patientId, @Param("professionalId") Long professionalId);
}
