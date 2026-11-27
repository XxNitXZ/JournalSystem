package com.medinexus.medicore.practitioner.dto;

import java.util.List;

/** Application-facing practitioner data; absent active status is represented by null. */
public record PractitionerDto(String id, List<PractitionerIdentifier> identifiers, List<PractitionerName> names,
        Boolean active) {

    public PractitionerDto {
        identifiers = List.copyOf(identifiers);
        names = List.copyOf(names);
    }

    public record PractitionerIdentifier(String system, String value) {
    }

    public record PractitionerName(String use, String text, String family, List<String> given) {
        public PractitionerName {
            given = List.copyOf(given);
        }
    }
}
