package com.medinexus.medicore.patient.validation;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;

/**
 * The Swedish personnummer of a FHIR Patient as MediCore may use it to match a PATIENT account. Only identifiers with
 * the system {@link Personnummer#SYSTEM} count (the KTH server uses the same system, with values formatted
 * {@code YYYYMMDD-XXXX}); any other identifier, however it looks, and the name are never used. Values are never
 * changed in FHIR; they are only read in their canonical 12-digit form ({@link Personnummer#normalize}).
 *
 * <ul>
 *   <li>{@code VALID}: every personnummer identifier has a valid value (real date, not in the future) and they all
 *       name the same person; {@code value} is the canonical 12-digit personnummer;</li>
 *   <li>{@code AMBIGUOUS}: several different personnummer, or a valid and an invalid one; never matched;</li>
 *   <li>{@code INVALID}: only invalid values; {@code value} is the first as stored (for display);</li>
 *   <li>{@code MISSING}: no personnummer identifier with a value.</li>
 * </ul>
 */
public record PatientPersonnummer(Status status, String value) {

    public enum Status { VALID, AMBIGUOUS, INVALID, MISSING }

    /** Evaluates the Patient's identifiers; {@code today} is the latest allowed date of birth. */
    public static PatientPersonnummer of(Patient patient, LocalDate today) {
        return patient == null ? new PatientPersonnummer(Status.MISSING, null) : of(patient.getIdentifier(), today);
    }

    public static PatientPersonnummer of(List<Identifier> identifiers, LocalDate today) {
        Set<String> valid = new LinkedHashSet<>();
        String firstInvalid = null;
        boolean anyInvalid = false;
        for (Identifier identifier : identifiers) {
            if (!Personnummer.SYSTEM.equals(identifier.getSystem()) || !identifier.hasValue()
                    || identifier.getValue().isBlank()) continue;
            String canonical = Personnummer.normalize(identifier.getValue(), today);
            if (canonical != null) {
                valid.add(canonical);
            } else {
                anyInvalid = true;
                if (firstInvalid == null) firstInvalid = identifier.getValue().strip();
            }
        }
        if (valid.size() > 1 || !valid.isEmpty() && anyInvalid) return new PatientPersonnummer(Status.AMBIGUOUS, null);
        if (valid.size() == 1) return new PatientPersonnummer(Status.VALID, valid.iterator().next());
        if (anyInvalid) return new PatientPersonnummer(Status.INVALID, firstInvalid);
        return new PatientPersonnummer(Status.MISSING, null);
    }

    /** True only for exactly one valid personnummer equal to this canonical value. */
    public boolean matches(String canonicalPersonnummer) {
        return status == Status.VALID && canonicalPersonnummer != null && value.equals(canonicalPersonnummer);
    }
}
