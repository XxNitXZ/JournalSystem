package com.medinexus.medicore.journalaccess.dto;

import java.time.Instant;
import java.util.List;

import com.medinexus.medicore.journalaccess.JournalAccessStatus;

/**
 * A request under review: the PATIENT account from PostgreSQL next to the matching Patient record from HAPI FHIR
 * (none while the request is AWAITING_RECORD).
 * No database or FHIR IDs other than the request ID (used only for the review actions).
 *
 * @param record              the FHIR Patient, or null if it could not be read
 * @param recordStatus        whether the FHIR Patient could be read
 * @param personnummerMatches true if the account and the FHIR Patient have the same personnummer
 */
public record JournalAccessRequestDetail(Long id, JournalAccessStatus status, Instant createdAt, Instant reviewedAt,
        Account account, PatientRecord record, RecordStatus recordStatus, boolean personnummerMatches) {

    public record Account(String givenName, String familyName, String personnummer, String email) {
    }

    public record PatientRecord(List<String> givenNames, String familyName, String personnummer, String gender,
            String birthDate) {
        public PatientRecord {
            givenNames = givenNames == null ? List.of() : List.copyOf(givenNames);
        }
    }

    public enum RecordStatus {
        AVAILABLE, NOT_FOUND, UNAVAILABLE,
        /** AWAITING_RECORD: no registered FHIR Patient with the account's personnummer yet. */
        NOT_REGISTERED
    }
}
