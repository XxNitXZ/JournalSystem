package com.medinexus.medicore.auth.service;

/**
 * Validation and normalization of the optional account telephone number (registration and profile), Swedish or
 * international.
 *
 * <p>Accepted input: digits, optionally one leading {@code +}, and the separators space, {@code -}, {@code .}, and
 * parentheses, at most 32 characters. Normalization only removes those separators; it never changes digits, never
 * adds or removes a country code and never turns {@code 00} into {@code +}, so a valid international number is kept
 * as entered. The result must be an optional {@code +} followed by 6 to 15 digits (E.164 allows at most 15), which
 * is what the database constraint {@code ck_users_phone_number_format} also requires.
 */
public final class PhoneNumbers {

    private static final int MAX_INPUT_LENGTH = 32;

    private PhoneNumbers() {
    }

    /**
     * The normalized number, or null if the value is null or blank (no phone number).
     *
     * @throws IllegalArgumentException for an invalid number; the message never contains the value
     */
    public static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        String stripped = value.strip();
        if (stripped.length() > MAX_INPUT_LENGTH || !stripped.matches("\\+?[0-9 ().-]+")) {
            throw invalid();
        }
        String normalized = stripped.replaceAll("[ ().-]", "");
        if (!normalized.matches("\\+?[0-9]{6,15}")) throw invalid();
        return normalized;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("A valid phone number is required");
    }
}
