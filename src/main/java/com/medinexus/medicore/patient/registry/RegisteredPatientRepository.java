package com.medinexus.medicore.patient.registry;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RegisteredPatientRepository extends JpaRepository<RegisteredPatient, String> {

    @Query("SELECT r.fhirPatientId FROM RegisteredPatient r")
    List<String> findAllFhirPatientIds();
}
