package com.medinexus.medicore.medicalrecord.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Reference;
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
import com.medinexus.medicore.fhir.client.FhirConditionClient;
import com.medinexus.medicore.fhir.client.FhirEncounterClient;
import com.medinexus.medicore.fhir.client.FhirPatientClient;
import com.medinexus.medicore.fhir.client.FhirPractitionerClient;
import com.medinexus.medicore.fhir.client.FhirReferences;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.fhir.mapper.EncounterMapper;
import com.medinexus.medicore.medicalrecord.dto.ClinicalNoteDto.EncounterContext;
import com.medinexus.medicore.medicalrecord.dto.ConditionDto;
import com.medinexus.medicore.medicalrecord.dto.ConditionDto.TemporalValue;
import com.medinexus.medicore.medicalrecord.dto.CreateConditionRequest;
import com.medinexus.medicore.medicalrecord.dto.CreateConditionRequest.ClinicalStatus;
import com.medinexus.medicore.medicalrecord.dto.CreateConditionRequest.VerificationStatus;
import com.medinexus.medicore.medicalrecord.dto.DiagnosisCodeSystem;
import com.medinexus.medicore.medicalrecord.dto.DiagnosisDto;
import com.medinexus.medicore.medicalrecord.dto.EncounterDto;
import com.medinexus.medicore.medicalrecord.dto.NewDiagnosisRequest;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Coding;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.QuantityValue;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.SubjectReference;
import com.medinexus.medicore.patient.registry.PatientRegistry;
import com.medinexus.medicore.patient.service.PatientNotFoundException;
import com.medinexus.medicore.practitioner.service.PractitionerDirectory;

/**
 * Diagnoses (US-05.02), stored only in HAPI FHIR as R4 {@code Condition} through the Epic 3 {@link FhirConditionClient}
 * and {@code ConditionMapper}.
 *
 * <ul>
 *   <li>DOCTOR and STAFF whose account is linked to a Practitioner ({@code users.fhir_practitioner_id}) record
 *       diagnoses for a Patient known to MediCore (registered or imported), optionally on one of that Patient's
 *       visits. The recorder is always the signed-in clinician's own Practitioner; roles are read from PostgreSQL.</li>
 *   <li>DOCTOR and STAFF read all Conditions of a Patient known to MediCore, including Conditions that were not
 *       created by MediCore.</li>
 *   <li>A PATIENT reads only the Conditions of their own approved record ({@code users.fhir_patient_id}); the Patient
 *       is never taken from the request. Patients cannot create, change or delete diagnoses.</li>
 * </ul>
 * Nothing is stored in PostgreSQL. Logs contain account IDs and counts only, never diagnoses or patient details.
 */
@Service
public class DiagnosisService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisService.class);
    private static final String LOGICAL_ID = "[A-Za-z0-9.-]{1,64}";
    private static final DateTimeFormatter RECORDED = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX");
    private static final String CATEGORY_SYSTEM = "http://terminology.hl7.org/CodeSystem/condition-category";
    /** Maximum length of the diagnosis text in characters. */
    public static final int MAX_DIAGNOSIS_LENGTH = 250;
    /** Upper bound for recorder name lookups per list. */
    static final int MAX_NAME_LOOKUPS = 25;
    /** Verification statuses a new diagnosis can have; refuted and entered-in-error are not "recording" one. */
    private static final List<VerificationStatus> NEW_VERIFICATION_STATUSES = List.of(VerificationStatus.CONFIRMED,
            VerificationStatus.PROVISIONAL, VerificationStatus.DIFFERENTIAL, VerificationStatus.UNCONFIRMED);

    private final UserRepository users;
    private final PatientAccessService patientAccess;
    private final PatientRegistry registry;
    private final FhirPatientClient patients;
    private final FhirEncounterClient encounters;
    private final FhirPractitionerClient practitioners;
    private final FhirConditionClient conditions;
    private final FhirReferences references;
    private final EncounterMapper encounterMapper;
    private final Clock clock;

    @Autowired
    public DiagnosisService(UserRepository users, PatientAccessService patientAccess, PatientRegistry registry,
            FhirPatientClient patients, FhirEncounterClient encounters, FhirPractitionerClient practitioners,
            FhirConditionClient conditions, FhirReferences references, EncounterMapper encounterMapper) {
        this(users, patientAccess, registry, patients, encounters, practitioners, conditions, references,
                encounterMapper, Clock.systemUTC());
    }

    DiagnosisService(UserRepository users, PatientAccessService patientAccess, PatientRegistry registry,
            FhirPatientClient patients, FhirEncounterClient encounters, FhirPractitionerClient practitioners,
            FhirConditionClient conditions, FhirReferences references, EncounterMapper encounterMapper, Clock clock) {
        this.users = users;
        this.patientAccess = patientAccess;
        this.registry = registry;
        this.patients = patients;
        this.encounters = encounters;
        this.practitioners = practitioners;
        this.conditions = conditions;
        this.references = references;
        this.encounterMapper = encounterMapper;
        this.clock = clock;
    }

    // ---------- create ----------

    /**
     * Records a diagnosis for a Patient known to MediCore (DOCTOR and STAFF with a linked Practitioner). Everything
     * is checked before the single FHIR write: the request (400), the clinician's Practitioner link (422), the
     * Patient's registry entry and existence (404), the optional visit's existence and Patient (422) and the
     * Practitioner's existence (422). Never creates an Encounter or Practitioner and never changes another Condition.
     *
     * @throws DiagnosisRequestException with a code (see there)
     * @throws PatientNotFoundException  if the Patient is not known to MediCore or does not exist
     * @throws AccessDeniedException     for other roles
     * @throws IllegalArgumentException  for a malformed Patient ID
     * @throws FhirServerException       for FHIR failures (a failed create may still have been committed)
     */
    public DiagnosisDto create(UserDto principal, String patientId, NewDiagnosisRequest request) {
        User account = requireClinician(principal);
        requireLogicalId(patientId);
        patientAccess.checkPatientAccess(principal, patientId);
        ValidDiagnosis diagnosis = validate(request);
        String practitionerId = account.getFhirPractitionerId();
        if (practitionerId == null || !practitionerId.matches(LOGICAL_ID)) {
            log.info("Diagnosis not recorded, no linked practitioner: accountId={}", principal.id());
            throw new DiagnosisRequestException("PRACTITIONER_NOT_LINKED");
        }
        if (!registry.isKnown(patientId)) {
            log.info("Diagnosis not recorded, patient not found: accountId={}", principal.id());
            throw new PatientNotFoundException();
        }
        requirePatientExists(patientId);
        EncounterContext visit = null;
        if (diagnosis.encounterId() != null) {
            Encounter encounter = readOrFail(() -> encounters.readEncounter(diagnosis.encounterId()),
                    "ENCOUNTER_NOT_FOUND");
            if (!diagnosis.encounterId().equals(encounter.getIdElement().getIdPart())) throw invalidResponse("encounter-read");
            if (!references.refersTo(encounter.getSubject(), "Patient", patientId)) {
                log.info("Diagnosis not recorded, encounter of another patient: accountId={}", principal.id());
                throw new DiagnosisRequestException("ENCOUNTER_NOT_FOR_PATIENT");
            }
            visit = context(diagnosis.encounterId(), encounter);
        }
        Practitioner practitioner = readOrFail(() -> practitioners.readPractitioner(practitionerId),
                "PRACTITIONER_NOT_FOUND");
        if (!practitionerId.equals(practitioner.getIdElement().getIdPart())) throw invalidResponse("practitioner-read");

        String recordedDate = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC)
                .format(RECORDED);
        String category = diagnosis.encounterId() == null ? "problem-list-item" : "encounter-diagnosis";
        Concept code = new Concept(diagnosis.text(), diagnosis.codeSystem() == null ? List.of()
                : List.of(new Coding(diagnosis.codeSystem().system(), null, diagnosis.code(), null, null)));
        CreateConditionRequest condition = new CreateConditionRequest(patientId, code, diagnosis.clinicalStatus(),
                diagnosis.verificationStatus(),
                List.of(new Concept(null, List.of(new Coding(CATEGORY_SYSTEM, null, category,
                        category.equals("problem-list-item") ? "Problem List Item" : "Encounter Diagnosis", null)))),
                null, diagnosis.onsetDate(), null, recordedDate, diagnosis.encounterId(), practitionerId);
        String id = conditions.createCondition(condition);
        log.info("Diagnosis recorded: accountId={}", principal.id());
        return new DiagnosisDto(id, patientId, diagnosis.text(), diagnosis.codeSystem() == null ? null
                : new DiagnosisDto.Code(diagnosis.codeSystem().system(), diagnosis.codeSystem().label(),
                        diagnosis.code(), null),
                diagnosis.clinicalStatus().code(), diagnosis.verificationStatus().code(), category, recordedDate,
                diagnosis.onsetDate(), visit, PractitionerDirectory.displayName(practitioner));
    }

    record ValidDiagnosis(String text, DiagnosisCodeSystem codeSystem, String code, ClinicalStatus clinicalStatus,
            VerificationStatus verificationStatus, String onsetDate, String encounterId) {
    }

    ValidDiagnosis validate(NewDiagnosisRequest request) {
        if (request == null) throw new DiagnosisRequestException("INVALID_REQUEST");
        if (request.diagnosis() == null || request.diagnosis().isBlank()) {
            throw new DiagnosisRequestException("DIAGNOSIS_REQUIRED");
        }
        String text = request.diagnosis().strip();
        if (text.codePointCount(0, text.length()) > MAX_DIAGNOSIS_LENGTH) {
            throw new DiagnosisRequestException("DIAGNOSIS_TOO_LONG");
        }
        if (!java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(text)
                || text.codePoints().anyMatch(c -> c < 0x20 || c >= 0x7F && c <= 0x9F)) {
            throw new DiagnosisRequestException("DIAGNOSIS_INVALID");
        }
        DiagnosisCodeSystem system = null;
        String code = null;
        if (request.codeSystem() != null || request.code() != null) {
            if (request.code() == null) throw new DiagnosisRequestException("CODE_REQUIRED");
            if (request.codeSystem() == null) throw new DiagnosisRequestException("CODE_SYSTEM_REQUIRED");
            system = enumValue(DiagnosisCodeSystem.class, request.codeSystem(), "CODE_SYSTEM_INVALID");
            code = system.normalize(request.code());
            if (code == null) throw new DiagnosisRequestException("CODE_INVALID");
        }
        ClinicalStatus clinical = request.clinicalStatus() == null ? ClinicalStatus.ACTIVE
                : enumValue(ClinicalStatus.class, request.clinicalStatus(), "CLINICAL_STATUS_INVALID");
        VerificationStatus verification = request.verificationStatus() == null ? VerificationStatus.CONFIRMED
                : enumValue(VerificationStatus.class, request.verificationStatus(), "VERIFICATION_STATUS_INVALID");
        if (!NEW_VERIFICATION_STATUSES.contains(verification)) {
            throw new DiagnosisRequestException("VERIFICATION_STATUS_INVALID");
        }
        String onset = null;
        if (request.onsetDate() != null) {
            LocalDate date;
            try {
                if (!request.onsetDate().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new DateTimeParseException("", "", 0);
                date = LocalDate.parse(request.onsetDate());
            } catch (DateTimeParseException invalid) {
                throw new DiagnosisRequestException("ONSET_INVALID");
            }
            if (date.getYear() < 1900) throw new DiagnosisRequestException("ONSET_INVALID");
            // The latest calendar date anywhere on Earth (UTC+14), so a local "today" is never rejected.
            if (date.isAfter(LocalDate.now(clock.withZone(ZoneOffset.ofHours(14))))) {
                throw new DiagnosisRequestException("ONSET_IN_FUTURE");
            }
            onset = date.toString();
        }
        String encounterId = request.encounterId();
        if (encounterId != null && !encounterId.matches(LOGICAL_ID)) {
            throw new DiagnosisRequestException("ENCOUNTER_INVALID");
        }
        return new ValidDiagnosis(text, system, code, clinical, verification, onset, encounterId);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String errorCode) {
        for (E candidate : type.getEnumConstants()) {
            if (candidate.name().equals(value)) return candidate;
        }
        throw new DiagnosisRequestException(errorCode);
    }

    // ---------- read ----------

    /** All diagnoses of a Patient known to MediCore, newest first (DOCTOR and STAFF). */
    public List<DiagnosisDto> listForPatient(UserDto principal, String patientId) {
        requireClinician(principal);
        requireLogicalId(patientId);
        patientAccess.checkPatientAccess(principal, patientId);
        if (!registry.isKnown(patientId)) {
            log.info("Diagnoses not listed, patient not found: accountId={}", principal.id());
            throw new PatientNotFoundException();
        }
        List<DiagnosisDto> result = summarize(patientId);
        log.info("Diagnoses listed: accountId={}, count={}", principal.id(), result.size());
        return result;
    }

    /**
     * The signed-in PATIENT's own diagnoses, newest first. The Patient is the account's approved link only.
     *
     * @throws DiagnosisRequestException RECORD_NOT_LINKED without an approved link
     */
    public List<DiagnosisDto> listOwn(UserDto principal) {
        User account = account(principal);
        if (account.getRole() != Role.PATIENT) throw denied();
        String patientId = account.getFhirPatientId();
        if (patientId == null) throw new DiagnosisRequestException("RECORD_NOT_LINKED");
        patientAccess.checkPatientAccess(principal, patientId);
        List<DiagnosisDto> result = summarize(patientId);
        log.info("Own diagnoses listed: accountId={}, count={}", principal.id(), result.size());
        return result;
    }

    /**
     * Readable diagnoses, newest recorded first (undated last). The search itself rejects any Condition of another
     * Patient. Conditions whose verification status is {@code entered-in-error} are left out. The visit is shown only
     * if it is one of this Patient's visits (one Encounter search; if it fails, diagnoses are shown without visit
     * details). Recorder names are read once per Practitioner (bounded).
     */
    private List<DiagnosisDto> summarize(String patientId) {
        List<ConditionDto> found = conditions.findByPatientId(patientId);
        if (found.isEmpty()) return List.of();
        Map<String, EncounterContext> visits = found.stream().anyMatch(condition -> condition.encounter() != null)
                ? visits(patientId) : Map.of();
        Map<String, String> recorderNames = new HashMap<>();
        List<DiagnosisDto> result = new ArrayList<>();
        for (ConditionDto condition : found) {
            String verification = statusCode(condition.verificationStatus(), VerificationStatus.SYSTEM);
            if ("entered-in-error".equals(verification)) continue;
            String encounterId = references.logicalId(reference(condition.encounter()), "Encounter");
            result.add(new DiagnosisDto(condition.id(), patientId, diagnosisText(condition.code()),
                    code(condition.code()), statusCode(condition.clinicalStatus(), ClinicalStatus.SYSTEM), verification,
                    condition.category().isEmpty() ? null : statusCode(condition.category().getFirst(), CATEGORY_SYSTEM),
                    condition.recordedDate(), onset(condition.onset()),
                    encounterId == null ? null : visits.get(encounterId),
                    recorder(condition.recorder(), recorderNames)));
        }
        result.sort(Comparator.comparing((DiagnosisDto diagnosis) -> EncounterService.sortKey(diagnosis.recordedDate()),
                Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(DiagnosisDto::id));
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
            // Already logged safely; the diagnoses themselves are still shown.
            return Map.of();
        }
        return visits;
    }

    private String recorder(SubjectReference recorder, Map<String, String> cache) {
        String id = references.logicalId(reference(recorder), "Practitioner");
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

    private static Reference reference(SubjectReference reference) {
        if (reference == null || reference.reference() == null) return null;
        Reference result = new Reference(reference.reference());
        if (reference.type() != null) result.setType(reference.type());
        return result;
    }

    /** {@code code.text}, otherwise the first coding display; null if neither (the code alone is shown separately). */
    static String diagnosisText(Concept code) {
        if (code == null) return null;
        if (code.text() != null && !code.text().isBlank()) return code.text().strip();
        return code.coding().stream().map(Coding::display).filter(display -> display != null && !display.isBlank())
                .map(String::strip).findFirst().orElse(null);
    }

    private static DiagnosisDto.Code code(Concept code) {
        if (code == null) return null;
        return code.coding().stream().filter(coding -> coding.code() != null && !coding.code().isBlank())
                .findFirst().map(coding -> {
                    DiagnosisCodeSystem known = DiagnosisCodeSystem.fromSystem(coding.system());
                    return new DiagnosisDto.Code(coding.system(), known == null ? null : known.label(),
                            coding.code().strip(), coding.display());
                }).orElse(null);
    }

    /** The code in the expected system, otherwise the concept text (lower case), otherwise null. */
    private static String statusCode(Concept concept, String system) {
        if (concept == null) return null;
        for (Coding coding : concept.coding()) {
            if (system.equals(coding.system()) && coding.code() != null && !coding.code().isBlank()) {
                return coding.code().strip();
            }
        }
        return concept.text() == null || concept.text().isBlank() ? null
                : concept.text().strip().toLowerCase(Locale.ROOT);
    }

    /** A readable onset: the dateTime or string as stored, a period's start, an age or a range. */
    static String onset(TemporalValue onset) {
        if (onset == null || onset.data() == null) return null;
        return switch (onset.data()) {
            case ConditionDto.TextValue text -> text.value();
            case ConditionDto.PeriodValue period -> period.start() != null ? period.start() : period.end();
            case ConditionDto.AgeValue age -> age.quantity() == null ? null : "Age " + quantity(age.quantity());
            case ConditionDto.RangeValue range -> "Age " + (range.low() == null ? "?" : quantity(range.low())) + " – "
                    + (range.high() == null ? "?" : quantity(range.high()));
        };
    }

    private static String quantity(QuantityValue quantity) {
        String value = quantity.value() == null ? "?" : quantity.value().stripTrailingZeros().toPlainString();
        String unit = quantity.unit() != null ? quantity.unit() : quantity.code();
        return unit == null ? value : value + " " + unit;
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
            if (resource == null) throw new DiagnosisRequestException(missingCode);
            return resource;
        } catch (FhirServerException failure) {
            if (isMissing(failure)) throw new DiagnosisRequestException(missingCode);
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
        return users.findById(principal.id()).orElseThrow(DiagnosisService::denied);
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
