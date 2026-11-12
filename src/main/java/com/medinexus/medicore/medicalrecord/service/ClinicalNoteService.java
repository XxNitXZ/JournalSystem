package com.medinexus.medicore.medicalrecord.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Enumerations.DocumentReferenceStatus;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.auth.service.PatientAccessService;
import com.medinexus.medicore.fhir.client.FhirDocumentReferenceClient;
import com.medinexus.medicore.fhir.client.FhirEncounterClient;
import com.medinexus.medicore.fhir.client.FhirPatientClient;
import com.medinexus.medicore.fhir.client.FhirPractitionerClient;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.fhir.mapper.ClinicalNoteMapper;
import com.medinexus.medicore.fhir.mapper.EncounterMapper;
import com.medinexus.medicore.medicalrecord.dto.ClinicalNoteDto;
import com.medinexus.medicore.medicalrecord.dto.ClinicalNoteDto.EncounterContext;
import com.medinexus.medicore.medicalrecord.dto.CreateClinicalNoteRequest;
import com.medinexus.medicore.medicalrecord.dto.EncounterDto;
import com.medinexus.medicore.patient.registry.PatientRegistry;
import com.medinexus.medicore.patient.service.PatientNotFoundException;
import com.medinexus.medicore.practitioner.service.PractitionerDirectory;

/**
 * Clinical notes (US-05.01), stored only in HAPI FHIR as R4 {@code DocumentReference} (see {@link ClinicalNoteMapper}).
 *
 * <ul>
 *   <li>DOCTOR and STAFF whose account is linked to a Practitioner ({@code users.fhir_practitioner_id}) create notes
 *       for a Patient known to MediCore (registered or imported), on one of that Patient's existing visits. The
 *       author is always the signed-in clinician's own Practitioner; roles are read from PostgreSQL.</li>
 *   <li>DOCTOR and STAFF read the notes of a Patient known to MediCore.</li>
 *   <li>A PATIENT reads only the notes of their own approved record ({@code users.fhir_patient_id}); the Patient is
 *       never taken from the request. Patients cannot create notes.</li>
 * </ul>
 * Nothing is stored in PostgreSQL. Logs contain account IDs and counts only, never note text or patient details.
 */
@Service
public class ClinicalNoteService {

    private static final Logger log = LoggerFactory.getLogger(ClinicalNoteService.class);
    private static final String LOGICAL_ID = "[A-Za-z0-9.-]{1,64}";
    /** Maximum note length in characters (Unicode code points), after line endings are normalized and trimmed. */
    public static final int MAX_CONTENT_LENGTH = 10_000;
    /** Upper bound for author name lookups per list. */
    static final int MAX_NAME_LOOKUPS = 25;

    private final UserRepository users;
    private final PatientAccessService patientAccess;
    private final PatientRegistry registry;
    private final FhirPatientClient patients;
    private final FhirEncounterClient encounters;
    private final FhirPractitionerClient practitioners;
    private final FhirDocumentReferenceClient notes;
    private final ClinicalNoteMapper mapper;
    private final EncounterMapper encounterMapper;
    private final Clock clock;

    @Autowired
    public ClinicalNoteService(UserRepository users, PatientAccessService patientAccess, PatientRegistry registry,
            FhirPatientClient patients, FhirEncounterClient encounters, FhirPractitionerClient practitioners,
            FhirDocumentReferenceClient notes, ClinicalNoteMapper mapper, EncounterMapper encounterMapper) {
        this(users, patientAccess, registry, patients, encounters, practitioners, notes, mapper, encounterMapper,
                Clock.systemUTC());
    }

    ClinicalNoteService(UserRepository users, PatientAccessService patientAccess, PatientRegistry registry,
            FhirPatientClient patients, FhirEncounterClient encounters, FhirPractitionerClient practitioners,
            FhirDocumentReferenceClient notes, ClinicalNoteMapper mapper, EncounterMapper encounterMapper,
            Clock clock) {
        this.users = users;
        this.patientAccess = patientAccess;
        this.registry = registry;
        this.patients = patients;
        this.encounters = encounters;
        this.practitioners = practitioners;
        this.notes = notes;
        this.mapper = mapper;
        this.encounterMapper = encounterMapper;
        this.clock = clock;
    }

    // ---------- create ----------

    /**
     * Creates a note on an existing visit of a Patient known to MediCore (DOCTOR and STAFF with a linked
     * Practitioner). Everything is checked before the single FHIR write: the request and text (400), the
     * clinician's Practitioner link (422), the Patient's registry entry and existence (404), the visit's existence
     * and Patient (422) and the Practitioner's existence (422). No visit is ever created here.
     *
     * @throws ClinicalNoteException    with a code (see there)
     * @throws PatientNotFoundException if the Patient is not known to MediCore or does not exist
     * @throws AccessDeniedException    for other roles
     * @throws IllegalArgumentException for a malformed Patient ID
     * @throws FhirServerException      for FHIR failures (a failed create may still have been committed)
     */
    public ClinicalNoteDto create(UserDto principal, String patientId, CreateClinicalNoteRequest request) {
        User account = requireClinician(principal);
        requireLogicalId(patientId);
        patientAccess.checkPatientAccess(principal, patientId);
        if (request == null) throw new ClinicalNoteException("INVALID_REQUEST");
        String encounterId = request.encounterId();
        if (encounterId == null) throw new ClinicalNoteException("ENCOUNTER_REQUIRED");
        if (!encounterId.matches(LOGICAL_ID)) throw new ClinicalNoteException("ENCOUNTER_INVALID");
        String text = normalizedContent(request.content());
        String practitionerId = account.getFhirPractitionerId();
        if (practitionerId == null || !practitionerId.matches(LOGICAL_ID)) {
            log.info("Clinical note not created, no linked practitioner: accountId={}", principal.id());
            throw new ClinicalNoteException("PRACTITIONER_NOT_LINKED");
        }
        if (!registry.isKnown(patientId)) {
            log.info("Clinical note not created, patient not found: accountId={}", principal.id());
            throw new PatientNotFoundException();
        }
        requirePatientExists(patientId);
        Encounter encounter = readOrFail(() -> encounters.readEncounter(encounterId), "ENCOUNTER_NOT_FOUND");
        if (!encounterId.equals(encounter.getIdElement().getIdPart())) throw invalidResponse("encounter-read");
        if (!notes.refersTo(encounter.getSubject(), "Patient", patientId)) {
            log.info("Clinical note not created, encounter of another patient: accountId={}", principal.id());
            throw new ClinicalNoteException("ENCOUNTER_NOT_FOR_PATIENT");
        }
        Practitioner practitioner = readOrFail(() -> practitioners.readPractitioner(practitionerId),
                "PRACTITIONER_NOT_FOUND");
        if (!practitionerId.equals(practitioner.getIdElement().getIdPart())) throw invalidResponse("practitioner-read");

        DocumentReference resource = mapper.toResource(patientId, encounterId, practitionerId, text,
                Instant.now(clock).truncatedTo(ChronoUnit.SECONDS));
        String id = notes.createDocumentReference(resource);
        log.info("Clinical note created: accountId={}", principal.id());
        return new ClinicalNoteDto(id, patientId, context(encounterId, encounter),
                PractitionerDirectory.displayName(practitioner), mapper.createdAt(resource), mapper.status(resource),
                mapper.type(resource), text, true);
    }

    /**
     * The note text as stored: line endings normalized to {@code \n}, leading and trailing whitespace removed, inner
     * line breaks kept. Required, at most {@link #MAX_CONTENT_LENGTH} characters, valid Unicode without control
     * characters other than line feed and tab.
     */
    static String normalizedContent(String content) {
        if (content == null) throw new ClinicalNoteException("CONTENT_REQUIRED");
        String text = content.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (text.isEmpty()) throw new ClinicalNoteException("CONTENT_REQUIRED");
        if (text.codePointCount(0, text.length()) > MAX_CONTENT_LENGTH) {
            throw new ClinicalNoteException("CONTENT_TOO_LONG");
        }
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(text) || text.codePoints()
                .anyMatch(c -> c == 0x7F || c < 0x20 && c != '\n' && c != '\t' || c >= 0x80 && c <= 0x9F)) {
            throw new ClinicalNoteException("CONTENT_INVALID");
        }
        return text;
    }

    // ---------- read ----------

    /** The notes of a Patient known to MediCore, newest first (DOCTOR and STAFF). */
    public List<ClinicalNoteDto> listForPatient(UserDto principal, String patientId) {
        requireClinician(principal);
        requireLogicalId(patientId);
        patientAccess.checkPatientAccess(principal, patientId);
        if (!registry.isKnown(patientId)) {
            log.info("Clinical notes not listed, patient not found: accountId={}", principal.id());
            throw new PatientNotFoundException();
        }
        List<ClinicalNoteDto> result = summarize(patientId);
        log.info("Clinical notes listed: accountId={}, count={}", principal.id(), result.size());
        return result;
    }

    /**
     * The signed-in PATIENT's own notes, newest first. The Patient is the account's approved link only.
     *
     * @throws ClinicalNoteException RECORD_NOT_LINKED without an approved link
     */
    public List<ClinicalNoteDto> listOwn(UserDto principal) {
        User account = account(principal);
        if (account.getRole() != Role.PATIENT) throw denied();
        String patientId = account.getFhirPatientId();
        if (patientId == null) throw new ClinicalNoteException("RECORD_NOT_LINKED");
        patientAccess.checkPatientAccess(principal, patientId);
        List<ClinicalNoteDto> result = summarize(patientId);
        log.info("Own clinical notes listed: accountId={}, count={}", principal.id(), result.size());
        return result;
    }

    /**
     * Readable notes, newest first. Notes marked {@code entered-in-error} are left out. The visit of a note is shown
     * only if it is one of this Patient's visits (one Encounter search; if it fails, notes are shown without visit
     * details). Author names are read once per Practitioner (bounded).
     */
    private List<ClinicalNoteDto> summarize(String patientId) {
        List<DocumentReference> found = notes.findByPatientId(patientId);
        if (found.isEmpty()) return List.of();
        Map<String, EncounterContext> visits = visits(patientId);
        Map<String, String> authorNames = new HashMap<>();
        List<ClinicalNoteDto> result = new ArrayList<>();
        for (DocumentReference note : found) {
            if (note.getStatus() == DocumentReferenceStatus.ENTEREDINERROR) continue;
            EncounterContext visit = note.getContext().getEncounter().stream()
                    .map(reference -> notes.logicalId(reference, "Encounter"))
                    .filter(visits::containsKey).map(visits::get).findFirst().orElse(null);
            ClinicalNoteMapper.NoteContent content = mapper.content(note);
            result.add(new ClinicalNoteDto(note.getIdElement().getIdPart(), patientId, visit,
                    author(note, authorNames), mapper.createdAt(note), mapper.status(note), mapper.type(note),
                    content.text(), content.available()));
        }
        result.sort(Comparator.comparing((ClinicalNoteDto note) -> EncounterService.sortKey(note.createdAt()),
                Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(ClinicalNoteDto::id));
        return List.copyOf(result);
    }

    private Map<String, EncounterContext> visits(String patientId) {
        Map<String, EncounterContext> visits = new HashMap<>();
        try {
            for (EncounterDto encounter : encounters.findByPatientId(patientId)) {
                visits.put(encounter.id(), new EncounterContext(encounter.id(),
                        encounter.period() == null ? null : encounter.period().start(),
                        EncounterService.typeText(encounter.types())));
            }
        } catch (FhirServerException unavailable) {
            // Already logged safely; the notes themselves are still shown.
            return Map.of();
        }
        return visits;
    }

    private String author(DocumentReference note, Map<String, String> cache) {
        String id = note.getAuthor().stream().map(reference -> notes.logicalId(reference, "Practitioner"))
                .filter(candidate -> candidate != null).findFirst().orElse(null);
        if (id == null) return null;
        if (cache.containsKey(id)) return cache.get(id);
        if (cache.size() >= MAX_NAME_LOOKUPS) return null;
        String name;
        try {
            name = PractitionerDirectory.displayName(practitioners.readPractitioner(id));
        } catch (FhirServerException | IllegalArgumentException unreadable) {
            name = null;
        }
        cache.put(id, name);
        return name;
    }

    private EncounterContext context(String encounterId, Encounter encounter) {
        try {
            EncounterDto dto = encounterMapper.toDto(encounter);
            return new EncounterContext(encounterId, dto.period() == null ? null : dto.period().start(),
                    EncounterService.typeText(dto.types()));
        } catch (IllegalArgumentException incomplete) {
            return new EncounterContext(encounterId, null, null);
        }
    }

    // ---------- helpers ----------

    private void requirePatientExists(String patientId) {
        Patient patient;
        try {
            patient = patients.readPatient(patientId);
        } catch (FhirServerException failure) {
            if (isMissing(failure)) throw new PatientNotFoundException();
            throw failure;
        }
        if (patient == null || !patientId.equals(patient.getIdElement().getIdPart())) {
            throw invalidResponse("patient-read");
        }
    }

    private static <T> T readOrFail(Supplier<T> read, String missingCode) {
        try {
            T resource = read.get();
            if (resource == null) throw new ClinicalNoteException(missingCode);
            return resource;
        } catch (FhirServerException failure) {
            if (isMissing(failure)) throw new ClinicalNoteException(missingCode);
            throw failure;
        }
    }

    private User requireClinician(UserDto principal) {
        User account = account(principal);
        if (account.getRole() != Role.DOCTOR && account.getRole() != Role.STAFF) throw denied();
        return account;
    }

    private User account(UserDto principal) {
        if (principal == null || principal.id() == null || principal.id() <= 0) throw denied();
        return users.findById(principal.id()).orElseThrow(ClinicalNoteService::denied);
    }

    private static void requireLogicalId(String patientId) {
        if (patientId == null || !patientId.matches(LOGICAL_ID)) {
            throw new IllegalArgumentException("A valid logical FHIR Patient ID is required");
        }
    }

    private static boolean isMissing(FhirServerException failure) {
        int status = failure.httpStatus().orElse(0);
        return failure.type() == FhirServerException.Type.CLIENT_ERROR && (status == 404 || status == 410);
    }

    private static FhirServerException invalidResponse(String operation) {
        return new FhirServerException(operation, FhirServerException.Type.INVALID_RESPONSE, null, List.of(), null);
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Access denied");
    }
}
