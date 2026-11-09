package com.medinexus.medicore.auth.dto;

import java.util.Map;
import java.util.Set;

import com.medinexus.medicore.auth.service.AccountChangeException;

/**
 * {@code POST /api/auth/password}: the current password, the new password and its confirmation. Any other property
 * is rejected. {@link #toString()} never contains the passwords.
 */
public record PasswordChangeRequest(String currentPassword, String newPassword, String confirmPassword) {

    private static final Set<String> FIELDS = Set.of("currentPassword", "newPassword", "confirmPassword");

    public static PasswordChangeRequest from(Map<String, Object> body) {
        if (body == null) throw new AccountChangeException("INVALID_REQUEST");
        if (!FIELDS.containsAll(body.keySet())) throw new AccountChangeException("FIELD_NOT_EDITABLE");
        return new PasswordChangeRequest(ProfileUpdateRequest.text(body, "currentPassword"),
                ProfileUpdateRequest.text(body, "newPassword"), ProfileUpdateRequest.text(body, "confirmPassword"));
    }

    @Override
    public String toString() {
        return "PasswordChangeRequest[***]";
    }
}
