package com.medinexus.medicore.patient.dto;

import java.util.Map;
import java.util.Set;

/**
 * The only demographic that DOCTOR and STAFF may change on a registered Patient: the gender. Names, personnummer,
 * birth date and the FHIR Patient ID are immutable after registration, and contact details (e-mail, phone) are not
 * FHIR data in MediCore; none of them is part of this request.
 *
 * <p>{@link #from} reads the JSON body strictly: any other property (for example {@code givenName},
 * {@code personnummer}, {@code birthDate}, {@code id}, {@code email} or {@code phoneNumber}) and any non-text value
 * is rejected, so immutable fields can never be submitted.
 *
 * @param gender {@code FEMALE}, {@code MALE}, {@code OTHER} or {@code UNKNOWN}
 */
public record UpdatePatientRequest(CreatePatientRequest.Gender gender) {

    private static final Set<String> FIELDS = Set.of("gender");

    /**
     * @throws IllegalArgumentException for a missing body, unknown or immutable properties, non-text values or an
     *                                  unknown gender; the message never contains the submitted values
     */
    public static UpdatePatientRequest from(Map<String, Object> body) {
        if (body == null) throw new IllegalArgumentException("Patient update data is required");
        if (!FIELDS.containsAll(body.keySet())) {
            throw new IllegalArgumentException("Only the gender can be changed");
        }
        Object value = body.get("gender");
        if (value != null && !(value instanceof String)) {
            throw new IllegalArgumentException("Patient update values must be text");
        }
        return new UpdatePatientRequest(gender((String) value));
    }

    private static CreatePatientRequest.Gender gender(String value) {
        if (value == null) return null;
        for (CreatePatientRequest.Gender gender : CreatePatientRequest.Gender.values()) {
            if (gender.name().equals(value)) return gender;
        }
        throw new IllegalArgumentException("A valid gender is required");
    }
}
