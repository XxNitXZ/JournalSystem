package com.medinexus.medicore.auth.service;

/**
 * A rejected change of the signed-in user's own account, with a machine-readable code and no submitted values:
 * {@code FIELD_NOT_EDITABLE}, {@code INVALID_REQUEST}, {@code NOTHING_TO_CHANGE}, {@code EMAIL_INVALID},
 * {@code PHONE_INVALID}, {@code PASSWORD_INVALID}, {@code PASSWORD_MISMATCH} (400), {@code CURRENT_PASSWORD_INVALID}
 * (403) and {@code EMAIL_TAKEN} (409).
 */
public class AccountChangeException extends RuntimeException {

    private final String code;

    public AccountChangeException(String code) {
        super("Account change rejected: " + code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
