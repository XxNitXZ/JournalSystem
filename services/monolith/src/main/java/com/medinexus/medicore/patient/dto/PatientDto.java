package com.medinexus.medicore.patient.dto;

import java.util.List;

/** Application-facing patient data; birthDate retains FHIR's optional year/month precision. */
public record PatientDto(String id, List<PatientIdentifier> identifiers, List<PatientName> names,
        String gender, String birthDate) {

    public PatientDto {
        identifiers = List.copyOf(identifiers);
        names = List.copyOf(names);
    }

    public record PatientIdentifier(String system, String value) {
    }

    public record PatientName(String use, String text, String family, List<String> given) {
        public PatientName {
            given = List.copyOf(given);
        }
    }
}
