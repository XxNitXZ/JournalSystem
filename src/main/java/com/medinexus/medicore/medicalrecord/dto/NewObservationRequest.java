package com.medinexus.medicore.medicalrecord.dto;

import java.util.List;

import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.medinexus.medicore.medicalrecord.dto.CreateObservationRequest.Status;
import com.medinexus.medicore.medicalrecord.dto.CreateObservationRequest.QuantityValue;

/** Public input: Patient identity is exclusively supplied by the authorized path. Exactly one value/absence is required. */
public record NewObservationRequest(Status status, ConceptInput code, String effectiveDateTime,
        QuantityValue valueQuantity, ConceptInput valueCodeableConcept, ConceptInput dataAbsentReason) {
    
    /** Allows text-only concepts without requiring an empty coding array in JSON. */
    public record ConceptInput(String text, List<ObservationDto.Coding> coding) {
        public ConceptInput { coding = coding == null ? List.of() : List.copyOf(coding); }
        public Concept toConcept() { return new Concept(text, coding); }
    }

    public CreateObservationRequest forPatient(String patientId) {
        int choices = (valueQuantity == null ? 0 : 1) + (valueCodeableConcept == null ? 0 : 1)
                + (dataAbsentReason == null ? 0 : 1);
        if (choices != 1 || effectiveDateTime == null || effectiveDateTime.isBlank()) {
            throw new IllegalArgumentException("Invalid Observation request");
        }
        return new CreateObservationRequest(patientId, status, code == null ? null : code.toConcept(), valueQuantity != null ? valueQuantity
                : valueCodeableConcept != null ? new CreateObservationRequest.CodedValue(valueCodeableConcept.toConcept()) : null,
                dataAbsentReason == null ? null : dataAbsentReason.toConcept(), effectiveDateTime);
    }
}
