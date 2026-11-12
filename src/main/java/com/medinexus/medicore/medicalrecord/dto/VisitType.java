package com.medinexus.medicore.medicalrecord.dto;

import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest.EncounterClass;

/**
 * The small set of visit types MediCore offers for a new Encounter (US-05.05): {@code Encounter.type} as a SNOMED CT
 * concept and the matching {@code Encounter.class} from the v3 ActCode value set.
 */
public enum VisitType {
    CONSULTATION("11429006", "Consultation", EncounterClass.AMB),
    FOLLOW_UP("390906007", "Follow-up encounter", EncounterClass.AMB),
    CHECK_UP("185349003", "Encounter for check up", EncounterClass.AMB),
    TELEPHONE("185317003", "Telephone encounter", EncounterClass.VR);

    public static final String SYSTEM = "http://snomed.info/sct";

    private final String code;
    private final String display;
    private final EncounterClass encounterClass;

    VisitType(String code, String display, EncounterClass encounterClass) {
        this.code = code;
        this.display = display;
        this.encounterClass = encounterClass;
    }

    public String code() {
        return code;
    }

    public String display() {
        return display;
    }

    public EncounterClass encounterClass() {
        return encounterClass;
    }
}
