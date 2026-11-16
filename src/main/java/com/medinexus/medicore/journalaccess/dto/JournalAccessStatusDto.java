package com.medinexus.medicore.journalaccess.dto;

/**
 * The signed-in PATIENT's journal access: {@code NONE} (no matching record found yet), {@code PENDING},
 * {@code APPROVED} (the account is linked to its record) or {@code REJECTED}. Contains no patient data.
 */
public record JournalAccessStatusDto(String status) {
}
