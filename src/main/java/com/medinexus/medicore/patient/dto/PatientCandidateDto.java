package com.medinexus.medicore.patient.dto;

/**
 * An existing FHIR Patient found by the import search: readable demographics only (no raw resource), read from FHIR
 * and never stored.
 *
 * @param id                 the logical FHIR Patient ID, used for the import action and the profile link
 * @param name               official (otherwise first) name, or null
 * @param personnummer       the Swedish personnummer: the canonical 12 digits when valid (stored as
 *                           {@code YYYYMMDDXXXX} or {@code YYYYMMDD-XXXX}), the first value as stored when invalid,
 *                           null when missing or ambiguous
 * @param gender             FHIR gender code, or null
 * @param birthDate          birth date as stored, or null
 * @param registered         true if the Patient is already known to MediCore (registry or account link)
 * @param importable         true if it can be imported: it has a name and a birth date or an identifier, so that
 *                           clinicians can tell it apart
 * @param journalMatchable   true if it has exactly one valid personnummer in MediCore's identifier system, so that a
 *                           PATIENT account's journal access request can be matched to it; importable Patients without
 *                           one can still be used clinically
 * @param personnummerStatus {@code VALID}, {@code AMBIGUOUS} (several different or conflicting values),
 *                           {@code INVALID} or {@code MISSING}
 */
public record PatientCandidateDto(String id, String name, String personnummer, String gender, String birthDate,
        boolean registered, boolean importable, boolean journalMatchable, String personnummerStatus) {

    /** Without an explicit status: derived from the personnummer and journalMatchable. */
    public PatientCandidateDto(String id, String name, String personnummer, String gender, String birthDate,
            boolean registered, boolean importable, boolean journalMatchable) {
        this(id, name, personnummer, gender, birthDate, registered, importable, journalMatchable,
                journalMatchable ? "VALID" : personnummer == null ? "MISSING" : "INVALID");
    }
}
