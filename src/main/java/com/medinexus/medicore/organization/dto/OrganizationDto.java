package com.medinexus.medicore.organization.dto;

import java.util.List;

import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Identifier;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.SubjectReference;

/**
 * Read projection of an R4 Organization, not a complete FHIR resource or profile validator.
 * {@code versionId} is the server's current version and is required for a safe update.
 * {@code partOf} is preserved verbatim and never resolved. Organization.contact (contact
 * persons), endpoint and extensions are not projected; codes are the FHIR code strings.
 */
public record OrganizationDto(String id, String versionId, List<Identifier> identifiers, Boolean active,
        List<Concept> types, String name, List<String> aliases, List<ContactPoint> telecoms,
        List<Address> addresses, SubjectReference partOf) {

    public OrganizationDto {
        identifiers = List.copyOf(identifiers);
        types = List.copyOf(types);
        aliases = List.copyOf(aliases);
        telecoms = List.copyOf(telecoms);
        addresses = List.copyOf(addresses);
    }

    /** Organization.telecom: system, use and rank as stored on the server. */
    public record ContactPoint(String system, String value, String use, Integer rank) {
    }

    /** Organization.address; Address.period is not projected. */
    public record Address(String use, String type, String text, List<String> lines, String city,
            String district, String state, String postalCode, String country) {
        public Address {
            lines = List.copyOf(lines);
        }
    }
}
