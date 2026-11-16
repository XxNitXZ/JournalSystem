package com.medinexus.medicore.medicalrecord.dto;

import java.util.Map;
import java.util.Set;

import com.medinexus.medicore.medicalrecord.service.EncounterRequestException;

/**
 * Body of {@code POST /api/patients/{patientId}/encounters} (US-05.05). The Patient comes from the path, never from
 * the body. Values are validated by {@code EncounterService}; this record only reads the JSON strictly: any other
 * property (such as {@code patientId} or {@code subject}) or a non-text value is rejected.
 *
 * @param startTime      date and time of the visit, ISO 8601 with an offset ({@code 2026-11-10T14:30:00+01:00})
 * @param practitionerId the chosen Practitioner (one linked to a MediCore clinician)
 * @param type           a {@link VisitType} name
 * @param status         {@code FINISHED} (default when absent), {@code IN_PROGRESS} or {@code PLANNED}
 * @param organizationId optional Organization (service provider)
 * @param locationId     optional Location
 */
public record NewEncounterRequest(String startTime, String practitionerId, String type, String status,
        String organizationId, String locationId) {

    private static final Set<String> FIELDS = Set.of("startTime", "practitionerId", "type", "status",
            "organizationId", "locationId");

    public static NewEncounterRequest from(Map<String, Object> body) {
        if (body == null || !FIELDS.containsAll(body.keySet())) throw new EncounterRequestException("INVALID_REQUEST");
        return new NewEncounterRequest(text(body, "startTime"), text(body, "practitionerId"), text(body, "type"),
                text(body, "status"), text(body, "organizationId"), text(body, "locationId"));
    }

    private static String text(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value != null && !(value instanceof String)) throw new EncounterRequestException("INVALID_REQUEST");
        String text = (String) value;
        return text == null || text.isBlank() ? null : text.strip();
    }
}
