package com.medinexus.medicore.medicalrecord.dto;

import java.util.List;
import java.util.Locale;

import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;

/**
 * Internal creation input; no caller-selected resource ID or subject URL. Since US-05.02 also the optional
 * {@code recordedDate} (FHIR dateTime), {@code encounterId} ({@code Condition.encounter}) and {@code recorderId}
 * ({@code Condition.recorder}, a Practitioner); these are logical IDs, never URLs.
 */
public record CreateConditionRequest(String patientId, Concept code, ClinicalStatus clinicalStatus,
        VerificationStatus verificationStatus, List<Concept> category, Concept severity,
        String onsetDateTime, String abatementDateTime, String recordedDate, String encounterId, String recorderId) {

    public CreateConditionRequest {
        category = category == null ? List.of() : List.copyOf(category);
    }

    /** The Epic 3 form without recorded date, Encounter and recorder. */
    public CreateConditionRequest(String patientId, Concept code, ClinicalStatus clinicalStatus,
            VerificationStatus verificationStatus, List<Concept> category, Concept severity,
            String onsetDateTime, String abatementDateTime) {
        this(patientId, code, clinicalStatus, verificationStatus, category, severity, onsetDateTime,
                abatementDateTime, null, null, null);
    }

    public enum ClinicalStatus {
        ACTIVE, RECURRENCE, RELAPSE, INACTIVE, REMISSION, RESOLVED;

        public static final String SYSTEM = "http://terminology.hl7.org/CodeSystem/condition-clinical";
        public String code() { return name().toLowerCase(Locale.ROOT); }
    }

    public enum VerificationStatus {
        UNCONFIRMED, PROVISIONAL, DIFFERENTIAL, CONFIRMED, REFUTED, ENTERED_IN_ERROR;

        public static final String SYSTEM = "http://terminology.hl7.org/CodeSystem/condition-ver-status";
        public String code() { return name().toLowerCase(Locale.ROOT).replace('_', '-'); }
    }
}
