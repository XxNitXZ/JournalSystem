package com.medinexus.medicore.messaging.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.medinexus.medicore.messaging.entity.Message;

import jakarta.persistence.EntityManager;

/** Real PostgreSQL persistence and constraints; every test rolls back its synthetic data. */
@SpringBootTest
@Transactional
class MessageRepositoryIntegrationTest {
    @Autowired private MessageRepository repository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    @Test
    void persistsMessagesAndRepliesWithBackendTimestamps() {
        Long patient = account("PATIENT");
        Long doctor = account("DOCTOR");
        Instant before = Instant.now();
        Message message = repository.saveAndFlush(new Message(patient, doctor, "Synthetic question"));
        Instant created = message.getCreatedAt();
        assertThat(created).isBetween(before, Instant.now());
        Long id = message.getId();
        assertThat(id).isPositive();
        repository.saveAndFlush(new Message(doctor, patient, "Synthetic reply"));
        entityManager.clear();
        Message loaded = repository.findById(id).orElseThrow();
        assertThat(loaded.getSenderId()).isEqualTo(patient);
        assertThat(loaded.getRecipientId()).isEqualTo(doctor);
        assertThat(loaded.getContent()).isEqualTo("Synthetic question");
        // PostgreSQL stores microsecond precision.
        assertThat(Duration.between(created, loaded.getCreatedAt()).abs()).isLessThanOrEqualTo(Duration.ofNanos(1000));
    }

    @Test
    void messageFieldsCannotBeChangedByJpaUpdate() {
        Message message = repository.saveAndFlush(new Message(account("PATIENT"), account("STAFF"), "Synthetic"));
        Long id = message.getId();
        entityManager.clear();
        Message loaded = repository.findById(id).orElseThrow();
        Instant original = loaded.getCreatedAt();
        ReflectionTestUtils.setField(loaded, "createdAt", Instant.EPOCH);
        // Simulate attempted changes to the managed entity; none may reach the database.
        ReflectionTestUtils.setField(loaded, "senderId", message.getRecipientId());
        ReflectionTestUtils.setField(loaded, "recipientId", message.getSenderId());
        ReflectionTestUtils.setField(loaded, "content", "Synthetic revised content");
        repository.flush();
        entityManager.clear();
        Message reloaded = repository.findById(id).orElseThrow();
        assertThat(reloaded.getCreatedAt()).isEqualTo(original);
        assertThat(reloaded.getSenderId()).isEqualTo(message.getSenderId());
        assertThat(reloaded.getRecipientId()).isEqualTo(message.getRecipientId());
        assertThat(reloaded.getContent()).isEqualTo("Synthetic");
    }

    @Test
    void databaseSuppliesTimestampWhenOmittedAndAcceptsMaximumContent() {
        Long id = insert(account("PATIENT"), account("STAFF"), "x".repeat(5000));
        Message loaded = repository.findById(id).orElseThrow();
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getContent()).hasSize(5000);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\r\n"})
    void databaseRejectsMissingOrBlankContent(String content) {
        Long sender = account("PATIENT");
        Long recipient = account("DOCTOR");
        rejects(() -> insert(sender, recipient, content));
    }

    @Test
    void databaseRejectsOversizedContent() {
        Long sender = account("PATIENT");
        Long recipient = account("STAFF");
        rejects(() -> insert(sender, recipient, "x".repeat(5001)));
    }

    @Test
    void databaseRejectsSelfMessages() {
        Long sender = account("PATIENT");
        rejects(() -> insert(sender, sender, "Synthetic"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void databaseRejectsMissingParticipants(boolean senderMissing) {
        Long existing = account("PATIENT");
        rejects(() -> insert(senderMissing ? null : existing, senderMissing ? existing : null, "Synthetic"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void databaseRejectsUnknownParticipants(boolean senderMissing) {
        Long existing = account("PATIENT");
        rejects(() -> insert(senderMissing ? -1L : existing, senderMissing ? existing : -1L, "Synthetic"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void participantDeletionIsRestricted(boolean deleteSender) {
        Long sender = account("PATIENT");
        Long recipient = account("DOCTOR");
        insert(sender, recipient, "Synthetic");
        rejects(() -> jdbc.update("DELETE FROM users WHERE id = ?", deleteSender ? sender : recipient));
    }

    @Test
    void databaseRejectsExplicitNullTimestamp() {
        Long sender = account("PATIENT");
        Long recipient = account("DOCTOR");
        rejects(() -> jdbc.update("INSERT INTO messages (sender_id, recipient_id, content, created_at) VALUES (?, ?, ?, NULL)",
                sender, recipient, "Synthetic"));
    }

    private Long account(String role) {
        return jdbc.queryForObject("INSERT INTO users (email, password_hash, role) VALUES (?, ?, ?) RETURNING id",
                Long.class, UUID.randomUUID() + "@example.test", "synthetic-hash", role);
    }

    private Long insert(Long sender, Long recipient, String content) {
        return jdbc.queryForObject("INSERT INTO messages (sender_id, recipient_id, content) VALUES (?, ?, ?) RETURNING id",
                Long.class, sender, recipient, content);
    }

    private void rejects(Runnable write) {
        assertThatThrownBy(write::run).isInstanceOf(DataIntegrityViolationException.class);
    }
}
