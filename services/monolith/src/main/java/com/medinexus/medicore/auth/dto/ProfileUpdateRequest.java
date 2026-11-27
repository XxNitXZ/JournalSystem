package com.medinexus.medicore.auth.dto;

import java.util.Map;
import java.util.Set;

import com.medinexus.medicore.auth.service.AccountChangeException;

/**
 * Contact changes of the signed-in account ({@code PATCH /api/auth/profile}): {@code email} (requires
 * {@code currentPassword}) and {@code phoneNumber} ({@code null} or blank removes it). A field that is absent is
 * left unchanged. Names, personnummer, role, IDs and FHIR identifiers cannot be changed: any other property is
 * rejected ({@code FIELD_NOT_EDITABLE}) instead of being ignored.
 */
public record ProfileUpdateRequest(boolean emailPresent, String email, boolean phoneNumberPresent, String phoneNumber,
        String currentPassword) {

    private static final Set<String> FIELDS = Set.of("email", "phoneNumber", "currentPassword");

    /** @throws AccountChangeException FIELD_NOT_EDITABLE for other properties, INVALID_REQUEST for non-text values */
    public static ProfileUpdateRequest from(Map<String, Object> body) {
        if (body == null) throw new AccountChangeException("INVALID_REQUEST");
        if (!FIELDS.containsAll(body.keySet())) throw new AccountChangeException("FIELD_NOT_EDITABLE");
        return new ProfileUpdateRequest(body.containsKey("email"), text(body, "email"),
                body.containsKey("phoneNumber"), text(body, "phoneNumber"), text(body, "currentPassword"));
    }

    static String text(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value != null && !(value instanceof String)) throw new AccountChangeException("INVALID_REQUEST");
        return (String) value;
    }

    /** Never prints the password. */
    @Override
    public String toString() {
        return "ProfileUpdateRequest[emailPresent=" + emailPresent + ", phoneNumberPresent=" + phoneNumberPresent + "]";
    }
}
