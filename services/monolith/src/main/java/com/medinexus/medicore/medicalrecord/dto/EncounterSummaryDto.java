package com.medinexus.medicore.medicalrecord.dto;

import java.util.List;

/**
 * One visit as shown in the patient record (US-05.05): readable values only. The Patient is implied by the request
 * and never repeated; Practitioner, Organization and Location are given by name (null when the resource has no name
 * or cannot be read), never by reference.
 *
 * @param id            the Encounter's logical ID, for list keys only (not shown)
 * @param start         {@code Encounter.period.start} as stored (ISO 8601), or null
 * @param end           {@code Encounter.period.end} as stored, or null
 * @param status        the FHIR R4 status code, e.g. {@code finished}
 * @param type          the visit type's text or display, or null
 * @param practitioners names of the participating Practitioners
 * @param organization  name of the service provider, or null
 * @param location      name of the (first) Location, or null
 */
public record EncounterSummaryDto(String id, String start, String end, String status, String type,
        List<String> practitioners, String organization, String location) {

    public EncounterSummaryDto {
        practitioners = practitioners == null ? List.of() : List.copyOf(practitioners);
    }
}
