package com.medinexus.medicore.auth.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/** The e-mail and password rules shared by registration, profile changes and password changes. */
public final class AccountCredentials {

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private AccountCredentials() {
    }

    /**
     * The e-mail address as stored (lower case), as at registration.
     *
     * @throws IllegalArgumentException for an invalid address; the message never contains it
     */
    public static String email(String email) {
        if (email == null || email.length() > 255 || !EMAIL.matcher(email).matches()) {
            throw new IllegalArgumentException("A valid email address is required");
        }
        return email.toLowerCase(Locale.ROOT);
    }

    /** BCrypt supports at most 72 UTF-8 bytes. Passwords are never trimmed or truncated. */
    public static boolean isAcceptablePassword(String password) {
        return password != null && !password.isBlank() && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
