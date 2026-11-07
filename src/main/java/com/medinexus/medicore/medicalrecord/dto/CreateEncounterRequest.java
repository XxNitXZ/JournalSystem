package com.medinexus.medicore.medicalrecord.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;

/**
 * Internal creation input; no caller-selected resource ID, subject URL or other reference URLs.
 * Patient, Practitioner, Organization (service provider) and Location references are built from
 * validated logical IDs only. The service provider and locations are optional and never resolved.
 */
public record CreateEncounterRequest(String patientId, Status status, EncounterClass encounterClass,
        List<Concept> type, Concept serviceType, Concept priority, String periodStart, String periodEnd,
        List<Concept> reasonCode, List<Participant> participants, String serviceProviderId,
        List<EncounterLocation> locations) {

    public CreateEncounterRequest {
        type = type == null ? List.of() : List.copyOf(type);
        reasonCode = reasonCode == null ? List.of() : List.copyOf(reasonCode);
        // Unmodifiable copies that keep null entries, so EncounterMapper rejects them as invalid input.
        participants = participants == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(participants));
        locations = locations == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(locations));
    }

    /** Request without service provider and locations (the original NIT-131 signature). */
    public CreateEncounterRequest(String patientId, Status status, EncounterClass encounterClass,
            List<Concept> type, Concept serviceType, Concept priority, String periodStart, String periodEnd,
            List<Concept> reasonCode, List<Participant> participants) {
        this(patientId, status, encounterClass, type, serviceType, priority, periodStart, periodEnd, reasonCode,
                participants, null, null);
    }

    /**
     * A Location where the Encounter takes place (Encounter.location.location = Location/{locationId}),
     * with an optional Encounter.location.status.
     */
    public record EncounterLocation(String locationId, LocationStatus status) {
    }

    /** FHIR R4 Encounter.location.status (required binding). */
    public enum LocationStatus {
        PLANNED, ACTIVE, RESERVED, COMPLETED;

        public String code() { return name().toLowerCase(Locale.ROOT); }
    }

    /** A Practitioner taking part in the Encounter (Encounter.participant.individual and .type). */
    public record Participant(String practitionerId, List<ParticipantType> types) {
        public Participant {
            types = types == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(types));
        }
    }

    /** FHIR R4 Encounter.participant.type: the v3 ParticipationType codes of the ParticipantType value set. */
    public enum ParticipantType {
        ADM, ATND, CALLBCK, CON, DIS, ESC, PART, PPRF, REF, SPRF;

        public static final String SYSTEM = "http://terminology.hl7.org/CodeSystem/v3-ParticipationType";
        public String code() { return name(); }
    }

    /** FHIR R4 Encounter.status (required binding). */
    public enum Status {
        PLANNED, ARRIVED, TRIAGED, IN_PROGRESS, ONLEAVE, FINISHED, CANCELLED, ENTERED_IN_ERROR, UNKNOWN;

        public String code() { return name().toLowerCase(Locale.ROOT).replace('_', '-'); }
    }

    /** FHIR R4 Encounter.class from the v3 ActEncounterCode value set. */
    public enum EncounterClass {
        AMB, EMER, FLD, HH, IMP, ACUTE, NONAC, OBSENC, PRENC, SS, VR;

        public static final String SYSTEM = "http://terminology.hl7.org/CodeSystem/v3-ActCode";
        public String code() { return name(); }
    }
}
