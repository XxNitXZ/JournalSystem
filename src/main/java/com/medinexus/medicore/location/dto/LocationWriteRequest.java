package com.medinexus.medicore.location.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Identifier;

/**
 * Internal create and update input: the complete desired state of the Location fields that MediCore
 * manages (identifier, status, name, alias, description, mode, type, telecom, address, physicalType,
 * position, managingOrganization, partOf). A null or empty value removes that element on update;
 * unmanaged elements (operationalStatus, hoursOfOperation, availabilityExceptions, endpoint,
 * extensions, ...) are never touched. There is no caller-selected resource ID and no reference URL:
 * managingOrganization and partOf are built from validated logical IDs. FHIR R4 requires no element of
 * a Location, so no field is mandatory.
 */
public record LocationWriteRequest(List<Identifier> identifiers, Status status, String name, List<String> aliases,
        String description, Mode mode, List<Concept> types, List<ContactPoint> telecoms, Address address,
        Concept physicalType, LocationDto.Position position, String managingOrganizationId, String partOfLocationId) {

    public LocationWriteRequest {
        identifiers = copy(identifiers);
        aliases = copy(aliases);
        types = copy(types);
        telecoms = copy(telecoms);
    }

    /** Location.telecom. */
    public record ContactPoint(ContactPointSystem system, String value, ContactPointUse use, Integer rank) {
    }

    /** Location.address. */
    public record Address(AddressUse use, AddressType type, String text, List<String> lines, String city,
            String district, String state, String postalCode, String country) {
        public Address {
            lines = copy(lines);
        }
    }

    /** FHIR R4 Location.status (required binding). */
    public enum Status {
        ACTIVE, SUSPENDED, INACTIVE;

        public String code() { return name().toLowerCase(Locale.ROOT); }
    }

    /** FHIR R4 Location.mode (required binding). */
    public enum Mode {
        INSTANCE, KIND;

        public String code() { return name().toLowerCase(Locale.ROOT); }
    }

    /** FHIR R4 ContactPoint.system (required binding). */
    public enum ContactPointSystem {
        PHONE, FAX, EMAIL, PAGER, URL, SMS, OTHER;

        public String code() { return name().toLowerCase(Locale.ROOT); }
    }

    /** FHIR R4 ContactPoint.use (required binding); Location has no restriction on "home". */
    public enum ContactPointUse {
        HOME, WORK, TEMP, OLD, MOBILE;

        public String code() { return name().toLowerCase(Locale.ROOT); }
    }

    /** FHIR R4 Address.use (required binding); Location has no restriction on "home". */
    public enum AddressUse {
        HOME, WORK, TEMP, OLD, BILLING;

        public String code() { return name().toLowerCase(Locale.ROOT); }
    }

    /** FHIR R4 Address.type (required binding). */
    public enum AddressType {
        POSTAL, PHYSICAL, BOTH;

        public String code() { return name().toLowerCase(Locale.ROOT); }
    }

    /** Unmodifiable copy that keeps null entries, so LocationMapper rejects them as invalid input. */
    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
