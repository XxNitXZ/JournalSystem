package com.medinexus.medicore.journalaccess;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface JournalAccessRequestRepository extends JpaRepository<JournalAccessRequest, Long> {

    long countByStatus(JournalAccessStatus status);

    /** The account's open request (AWAITING_RECORD or PENDING); there is at most one (unique index). */
    Optional<JournalAccessRequest> findFirstByUserIdAndStatusIn(Long userId, Collection<JournalAccessStatus> statuses);

    Optional<JournalAccessRequest> findFirstByUserIdAndStatus(Long userId, JournalAccessStatus status);

    /** Pending requests first, then those awaiting a record, then decided ones; the most recent first. */
    @Query("""
            SELECT r FROM JournalAccessRequest r
            ORDER BY CASE r.status WHEN com.medinexus.medicore.journalaccess.JournalAccessStatus.PENDING THEN 0
                     WHEN com.medinexus.medicore.journalaccess.JournalAccessStatus.AWAITING_RECORD THEN 1 ELSE 2 END,
                     r.createdAt DESC, r.id DESC
            """)
    List<JournalAccessRequest> findAllForReview();

    /** The account's most recent request, if any. */
    Optional<JournalAccessRequest> findFirstByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    /**
     * Decides a PENDING request; returns 1, or 0 if it is no longer pending (for example decided concurrently).
     * The row lock taken by this update serializes concurrent reviews of the same request.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE JournalAccessRequest r SET r.status = :decision, r.reviewedBy = :reviewer, r.reviewedAt = :reviewedAt
            WHERE r.id = :id AND r.status = com.medinexus.medicore.journalaccess.JournalAccessStatus.PENDING
            """)
    int decideIfPending(@Param("id") Long id, @Param("decision") JournalAccessStatus decision,
            @Param("reviewer") Long reviewer, @Param("reviewedAt") Instant reviewedAt);

    /**
     * Attaches a registered FHIR Patient to a request that is still AWAITING_RECORD and makes it PENDING; returns 1,
     * or 0 if the request is no longer awaiting (for example attached concurrently).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE JournalAccessRequest r SET r.fhirPatientId = :fhirPatientId,
                r.status = com.medinexus.medicore.journalaccess.JournalAccessStatus.PENDING
            WHERE r.id = :id AND r.status = com.medinexus.medicore.journalaccess.JournalAccessStatus.AWAITING_RECORD
            """)
    int attachRecordIfAwaiting(@Param("id") Long id, @Param("fhirPatientId") String fhirPatientId);
}
