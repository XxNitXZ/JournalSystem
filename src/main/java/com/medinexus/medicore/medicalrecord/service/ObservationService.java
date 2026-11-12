package com.medinexus.medicore.medicalrecord.service;

import java.util.List;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.auth.service.PatientAccessService;
import com.medinexus.medicore.fhir.client.FhirObservationClient;
import com.medinexus.medicore.fhir.client.FhirPatientClient;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.fhir.mapper.ObservationMapper;
import com.medinexus.medicore.medicalrecord.dto.NewObservationRequest;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto;
import com.medinexus.medicore.medicalrecord.dto.ObservationSearchResult;
import com.medinexus.medicore.patient.registry.PatientRegistry;
import com.medinexus.medicore.patient.service.PatientNotFoundException;
import com.medinexus.medicore.patient.service.OwnPatientRecordException;

@Service
public class ObservationService {
    private final UserRepository users;
    private final PatientAccessService access;
    private final PatientRegistry registry;
    private final FhirPatientClient patients;
    private final FhirObservationClient observations;
    private final ObservationMapper mapper;

    public ObservationService(UserRepository users, PatientAccessService access, PatientRegistry registry,
            FhirPatientClient patients, FhirObservationClient observations, ObservationMapper mapper) {
        this.users = users; 
        this.access = access; 
        this.registry = registry;
        this.patients = patients; 
        this.observations = observations; 
        this.mapper = mapper;
    }

    public ObservationDto create(UserDto principal, String patientId, NewObservationRequest request) {
        clinician(principal);
        validateId(patientId);
        access.checkPatientAccess(principal, patientId);
        if (request == null) throw new IllegalArgumentException("Observation data is required");
        var input = request.forPatient(patientId);
        // Complete validation before the first remote request, including codes, value and date.
        var resource = mapper.toResource(input);
        known(patientId);
        exists(patientId);
        String id = observations.createObservation(input);
        resource.setId(id);
        return mapper.toDto(resource);
    }

    public ObservationSearchResult listForPatient(UserDto principal, String patientId) {
        clinician(principal);
        validateId(patientId);
        access.checkPatientAccess(principal, patientId);
        known(patientId);
        exists(patientId);
        return observations.findByPatientId(patientId);
    }

    public ObservationSearchResult listOwn(UserDto principal) {
        User account = account(principal);
        if (account.getRole() != Role.PATIENT) throw denied();
        String patientId = account.getFhirPatientId();
        if (patientId == null) throw new OwnPatientRecordException("RECORD_NOT_LINKED");
        validateId(patientId);
        access.checkPatientAccess(principal, patientId);
        exists(patientId);
        return observations.findByPatientId(patientId);
    }

    private void known(String id) { if (!registry.isKnown(id)) throw new PatientNotFoundException(); }
    private void exists(String id) {
        Patient patient;
        try { patient = patients.readPatient(id); }
        catch (FhirServerException failure) {
            if (failure.type() == FhirServerException.Type.CLIENT_ERROR
                    && (failure.httpStatus().orElse(0) == 404 || failure.httpStatus().orElse(0) == 410)) {
                throw new PatientNotFoundException();
            }
            throw failure;
        }
        if (patient == null || !id.equals(patient.getIdElement().getIdPart())) {
            throw new FhirServerException("patient-read", FhirServerException.Type.INVALID_RESPONSE, null, List.of(), null);
        }
    }
    private User account(UserDto principal) {
        if (principal == null || principal.id() == null || principal.id() <= 0) throw denied();
        return users.findById(principal.id()).orElseThrow(ObservationService::denied);
    }
    private void clinician(UserDto principal) {
        Role role = account(principal).getRole();
        if (role != Role.DOCTOR && role != Role.STAFF) throw denied();
    }
    private void validateId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9.-]{1,64}")) throw new IllegalArgumentException("Invalid Patient ID");
    }
    private static AccessDeniedException denied() { return new AccessDeniedException("Access denied"); }
}
