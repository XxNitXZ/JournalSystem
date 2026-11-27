package com.medinexus.medicore.practitioner.dto;

/**
 * A Practitioner that can be chosen for an Encounter: one linked to a MediCore DOCTOR or STAFF account.
 *
 * @param id   the logical FHIR Practitioner ID, used only as the selected value (never shown as text)
 * @param name the name from the FHIR Practitioner
 * @param own  true for the Practitioner linked to the signed-in account (preselected)
 */
public record PractitionerOption(String id, String name, boolean own) {
}
