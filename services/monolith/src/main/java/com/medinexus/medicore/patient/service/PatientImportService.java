package com.medinexus.medicore.patient.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.fhir.client.FhirPatientClient;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.journalaccess.JournalAccessMatcher;
import com.medinexus.medicore.patient.dto.PatientCandidateDto;
import com.medinexus.medicore.patient.dto.PatientDiscoveryResponse;
import com.medinexus.medicore.patient.dto.PatientSearchCriteria;
import com.medinexus.medicore.patient.registry.PatientRegistry;
import com.medinexus.medicore.patient.validation.PatientPersonnummer;
import com.medinexus.medicore.patient.validation.Personnummer;

/**
 * Import of existing FHIR Patients into MediCore (DOCTOR and STAFF): a <strong>reference</strong> import. The Patient
 * on the FHIR server is reused as it is, never created or changed; MediCore only records its logical ID, the importing
 * account and the time in {@code registered_patients}. From then on it is a normal MediCore Patient: search, profile,
 * Encounters and journal access all use the same registry.
 *
 * <p>The discovery search covers the whole configured FHIR server (that is its purpose) but is bounded and read-only;
 * the normal patient search stays restricted to the registry. An import never creates a journal access request and
 * never links an account; it only lets a request that a patient already made and that waits for its record be matched
 * by personnummer (exactly, never by name), and a doctor must still approve it. Logs contain account IDs only.
 */
@Service
public class PatientImportService {

    private static final Logger log = LoggerFactory.getLogger(PatientImportService.class);
    private static final ZoneId SWEDEN = ZoneId.of("Europe/Stockholm");
    private static final String LOGICAL_ID = "[A-Za-z0-9.-]{1,64}";

    /** Search modes of the import search. */
    public enum Mode {
        NAME, PERSONNUMMER, ID
    }

    private final UserRepository users;
    private final FhirPatientClient patients;
    private final PatientRegistry registry;
    private final JournalAccessMatcher journalAccessMatcher;

    public PatientImportService(UserRepository users, FhirPatientClient patients, PatientRegistry registry,
            JournalAccessMatcher journalAccessMatcher) {
        this.users = users;
        this.patients = patients;
        this.registry = registry;
        this.journalAccessMatcher = journalAccessMatcher;
    }

    /**
     * Searches the configured FHIR server for existing Patients by name, exact personnummer or exact FHIR ID. Results
     * say whether each Patient is already known to MediCore and whether it can be imported or matched.
     *
     * @throws PatientImportException SEARCH_INVALID for an invalid mode or value
     * @throws AccessDeniedException  unless the account is currently a DOCTOR or STAFF
     * @throws FhirServerException    for FHIR failures
     */
    public PatientDiscoveryResponse discover(UserDto principal, String mode, String value) {
        requireClinician(principal);
        Mode searchMode = mode(mode);
        List<Patient> found;
        boolean truncated = false;
        if (searchMode == Mode.ID) {
            String id = value == null ? "" : value.strip();
            if (!id.matches(LOGICAL_ID)) throw new PatientImportException("SEARCH_INVALID");
            Patient patient = readOrNull(id);
            found = patient == null ? List.of() : List.of(patient);
        } else {
            FhirPatientClient.Discovery discovery = patients.discoverPatients(criteria(searchMode, value));
            found = discovery.patients();
            truncated = discovery.truncated();
        }
        Set<String> known = new HashSet<>(registry.knownIds());
        List<PatientCandidateDto> candidates = new ArrayList<>();
        for (Patient patient : found) candidates.add(candidate(patient, known));
        log.info("Patient import search: accountId={}, mode={}, count={}, truncated={}", principal.id(), searchMode,
                candidates.size(), truncated);
        return new PatientDiscoveryResponse(candidates, truncated);
    }

    /**
     * Imports an existing FHIR Patient: reads it once, checks that it is usable, and records only its ID in the
     * registry (atomically: a concurrent or repeated import is 409). Nothing is written to FHIR.
     *
     * @throws PatientImportException   ALREADY_REGISTERED, PATIENT_NOT_IMPORTABLE or INVALID_REQUEST
     * @throws PatientNotFoundException if the FHIR server has no such Patient
     * @throws FhirServerException      for FHIR failures
     */
    public PatientCandidateDto importPatient(UserDto principal, String fhirPatientId) {
        requireClinician(principal);
        if (fhirPatientId == null || !fhirPatientId.matches(LOGICAL_ID)) {
            throw new PatientImportException("INVALID_REQUEST");
        }
        if (registry.isRegistered(fhirPatientId)) throw new PatientImportException("ALREADY_REGISTERED");
        Patient patient = readOrNull(fhirPatientId);
        if (patient == null) throw new PatientNotFoundException();
        PatientCandidateDto candidate = candidate(patient, Set.of());
        if (!candidate.importable()) throw new PatientImportException("PATIENT_NOT_IMPORTABLE");
        if (!registry.recordImport(fhirPatientId, principal.id())) {
            throw new PatientImportException("ALREADY_REGISTERED");
        }
        boolean matched = false;
        if (candidate.journalMatchable()) {
            try {
                // A request that the patient already made and that waits for this record; never a new request.
                matched = journalAccessMatcher.onPatientImported(candidate.personnummer());
            } catch (FhirServerException | DataAccessException failure) {
                // The import itself succeeded; the waiting request is matched later (patient's dashboard).
                log.warn("Journal access matching after patient import failed: accountId={}, cause={}",
                        principal.id(), failure.getClass().getSimpleName());
            }
        }
        log.info("Patient imported: accountId={}, journalRequestMatched={}", principal.id(), matched);
        return new PatientCandidateDto(candidate.id(), candidate.name(), candidate.personnummer(), candidate.gender(),
                candidate.birthDate(), true, true, candidate.journalMatchable(), candidate.personnummerStatus());
    }

    // ---------- helpers ----------

    private static Mode mode(String value) {
        if (value == null) throw new PatientImportException("SEARCH_INVALID");
        try {
            return Mode.valueOf(value);
        } catch (IllegalArgumentException invalid) {
            throw new PatientImportException("SEARCH_INVALID");
        }
    }

    /** A name search must be a name; a personnummer search a valid 12-digit personnummer (hyphen allowed). */
    private static PatientSearchCriteria criteria(Mode mode, String value) {
        if (mode == Mode.PERSONNUMMER) {
            String digits = Personnummer.canonical(value);
            if (digits == null) throw new PatientImportException("SEARCH_INVALID");
            return new PatientSearchCriteria(digits, List.of());
        }
        PatientSearchCriteria criteria;
        try {
            criteria = PatientSearchCriteria.parse(value);
        } catch (IllegalArgumentException invalid) {
            throw new PatientImportException("SEARCH_INVALID");
        }
        if (criteria.isPersonnummerSearch()) throw new PatientImportException("SEARCH_INVALID");
        return criteria;
    }

    /** The Patient with this ID, or null if the server does not have it; a response for another ID is invalid. */
    private Patient readOrNull(String id) {
        Patient patient;
        try {
            patient = patients.readPatient(id);
        } catch (FhirServerException failure) {
            int status = failure.httpStatus().orElse(0);
            if (failure.type() == FhirServerException.Type.CLIENT_ERROR && (status == 404 || status == 410)) return null;
            throw failure;
        }
        if (patient == null || !id.equals(patient.getIdElement().getIdPart())) {
            throw new FhirServerException("patient-read", FhirServerException.Type.INVALID_RESPONSE, null, List.of(),
                    null);
        }
        return patient;
    }

    static PatientCandidateDto candidate(Patient patient, Set<String> known) {
        String id = patient.getIdElement().getIdPart();
        String name = displayName(patient);
        // YYYYMMDDXXXX and YYYYMMDD-XXXX (as on the KTH server) are the same personnummer; only MediCore's
        // identifier system counts, and conflicting values are never matched (PatientPersonnummer).
        PatientPersonnummer personnummer = PatientPersonnummer.of(patient, LocalDate.now(SWEDEN));
        String birthDate = patient.hasBirthDate() ? patient.getBirthDateElement().getValueAsString() : null;
        boolean importable = name != null && (birthDate != null || patient.hasIdentifier());
        return new PatientCandidateDto(id, name, personnummer.value(),
                patient.hasGender() ? patient.getGender().toCode() : null, birthDate, known.contains(id), importable,
                personnummer.status() == PatientPersonnummer.Status.VALID, personnummer.status().name());
    }

    /** Given names and family name of the official (otherwise first) name, or its text; null without a name. */
    static String displayName(Patient patient) {
        if (!patient.hasName()) return null;
        HumanName name = patient.getName().stream().filter(candidate -> candidate.getUse() == HumanName.NameUse.OFFICIAL)
                .findFirst().orElse(patient.getNameFirstRep());
        String joined = java.util.stream.Stream.concat(name.getGiven().stream().map(StringType::getValue),
                java.util.stream.Stream.of(name.getFamily()))
                .filter(part -> part != null && !part.isBlank()).map(String::strip).collect(Collectors.joining(" "));
        if (!joined.isEmpty()) return joined;
        return name.hasText() && !name.getText().isBlank() ? name.getText().strip() : null;
    }

    private void requireClinician(UserDto principal) {
        if (principal == null || principal.id() == null || principal.id() <= 0) throw denied();
        Role role = users.findById(principal.id()).map(account -> account.getRole()).orElseThrow(PatientImportService::denied);
        if (role != Role.DOCTOR && role != Role.STAFF) throw denied();
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Access denied");
    }

    /** Request bodies are read strictly: only the given fields, text values only. */
    public static String text(Map<String, Object> body, Set<String> fields, String field) {
        if (body == null || !fields.containsAll(body.keySet())) throw new PatientImportException("INVALID_REQUEST");
        Object value = body.get(field);
        if (value != null && !(value instanceof String)) throw new PatientImportException("INVALID_REQUEST");
        return (String) value;
    }
}
