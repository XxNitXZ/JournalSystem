package com.medinexus.medicore.fhir.mapper;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.stereotype.Component;

import com.medinexus.medicore.patient.dto.CreatePatientRequest;
import com.medinexus.medicore.patient.dto.UpdatePatientRequest;
import com.medinexus.medicore.patient.dto.PatientDto;
import com.medinexus.medicore.patient.dto.PatientDto.PatientIdentifier;
import com.medinexus.medicore.patient.dto.PatientDto.PatientName;
import com.medinexus.medicore.patient.validation.Personnummer;

@Component
public class PatientMapper {

    private static final ZoneId SWEDEN = ZoneId.of("Europe/Stockholm");
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_GIVEN_NAMES = 10;

    public PatientDto toDto(Patient patient) {
        if (patient == null) {
            throw new IllegalArgumentException("Patient is required");
        }
        return new PatientDto(patient.getIdElement().getIdPart(),
                patient.getIdentifier().stream().map(identifier ->
                        new PatientIdentifier(identifier.getSystem(), identifier.getValue())).toList(),
                patient.getName().stream().map(name -> new PatientName(
                        name.hasUse() ? name.getUse().toCode() : null,
                        name.getText(), name.getFamily(),
                        name.getGiven().stream().map(given -> given.getValue()).filter(value -> value != null).toList()))
                        .toList(),
                patient.hasGender() ? patient.getGender().toCode() : null,
                patient.hasBirthDate() ? patient.getBirthDateElement().getValueAsString() : null);
    }

    /**
     * A new Patient for {@code POST [base]/Patient}: the personnummer as the only identifier (system
     * {@link Personnummer#SYSTEM}), one official name, gender, birth date (day precision) and
     * {@code active = true}. No ID, meta or narrative.
     *
     * @throws IllegalArgumentException with a fixed message that never contains the submitted values
     */
    public Patient toResource(CreatePatientRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Patient registration data is required");
        }
        if (!Personnummer.isValid(request.personnummer())) {
            throw new IllegalArgumentException("A valid Swedish personnummer is required");
        }
        // The birth date is derived from the personnummer; an explicitly given birth date must equal it.
        LocalDate birthDate = request.birthDate() != null ? request.birthDate()
                : Personnummer.birthDate(request.personnummer());
        if (birthDate.isAfter(LocalDate.now(SWEDEN))) {
            throw new IllegalArgumentException("A birth date that is not in the future is required");
        }
        if (!birthDate.equals(Personnummer.birthDate(request.personnummer()))) {
            throw new IllegalArgumentException("The birth date must match the personnummer");
        }
        String family = name(request.family());
        List<String> given = request.given();
        if (given == null || given.isEmpty() || given.size() > MAX_GIVEN_NAMES) {
            throw new IllegalArgumentException("Between 1 and " + MAX_GIVEN_NAMES + " given names are required");
        }
        if (request.gender() == null) {
            throw new IllegalArgumentException("A gender is required");
        }

        Patient patient = new Patient();
        patient.addIdentifier(new Identifier().setUse(Identifier.IdentifierUse.OFFICIAL)
                .setSystem(Personnummer.SYSTEM).setValue(request.personnummer()));
        HumanName humanName = patient.addName().setUse(HumanName.NameUse.OFFICIAL).setFamily(family);
        given.forEach(value -> humanName.addGiven(name(value)));
        patient.setGender(gender(request.gender()));
        patient.setBirthDateElement(new DateType(birthDate.toString()));
        patient.setActive(true);
        return patient;
    }

    /**
     * Applies a DOCTOR/STAFF update to a Patient read from FHIR, in place: <strong>only the gender</strong> changes.
     * Names, the ID, meta, identifiers (personnummer), birth date, telecom, addresses, extensions and every other
     * element are left exactly as read; names and personnummer are immutable after registration.
     *
     * @throws IllegalArgumentException with a fixed message that never contains the submitted values; the Patient
     *                                  is then unchanged
     */
    public void applyGender(Patient patient, UpdatePatientRequest update) {
        if (patient == null || update == null) {
            throw new IllegalArgumentException("Patient update data is required");
        }
        if (update.gender() == null) {
            throw new IllegalArgumentException("A gender is required");
        }
        patient.setGender(gender(update.gender()));
    }

    /** The FHIR version of a Patient as read ({@code _history} part of its ID, else {@code meta.versionId}), or null. */
    public static String versionOf(Patient patient) {
        if (patient == null) return null;
        String version = patient.getIdElement().getVersionIdPart();
        if (version == null && patient.hasMeta()) version = patient.getMeta().getVersionId();
        return version;
    }

    private static AdministrativeGender gender(CreatePatientRequest.Gender gender) {
        return switch (gender) {
            case MALE -> AdministrativeGender.MALE;
            case FEMALE -> AdministrativeGender.FEMALE;
            case OTHER -> AdministrativeGender.OTHER;
            case UNKNOWN -> AdministrativeGender.UNKNOWN;
        };
    }

    /** A stripped, nonblank name of at most 100 characters without control characters. */
    private static String name(String value) {
        String stripped = value == null ? "" : value.strip();
        if (stripped.isEmpty() || stripped.length() > MAX_NAME_LENGTH
                || stripped.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Names must be nonblank, at most " + MAX_NAME_LENGTH
                    + " characters and contain no control characters");
        }
        return stripped;
    }
}
