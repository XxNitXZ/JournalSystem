package com.medinexus.medicore.patient.dto;

import java.util.List;

import com.medinexus.medicore.patient.dto.PatientDto.PatientIdentifier;
import com.medinexus.medicore.patient.dto.PatientDto.PatientName;

/**
 * A patient profile: the demographics from HAPI FHIR ({@link PatientDto}: names, personnummer, gender, birth date)
 * plus the contact details of the PATIENT account linked to this Patient ({@code users.fhir_patient_id}) from
 * PostgreSQL, or null when no account is linked or the account has none. Contact details are never read from or
 * written to FHIR ({@code Patient.telecom} is ignored), and no other account data is included.
 *
 * @param version the FHIR version of the Patient as read, for DOCTOR/STAFF profiles; sent back as {@code If-Match}
 *                when updating the Patient (US-04.04). Not shown to users; null where no update is possible.
 */
public record PatientProfileDto(String id, List<PatientIdentifier> identifiers, List<PatientName> names,
        String gender, String birthDate, String email, String phoneNumber, String version) {

    /** E-mail and phone number of a linked account, from PostgreSQL. */
    public record Contact(String email, String phoneNumber) {
        public static final Contact NONE = new Contact(null, null);
    }

    public PatientProfileDto {
        identifiers = List.copyOf(identifiers);
        names = List.copyOf(names);
    }

    public static PatientProfileDto withContact(PatientDto patient, Contact contact, String version) {
        Contact known = contact == null ? Contact.NONE : contact;
        return new PatientProfileDto(patient.id(), patient.identifiers(), patient.names(), patient.gender(),
                patient.birthDate(), known.email(), known.phoneNumber(), version);
    }

    /** A profile with only an e-mail (no phone number) and no version. */
    public static PatientProfileDto of(PatientDto patient, String email) {
        return withContact(patient, new Contact(email, null), null);
    }

    /** A profile with only an e-mail (no phone number) and the given version. */
    public static PatientProfileDto of(PatientDto patient, String email, String version) {
        return withContact(patient, new Contact(email, null), version);
    }
}
