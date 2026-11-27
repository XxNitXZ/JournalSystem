package com.medinexus.medicore.journalaccess;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A PATIENT account's request to access its journal, created only when the patient asks for it. It references the
 * FHIR Patient registered by MediCore with the same personnummer, or none while that Patient is not registered yet
 * (AWAITING_RECORD). Holds only IDs, the status and the review; no patient data.
 */
@Entity
@Table(name = "journal_access_requests")
public class JournalAccessRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "fhir_patient_id", length = 64)
    private String fhirPatientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private JournalAccessStatus status;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    protected JournalAccessRequest() {
    }

    /** A new PENDING request for a known FHIR Patient. */
    public JournalAccessRequest(Long userId, String fhirPatientId, Instant createdAt) {
        this.userId = userId;
        this.fhirPatientId = fhirPatientId;
        this.status = JournalAccessStatus.PENDING;
        this.createdAt = createdAt;
    }

    /** A new request that waits for its FHIR Patient to be registered. */
    public static JournalAccessRequest awaitingRecord(Long userId, Instant createdAt) {
        JournalAccessRequest request = new JournalAccessRequest(userId, null, createdAt);
        request.status = JournalAccessStatus.AWAITING_RECORD;
        return request;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getFhirPatientId() {
        return fhirPatientId;
    }

    public JournalAccessStatus getStatus() {
        return status;
    }

    public Long getReviewedBy() {
        return reviewedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }
}
