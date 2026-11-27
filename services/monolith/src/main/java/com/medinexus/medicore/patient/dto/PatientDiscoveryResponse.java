package com.medinexus.medicore.patient.dto;

import java.util.List;

/**
 * Result of {@code POST /api/patients/import/search}.
 *
 * @param patients  the Patients found, at most {@code FhirPatientClient.DISCOVERY_MAX_RESULTS}
 * @param truncated true if the FHIR server has more matches than were fetched; the search should be refined
 */
public record PatientDiscoveryResponse(List<PatientCandidateDto> patients, boolean truncated) {

    public PatientDiscoveryResponse {
        patients = List.copyOf(patients);
    }
}
