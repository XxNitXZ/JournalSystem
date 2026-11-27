package com.medinexus.medicore.auth.service;

/**
 * Validation of an account holder's first and last name, shared by registration and profile editing.
 *
 * <p>A name is stripped of surrounding whitespace and must then be nonblank, at most 100 characters (code points)
 * and free of control characters. Error messages never contain the submitted value.
 */
public final class AccountNames {

    public static final int MAX_LENGTH = 100;

    private AccountNames() {
    }

    /** @throws IllegalArgumentException "A valid first name is required" */
    public static String givenName(String value) {
        return valid(value, "A valid first name is required");
    }

    /** @throws IllegalArgumentException "A valid last name is required" */
    public static String familyName(String value) {
        return valid(value, "A valid last name is required");
    }

    private static String valid(String value, String message) {
        String stripped = value == null ? "" : value.strip();
        if (stripped.isEmpty() || stripped.codePointCount(0, stripped.length()) > MAX_LENGTH
                || stripped.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(message);
        }
        return stripped;
    }
}
