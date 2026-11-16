package com.medinexus.medicore.patient.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Input for registering a new Patient in HAPI FHIR (US-04.01). No caller-selected FHIR ID.
 *
 * <p>Validated by {@code PatientMapper.toResource} before any FHIR request: {@code personnummer} is a mandatory
 * 12-digit Swedish personal identity number ({@code YYYYMMDDXXXX}) whose first eight digits are a real date (no
 * check-digit validation),
 * {@code birthDate} is optional and derived from the personnummer when absent; if given it must equal that date.
 * The birth date must not be in the future. {@code family} and
 * every {@code given} name are nonblank. The personnummer is stored only in {@code Patient.identifier}.
 */
public record CreatePatientRequest(String personnummer, String family, List<String> given, Gender gender,
        LocalDate birthDate) {

    public CreatePatientRequest {
        // Defensive, unmodifiable copy; null elements are kept so that validation can reject them.
        given = given == null ? null : Collections.unmodifiableList(new ArrayList<>(given));
    }

    /** FHIR R4 AdministrativeGender. */
    public enum Gender {
        MALE, FEMALE, OTHER, UNKNOWN
    }
}
