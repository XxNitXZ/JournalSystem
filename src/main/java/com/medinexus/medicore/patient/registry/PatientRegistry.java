package com.medinexus.medicore.patient.registry;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medinexus.medicore.auth.repository.UserRepository;

/**
 * The Patients that belong to MediCore on the shared FHIR server: those MediCore registered itself
 * ({@code registered_patients}) and those linked to a MediCore account by the trusted operator command
 * ({@code users.fhir_patient_id}). Patient search and profile reads are restricted to these IDs; nothing else on
 * the shared server is ever searched or shown. Only logical IDs are stored.
 */
@Service
public class PatientRegistry {

    private final RegisteredPatientRepository registered;
    private final UserRepository users;
    private final Clock clock;

    @Autowired
    public PatientRegistry(RegisteredPatientRepository registered, UserRepository users) {
        this(registered, users, Clock.systemUTC());
    }

    PatientRegistry(RegisteredPatientRepository registered, UserRepository users, Clock clock) {
        this.registered = registered;
        this.users = users;
        this.clock = clock;
    }

    /** Records a Patient that MediCore has just created in FHIR. Recording the same ID again changes nothing. */
    @Transactional
    public void record(String fhirPatientId, Long registeredBy) {
        if (!registered.existsById(fhirPatientId)) {
            registered.saveAndFlush(new RegisteredPatient(fhirPatientId, registeredBy, Instant.now(clock)));
        }
    }

    /** True if the Patient was registered by MediCore or is linked to a MediCore account. */
    @Transactional(readOnly = true)
    public boolean isKnown(String fhirPatientId) {
        return registered.existsById(fhirPatientId) || users.existsByFhirPatientId(fhirPatientId);
    }

    /** IDs of the Patients that MediCore registered itself (not those only linked to an account), sorted. */
    @Transactional(readOnly = true)
    public List<String> registeredIds() {
        return List.copyOf(new TreeSet<>(registered.findAllFhirPatientIds()));
    }

    /** All known Patient IDs, sorted and without duplicates. */
    @Transactional(readOnly = true)
    public List<String> knownIds() {
        TreeSet<String> ids = new TreeSet<>(registered.findAllFhirPatientIds());
        ids.addAll(users.findLinkedFhirPatientIds());
        return List.copyOf(ids);
    }
}
