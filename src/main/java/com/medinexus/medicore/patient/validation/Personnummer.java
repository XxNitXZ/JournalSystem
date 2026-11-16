package com.medinexus.medicore.patient.validation;

import java.time.DateTimeException;
import java.time.LocalDate;

/**
 * Validation of 12-digit Swedish personal identity numbers (personnummer), {@code YYYYMMDDXXXX}, as MediCore uses
 * them: the account holder's and the Patient's identifier, matched exactly between PostgreSQL and HAPI FHIR.
 *
 * <p>Rules: exactly twelve ASCII digits, and the first eight digits are a real calendar date (the date of birth).
 * The last four digits are <strong>not</strong> checked: MediCore deliberately does not validate the Luhn check
 * digit, so that freely chosen fictional numbers can be used; there is no allowlist either. Whether the birth date
 * may be in the future is decided by the callers ({@link #normalize} rejects it). Coordination numbers
 * (samordningsnummer, day + 60) are rejected because their day is not a date.
 *
 * <p>Methods never include the number in exception messages.
 */
public final class Personnummer {

    /** FHIR identifier system for Swedish personnummer. */
    public static final String SYSTEM = "http://electronichealth.se/identifier/personnummer";

    private Personnummer() {
    }

    /**
     * The normalized 12-digit form of {@code YYYYMMDDXXXX} or {@code YYYYMMDD-XXXX} (surrounding whitespace
     * ignored), or null if the value is not a valid personnummer or its date of birth is in the future
     * ({@code today}). Nothing else is changed. Never includes the value in any message.
     */
    public static String normalize(String value, LocalDate today) {
        String canonical = canonical(value);
        if (canonical == null || dateOf(canonical).isAfter(today)) return null;
        return canonical;
    }

    /**
     * The canonical 12-digit form of exactly {@code YYYYMMDDXXXX} or {@code YYYYMMDD-XXXX} (the hyphen only between
     * date and the last four digits; surrounding whitespace ignored) with a real calendar date, otherwise null. No
     * other character is removed, and the future-date rule is left to the callers ({@link #normalize} applies it).
     */
    public static String canonical(String value) {
        if (value == null) return null;
        String stripped = value.strip();
        if (stripped.matches("[0-9]{8}-[0-9]{4}")) stripped = stripped.substring(0, 8) + stripped.substring(9);
        return isValid(stripped) ? stripped : null;
    }

    /**
     * The identifier values under which a Patient with this canonical personnummer may be stored in FHIR: the
     * 12-digit form (as MediCore writes it) and {@code YYYYMMDD-XXXX} (as on the KTH server). For exact token searches.
     *
     * @throws IllegalArgumentException if the value is not a valid canonical personnummer
     */
    public static java.util.List<String> identifierValues(String canonical) {
        if (!isValid(canonical)) throw new IllegalArgumentException("A valid Swedish personnummer is required");
        return java.util.List.of(canonical, canonical.substring(0, 8) + "-" + canonical.substring(8));
    }

    /** True for exactly twelve ASCII digits whose first eight form a real calendar date. No check-digit test. */
    public static boolean isValid(String value) {
        return value != null && value.matches("[0-9]{12}") && dateOf(value) != null;
    }

    /**
     * The date of birth encoded in a valid personnummer.
     *
     * @throws IllegalArgumentException if the value is not a valid personnummer (the value is not in the message)
     */
    public static LocalDate birthDate(String value) {
        if (!isValid(value)) {
            throw new IllegalArgumentException("A valid Swedish personnummer is required");
        }
        return dateOf(value);
    }

    private static LocalDate dateOf(String digits) {
        try {
            return LocalDate.of(Integer.parseInt(digits.substring(0, 4)), Integer.parseInt(digits.substring(4, 6)),
                    Integer.parseInt(digits.substring(6, 8)));
        } catch (DateTimeException exception) {
            return null;
        }
    }
}
