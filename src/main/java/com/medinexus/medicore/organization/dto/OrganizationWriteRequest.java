package com.medinexus.medicore.organization.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Identifier;

/**
 * Internal create and update input: the complete desired state of the Organization fields that
 * MediCore manages (identifier, active, type, name, alias, telecom, address, partOf). A null or
 * empty value removes that element on update; unmanaged elements are never touched. There is no
 * caller-selected resource ID and no reference URL: partOf is built from a validated logical ID.
 */
public record OrganizationWriteRequest(List<Identifier> identifiers, Boolean active, List<Concept> types,
        String name, List<String> aliases, List<ContactPoint> telecoms, List<Address> addresses,
        String partOfOrganizationId) {

    public OrganizationWriteRequest {
        identifiers = copy(identifiers);
        types = copy(types);
        aliases = copy(aliases);
        telecoms = copy(telecoms);
        addresses = copy(addresses);
    }

    /** Organization.telecom. FHIR org-2: an Organization telecom never has use "home". */
    public record ContactPoint(ContactPointSystem system, String value, ContactPointUse use, Integer rank) {
    }

    /** Organization.address. FHIR org-3: an Organization address never has use "home". */
    public record Address(AddressUse use, AddressType type, String text, List<String> lines, String city,
            String district, String state, String postalCode, String country) {
        public Address {
            lines = copy(lines);
        }
    }

    /** FHIR R4 ContactPoint.system (required binding). */
    public enum ContactPointSystem {
        PHONE, FAX, EMAIL, PAGER, URL, SMS, OTHER;

        public String code() { return name().toLowerCase(Locale.ROOT); }
    }

    /** FHIR R4 ContactPoint.use without "home" (org-2). */
    public enum ContactPointUse {
        WORK, TEMP, OLD, MOBILE;

        public String code() { return name().toLowerCase(Locale.ROOT); }
    }

    /** FHIR R4 Address.use without "home" (org-3). */
    public enum AddressUse {
        WORK, TEMP, OLD, BILLING;

        public String code() { return name().toLowerCase(Locale.ROOT); }
    }

    /** FHIR R4 Address.type (required binding). */
    public enum AddressType {
        POSTAL, PHYSICAL, BOTH;

        public String code() { return name().toLowerCase(Locale.ROOT); }
    }

    /** Unmodifiable copy that keeps null entries, so OrganizationMapper rejects them as invalid input. */
    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
