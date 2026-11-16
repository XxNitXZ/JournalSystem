package com.medinexus.medicore.patient.registry;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RegisteredPatientRepository extends JpaRepository<RegisteredPatient, String> {

    @Query("SELECT r.fhirPatientId FROM RegisteredPatient r")
    List<String> findAllFhirPatientIds();

    /**
     * Inserts the Patient unless it is already registered; returns 1 if this call inserted it, 0 otherwise. A single
     * statement, so concurrent imports of the same Patient never create a second row or overwrite the first.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO registered_patients (fhir_patient_id, registered_by, registered_at)
            VALUES (:fhirPatientId, :registeredBy, :registeredAt)
            ON CONFLICT (fhir_patient_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("fhirPatientId") String fhirPatientId, @Param("registeredBy") Long registeredBy,
            @Param("registeredAt") Instant registeredAt);
}
