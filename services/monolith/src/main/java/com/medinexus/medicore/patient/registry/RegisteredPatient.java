package com.medinexus.medicore.patient.registry;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A Patient that MediCore registered in HAPI FHIR: only its logical FHIR ID, the registering account and the
 * time. This is not a Patient record; all Patient data stays in FHIR.
 */
@Entity
@Table(name = "registered_patients")
public class RegisteredPatient {

    @Id
    @Column(name = "fhir_patient_id", length = 64)
    private String fhirPatientId;

    @Column(name = "registered_by")
    private Long registeredBy;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    protected RegisteredPatient() {
    }

    public RegisteredPatient(String fhirPatientId, Long registeredBy, Instant registeredAt) {
        this.fhirPatientId = fhirPatientId;
        this.registeredBy = registeredBy;
        this.registeredAt = registeredAt;
    }

    public String getFhirPatientId() {
        return fhirPatientId;
    }

    public Long getRegisteredBy() {
        return registeredBy;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }
}
