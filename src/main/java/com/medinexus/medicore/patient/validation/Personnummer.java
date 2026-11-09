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
        if (value == null) return null;
        String stripped = value.strip();
        if (stripped.matches("[0-9]{8}-[0-9]{4}")) stripped = stripped.replace("-", "");
        if (!isValid(stripped) || dateOf(stripped).isAfter(today)) return null;
        return stripped;
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
