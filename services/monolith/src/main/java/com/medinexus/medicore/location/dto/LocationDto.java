package com.medinexus.medicore.location.dto;

import java.math.BigDecimal;
import java.util.List;

import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Coding;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Identifier;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.SubjectReference;
import com.medinexus.medicore.organization.dto.OrganizationDto;

/**
 * Read projection of an R4 Location, not a complete FHIR resource or profile validator.
 * {@code versionId} is the server's current version and is required for a safe update.
 * {@code managingOrganization} and {@code partOf} are preserved verbatim and never resolved.
 * {@code operationalStatus} is read-only. hoursOfOperation, availabilityExceptions, endpoint
 * and extensions are not projected; codes are the FHIR code strings.
 */
public record LocationDto(String id, String versionId, List<Identifier> identifiers, String status,
        Coding operationalStatus, String name, List<String> aliases, String description, String mode,
        List<Concept> types, List<OrganizationDto.ContactPoint> telecoms, OrganizationDto.Address address,
        Concept physicalType, Position position, SubjectReference managingOrganization, SubjectReference partOf) {

    public LocationDto {
        identifiers = List.copyOf(identifiers);
        aliases = List.copyOf(aliases);
        types = List.copyOf(types);
        telecoms = List.copyOf(telecoms);
    }

    /** Location.position (WGS84); decimals keep their exact value and scale. Altitude is optional. */
    public record Position(BigDecimal longitude, BigDecimal latitude, BigDecimal altitude) {
    }
}
