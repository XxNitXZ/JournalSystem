package com.medinexus.medicore.messaging.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.medinexus.medicore.identityapi.FakeIdentity;
import com.medinexus.medicore.messaging.entity.Message;
import com.medinexus.medicore.messaging.repository.MessageRepository.CounterpartSummary;

import jakarta.persistence.EntityManager;

/**
 * Real PostgreSQL persistence and constraints in schema "messaging"; every test rolls back its synthetic data.
 * The Lab 1 tests for the foreign keys to users ("unknown participant", "deleting a participant is blocked") are gone:
 * those keys can't cross schemas (NIT-324). MessageService checks every recipient with Identity instead.
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "MESSAGING_DB_PASSWORD", matches = ".+")
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

    @Test
    void databaseRejectsExplicitNullTimestamp() {
        Long sender = account("PATIENT");
        Long recipient = account("DOCTOR");
        rejects(() -> jdbc.update("INSERT INTO messages (sender_id, recipient_id, content, created_at) VALUES (?, ?, ?, NULL)",
                sender, recipient, "Synthetic"));
    }

    /**
     * An account id. Messaging has no accounts table (they live in Identity), so any id will do; the role is only
     * there to keep the tests readable.
     */
    private Long account(String role) {
        return FakeIdentity.unknownId();
    }

    @Test
    void counterpartsGroupBothDirectionsCountIncomingAndSortNewestFirst() {
        Long me = account("DOCTOR");
        Long maja = account("PATIENT");
        Long ebba = account("PATIENT");
        Long ali = account("PATIENT");
        Long outsider = account("PATIENT");
        insertAt(maja, me, "2026-01-01T00:00:00Z");
        insertAt(me, maja, "2026-01-02T00:00:00Z");
        insertAt(me, ebba, "2026-01-03T00:00:00Z");
        insertAt(ali, me, "2026-01-03T00:00:00Z");
        insertAt(outsider, maja, "2026-01-04T00:00:00Z");
        List<CounterpartSummary> counterparts = repository.findCounterparts(me);
        // Same time: the lower id first, like the Lab 1 query.
        List<Long> sameTime = ebba < ali ? List.of(ebba, ali) : List.of(ali, ebba);
        assertThat(counterparts).extracting(CounterpartSummary::getCounterpartId)
                .containsExactly(sameTime.get(0), sameTime.get(1), maja);
        CounterpartSummary withMaja = counterparts.get(2);
        assertThat(withMaja.getMessageCount()).isEqualTo(2);
        assertThat(withMaja.getIncomingCount()).isEqualTo(1);
        assertThat(withMaja.getLastMessageAt()).isEqualTo(Instant.parse("2026-01-02T00:00:00Z"));
        assertThat(counterparts).filteredOn(summary -> summary.getCounterpartId().equals(ebba))
                .singleElement().satisfies(summary -> assertThat(summary.getIncomingCount()).isZero());
        assertThat(repository.findCounterparts(account("STAFF"))).isEmpty();
    }

    private void insertAt(Long sender, Long recipient, String time) {
        jdbc.update("INSERT INTO messages (sender_id, recipient_id, content, created_at) VALUES (?, ?, ?, ?)",
                sender, recipient, "Synthetic", java.sql.Timestamp.from(Instant.parse(time)));
    }

    private Long insert(Long sender, Long recipient, String content) {
        return jdbc.queryForObject("INSERT INTO messages (sender_id, recipient_id, content) VALUES (?, ?, ?) RETURNING id",
                Long.class, sender, recipient, content);
    }

    private void rejects(Runnable write) {
        assertThatThrownBy(write::run).isInstanceOf(DataIntegrityViolationException.class);
    }
}
