package com.medinexus.medicore.patient.service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.hl7.fhir.r4.model.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.auth.service.PatientAccessService;
import com.medinexus.medicore.fhir.client.DuplicatePatientException;
import com.medinexus.medicore.fhir.client.FhirPatientClient;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.fhir.mapper.PatientMapper;
import com.medinexus.medicore.fhir.client.TooManyPatientsException;
import com.medinexus.medicore.patient.dto.CreatePatientRequest;
import com.medinexus.medicore.patient.dto.PatientDto;
import com.medinexus.medicore.patient.dto.PatientDto.PatientName;
import com.medinexus.medicore.patient.dto.PatientProfileDto;
import com.medinexus.medicore.patient.dto.UpdatePatientRequest;
import com.medinexus.medicore.patient.dto.PatientSearchCriteria;
import com.medinexus.medicore.journalaccess.JournalAccessMatcher;
import com.medinexus.medicore.patient.registry.PatientRegistry;

/**
 * Patient registration (US-04.01), patient profiles (US-04.02) and patient search for DOCTOR and STAFF accounts.
 * Clinical Patient data is read from and stored only in HAPI FHIR; PostgreSQL holds only the registry of
 * logical Patient IDs ({@link PatientRegistry}). Profiles and searches are limited to Patients in that registry
 * or linked to an account. Logs contain the account ID and the outcome, never patient data; the only exception
 * is the FHIR ID of a Patient that could not be recorded in the registry, which an operator needs.
 */
@Service
public class PatientService {

    private static final Logger log = LoggerFactory.getLogger(PatientService.class);
    private static final String LOGICAL_ID = "[A-Za-z0-9.-]{1,64}";

    private final UserRepository users;
    private final FhirPatientClient patients;
    private final PatientMapper mapper;
    private final PatientAccessService patientAccess;
    private final PatientRegistry registry;
    private final JournalAccessMatcher journalAccessMatcher;

    public PatientService(UserRepository users, FhirPatientClient patients, PatientMapper mapper,
            PatientAccessService patientAccess, PatientRegistry registry, JournalAccessMatcher journalAccessMatcher) {
        this.users = users;
        this.patients = patients;
        this.mapper = mapper;
        this.patientAccess = patientAccess;
        this.registry = registry;
        this.journalAccessMatcher = journalAccessMatcher;
    }

    /**
     * The signed-in PATIENT's own record (US-04.03). The FHIR Patient ID is taken only from the account in
     * PostgreSQL (set by an approved journal access request or by the operator command), never from the request.
     *
     * @throws AccessDeniedException       for other roles or unknown accounts
     * @throws OwnPatientRecordException  {@code RECORD_NOT_LINKED} without a link, {@code RECORD_NOT_FOUND} if the
     *                                     linked Patient no longer exists
     * @throws FhirServerException         for other FHIR failures, including a response for another Patient
     */
    public PatientProfileDto getOwnRecord(UserDto principal) {
        if (principal == null || principal.id() == null || principal.id() <= 0) throw denied();
        User account = users.findById(principal.id()).orElseThrow(PatientService::denied);
        if (account.getRole() != Role.PATIENT) throw denied();
        String id = account.getFhirPatientId();
        if (id == null) throw new OwnPatientRecordException("RECORD_NOT_LINKED");
        patientAccess.checkPatientAccess(principal, id);
        Patient patient;
        try {
            patient = patients.readPatient(id);
        } catch (FhirServerException failure) {
            int status = failure.httpStatus().orElse(0);
            if (failure.type() == FhirServerException.Type.CLIENT_ERROR && (status == 404 || status == 410)) {
                throw new OwnPatientRecordException("RECORD_NOT_FOUND");
            }
            throw failure;
        }
        if (patient == null || !id.equals(patient.getIdElement().getIdPart())) {
            throw new FhirServerException("patient-read", FhirServerException.Type.INVALID_RESPONSE, null, List.of(),
                    null);
        }
        log.info("Own patient record viewed: accountId={}", principal.id());
        return PatientProfileDto.withContact(mapper.toDto(patient),
                new PatientProfileDto.Contact(account.getEmail(), account.getPhoneNumber()), null);
    }

    /** E-mail and phone number of the PATIENT account linked to this FHIR Patient (PostgreSQL only), or none. */
    private PatientProfileDto.Contact linkedContact(String fhirPatientId) {
        return users.findByFhirPatientId(fhirPatientId).filter(account -> account.getRole() == Role.PATIENT)
                .map(account -> new PatientProfileDto.Contact(account.getEmail(), account.getPhoneNumber()))
                .orElse(PatientProfileDto.Contact.NONE);
    }

    /**
     * Searches the Patients known to MediCore by name or exact personnummer and returns every match, sorted by
     * family name, given names and birth date. Nothing outside the registry is searched.
     *
     * @throws AccessDeniedException     for other roles or unknown accounts
     * @throws IllegalArgumentException  for an empty or malformed query
     * @throws TooManyPatientsException  if more than {@link FhirPatientClient#MAX_RESULTS} Patients match
     * @throws FhirServerException       for FHIR failures
     */
    public List<PatientDto> searchPatients(UserDto principal, String query) {
        requireClinicalRole(principal);
        PatientSearchCriteria criteria = PatientSearchCriteria.parse(query);
        List<String> known = registry.knownIds();
        List<PatientDto> results = known.isEmpty() ? List.of() : patients.searchPatients(criteria, known).stream()
                .map(mapper::toDto).sorted(SEARCH_ORDER).toList();
        log.info("Patient search: accountId={}, results={}", principal.id(), results.size());
        return results;
    }

    private static final Comparator<PatientDto> SEARCH_ORDER = Comparator
            .comparing((PatientDto patient) -> officialName(patient).map(PatientName::family).orElse(""),
                    String.CASE_INSENSITIVE_ORDER)
            .thenComparing(patient -> officialName(patient).map(name -> String.join(" ", name.given())).orElse(""),
                    String.CASE_INSENSITIVE_ORDER)
            .thenComparing(patient -> patient.birthDate() == null ? "" : patient.birthDate())
            .thenComparing(PatientDto::id);

    private static Optional<PatientName> officialName(PatientDto patient) {
        return patient.names().stream().filter(name -> "official".equals(name.use())).findFirst()
                .or(() -> patient.names().stream().findFirst())
                .map(name -> new PatientName(name.use(), name.text(),
                        name.family() == null ? "" : name.family(), name.given()));
    }

    /**
     * Registers a new Patient in HAPI FHIR on behalf of a DOCTOR or STAFF account.
     *
     * @param principal the authenticated account from the security context, never request input; its current
     *                  role is read from the database so that a stale session role is not sufficient
     * @throws AccessDeniedException                                   for other roles or unknown accounts
     * @throws IllegalArgumentException                                for invalid registration data
     * @throws DuplicatePatientException                               if the personnummer is already registered
     * @throws com.medinexus.medicore.fhir.client.FhirServerException for FHIR failures
     */
    public PatientDto registerPatient(UserDto principal, CreatePatientRequest request) {
        requireClinicalRole(principal);
        // The personnummer, birth date and names are validated by the mapper before the FHIR request (400).
        String id;
        try {
            id = patients.createPatient(request);
        } catch (DuplicatePatientException duplicate) {
            log.info("Patient registration rejected as duplicate: accountId={}", principal.id());
            throw duplicate;
        }
        try {
            registry.record(id, principal.id());
        } catch (DataAccessException | TransactionException failure) {
            // The Patient exists in FHIR but not in the registry: it cannot be found in MediCore and registering
            // it again is a duplicate. The FHIR ID is logged so that an operator can record it.
            log.error("Patient created in FHIR but not recorded in the registry: accountId={}, fhirPatientId={}",
                    principal.id(), id);
            throw new PatientRegistryException(failure);
        }
        log.info("Patient registered: accountId={}", principal.id());
        try {
            // A journal access request that the PATIENT with this personnummer already made and that is waiting
            // for its record is attached to the new Patient (PENDING). No request is ever created here.
            journalAccessMatcher.onPatientRegistered(id, request.personnummer());
        } catch (RuntimeException failure) {
            // The registration itself succeeded; the waiting request is matched later when the patient's status
            // is read.
            log.warn("Journal access matching after patient registration failed: accountId={}, cause={}",
                    principal.id(), failure.getClass().getSimpleName());
        }
        Patient registered = mapper.toResource(request);
        registered.setId(id);
        return mapper.toDto(registered);
    }

    /**
     * The profile of one Patient, read directly from HAPI FHIR, for a DOCTOR or STAFF account (US-04.02).
     *
     * <p>Order: the account's current role (403), the ID format (400), {@link PatientAccessService} (403), then
     * exactly one {@code GET [base]/Patient/{id}}. PATIENT self-view is not part of this operation.
     *
     * @throws AccessDeniedException     for other roles, unknown accounts or denied access
     * @throws IllegalArgumentException  for an ID that is not a logical FHIR ID
     * @throws PatientNotFoundException  if the FHIR server answers 404 or 410
     * @throws FhirServerException       for other FHIR failures, including a response for another Patient
     */
    public PatientProfileDto getPatient(UserDto principal, String id) {
        requireClinicalRole(principal);
        if (id == null || !id.matches(LOGICAL_ID)) {
            throw new IllegalArgumentException("A valid logical FHIR Patient ID is required");
        }
        patientAccess.checkPatientAccess(principal, id);
        // Only Patients that belong to MediCore; any other Patient on the shared server is "not found".
        if (!registry.isKnown(id)) {
            log.info("Patient profile not found: accountId={}", principal.id());
            throw new PatientNotFoundException();
        }
        Patient patient;
        try {
            patient = patients.readPatient(id);
        } catch (FhirServerException failure) {
            int status = failure.httpStatus().orElse(0);
            if (failure.type() == FhirServerException.Type.CLIENT_ERROR && (status == 404 || status == 410)) {
                log.info("Patient profile not found: accountId={}", principal.id());
                throw new PatientNotFoundException();
            }
            throw failure;
        }
        if (patient == null || !id.equals(patient.getIdElement().getIdPart())) {
            // Never show one Patient's data for another Patient's ID.
            throw new FhirServerException("patient-read", FhirServerException.Type.INVALID_RESPONSE, null, List.of(),
                    null);
        }
        log.info("Patient profile viewed: accountId={}", principal.id());
        return PatientProfileDto.withContact(mapper.toDto(patient), linkedContact(id), PatientMapper.versionOf(patient));
    }

    /**
     * Updates the gender of a registered Patient in HAPI FHIR (US-04.04), for DOCTOR and STAFF only. Names,
     * personnummer, birth date and the FHIR ID can never change: the request has no such fields, the Patient read
     * from FHIR is changed only in its gender, and the names, identifiers and birth date are compared before writing
     * and with the server's answer. Nothing is stored in PostgreSQL; the linked account, its names and journal access are
     * unaffected.
     *
     * <p>Order: role from PostgreSQL (403), ID format (400), {@link PatientAccessService} (403), the update data
     * (400, before any FHIR request), the version the client read ({@code If-Match}, 428 if missing), MediCore's
     * registry (404 without a FHIR request), one read (404), the version check (409), one version-aware update
     * (409 on a concurrent change).
     *
     * @param expectedVersion the FHIR version shown to the client ({@link PatientProfileDto#version()})
     * @throws PatientVersionRequiredException if no version is given
     * @throws PatientVersionConflictException if the Patient changed since that version
     */
    public PatientProfileDto updatePatient(UserDto principal, String id, String expectedVersion,
            UpdatePatientRequest update) {
        requireClinicalRole(principal);
        if (id == null || !id.matches(LOGICAL_ID)) {
            throw new IllegalArgumentException("A valid logical FHIR Patient ID is required");
        }
        patientAccess.checkPatientAccess(principal, id);
        // Validates all values on a scratch Patient first, so invalid data never reaches the FHIR server.
        mapper.applyGender(new Patient(), update);
        if (expectedVersion == null) throw new PatientVersionRequiredException();
        if (!expectedVersion.matches(LOGICAL_ID)) {
            throw new IllegalArgumentException("A valid patient record version is required");
        }
        if (!registry.isKnown(id)) {
            log.info("Patient update not found: accountId={}", principal.id());
            throw new PatientNotFoundException();
        }
        Patient patient;
        try {
            patient = patients.readPatient(id);
        } catch (FhirServerException failure) {
            if (isMissing(failure)) {
                log.info("Patient update not found: accountId={}", principal.id());
                throw new PatientNotFoundException();
            }
            throw failure;
        }
        if (patient == null || !id.equals(patient.getIdElement().getIdPart())) {
            throw new FhirServerException("patient-read", FhirServerException.Type.INVALID_RESPONSE, null, List.of(),
                    null);
        }
        String currentVersion = PatientMapper.versionOf(patient);
        if (currentVersion == null) {
            // Without a version the update could overwrite a concurrent change: refuse it.
            throw new FhirServerException("patient-read", FhirServerException.Type.INVALID_RESPONSE, null, List.of(),
                    null);
        }
        if (!currentVersion.equals(expectedVersion)) {
            log.info("Patient update rejected, changed concurrently: accountId={}", principal.id());
            throw new PatientVersionConflictException();
        }

        List<org.hl7.fhir.r4.model.Identifier> identifiers = patient.getIdentifier().stream()
                .map(org.hl7.fhir.r4.model.Identifier::copy).toList();
        var birthDate = patient.getBirthDateElement().copy();
        List<org.hl7.fhir.r4.model.HumanName> names = patient.getName().stream()
                .map(org.hl7.fhir.r4.model.HumanName::copy).toList();
        mapper.applyGender(patient, update);
        if (!sameIdentifiers(identifiers, patient.getIdentifier())
                || !birthDate.equalsDeep(patient.getBirthDateElement()) || !sameNames(names, patient.getName())) {
            throw new IllegalStateException("Immutable patient data would change");
        }

        Patient updated;
        try {
            updated = patients.updatePatient(patient, currentVersion);
        } catch (FhirServerException failure) {
            int status = failure.httpStatus().orElse(0);
            if (failure.type() == FhirServerException.Type.CLIENT_ERROR && (status == 409 || status == 412)) {
                log.info("Patient update rejected, changed concurrently: accountId={}", principal.id());
                throw new PatientVersionConflictException();
            }
            if (isMissing(failure)) throw new PatientNotFoundException();
            throw failure;
        }
        if (!sameIdentifiers(identifiers, updated.getIdentifier())
                || !birthDate.equalsDeep(updated.getBirthDateElement()) || !sameNames(names, updated.getName())) {
            // The server stored something other than what was sent; never report this as a success.
            throw new FhirServerException("patient-update", FhirServerException.Type.INVALID_RESPONSE, null,
                    List.of(), null);
        }
        log.info("Patient updated: accountId={}", principal.id());
        return PatientProfileDto.withContact(mapper.toDto(updated), linkedContact(id), PatientMapper.versionOf(updated));
    }

    private static boolean sameNames(List<org.hl7.fhir.r4.model.HumanName> expected,
            List<org.hl7.fhir.r4.model.HumanName> actual) {
        if (expected.size() != actual.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).equalsDeep(actual.get(i))) return false;
        }
        return true;
    }

    private static boolean sameIdentifiers(List<org.hl7.fhir.r4.model.Identifier> expected,
            List<org.hl7.fhir.r4.model.Identifier> actual) {
        if (expected.size() != actual.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).equalsDeep(actual.get(i))) return false;
        }
        return true;
    }

    private static boolean isMissing(FhirServerException failure) {
        int status = failure.httpStatus().orElse(0);
        return failure.type() == FhirServerException.Type.CLIENT_ERROR && (status == 404 || status == 410);
    }

    private void requireClinicalRole(UserDto principal) {
        if (principal == null || principal.id() == null || principal.id() <= 0) throw denied();
        Role role = users.findById(principal.id()).map(account -> account.getRole()).orElseThrow(PatientService::denied);
        if (role != Role.DOCTOR && role != Role.STAFF) throw denied();
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Access denied");
    }
}
