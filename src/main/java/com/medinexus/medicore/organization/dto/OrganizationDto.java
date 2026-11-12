package com.medinexus.medicore.organization.dto;

import java.util.List;

import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Identifier;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.SubjectReference;


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

    public record ContactPoint(String system, String value, String use, Integer rank) {
    }

    public record Address(String use, String type, String text, List<String> lines, String city,
            String district, String state, String postalCode, String country) {
        public Address {
            lines = List.copyOf(lines);
        }
    }
}
