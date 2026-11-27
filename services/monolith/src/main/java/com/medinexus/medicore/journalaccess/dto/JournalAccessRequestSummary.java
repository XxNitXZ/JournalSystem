package com.medinexus.medicore.journalaccess.dto;

import java.time.Instant;

import com.medinexus.medicore.journalaccess.JournalAccessStatus;

/**
 * One row of the doctors' review list: the PATIENT account (names, personnummer, e-mail) and the status. The ID
 * is only used to open the request; MediPortal does not display it. No FHIR ID is included.
 */
public record JournalAccessRequestSummary(Long id, String givenName, String familyName, String personnummer,
        String email, JournalAccessStatus status, Instant createdAt, Instant reviewedAt) {
}
