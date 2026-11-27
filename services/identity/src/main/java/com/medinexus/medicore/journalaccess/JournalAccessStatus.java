package com.medinexus.medicore.journalaccess;

/**
 * Lifecycle of a journal access request, which only a PATIENT creates, by asking for access.
 * <ul>
 *   <li>AWAITING_RECORD: requested, but no registered FHIR Patient with the account's personnummer is known yet
 *       (or matching could not run because the FHIR server was unavailable);</li>
 *   <li>PENDING: a registered FHIR Patient with the same personnummer is attached; a doctor must review it;</li>
 *   <li>APPROVED / REJECTED: decided by a doctor.</li>
 * </ul>
 * Only PENDING requests can be approved or rejected.
 */
public enum JournalAccessStatus {
    AWAITING_RECORD, PENDING, APPROVED, REJECTED;

    /** AWAITING_RECORD and PENDING: an account has at most one open request. */
    public boolean isOpen() {
        return this == AWAITING_RECORD || this == PENDING;
    }
}
