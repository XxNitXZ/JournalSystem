package com.medinexus.medicore.medicalrecord.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Organization;
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
import com.medinexus.medicore.fhir.client.FhirEncounterClient;
import com.medinexus.medicore.fhir.client.FhirLocationClient;
import com.medinexus.medicore.fhir.client.FhirOrganizationClient;
import com.medinexus.medicore.fhir.client.FhirPatientClient;
import com.medinexus.medicore.fhir.client.FhirPractitionerClient;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest;
import com.medinexus.medicore.medicalrecord.dto.EncounterDto;
import com.medinexus.medicore.medicalrecord.dto.EncounterSummaryDto;
import com.medinexus.medicore.medicalrecord.dto.NewEncounterRequest;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Coding;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.SubjectReference;
import com.medinexus.medicore.medicalrecord.dto.VisitType;
import com.medinexus.medicore.patient.registry.PatientRegistry;
import com.medinexus.medicore.patient.service.PatientNotFoundException;
import com.medinexus.medicore.practitioner.service.PractitionerDirectory;

/**
 * Encounters (visits) of registered Patients (US-05.05), stored only in HAPI FHIR through the Epic 3 clients.
 *
 * <ul>
 *   <li>DOCTOR and STAFF create and list the Encounters of a Patient known to MediCore (registry or account link);
 *       roles are read from PostgreSQL and {@link PatientAccessService} is applied.</li>
 *   <li>A PATIENT lists only their own Encounters: the Patient comes from the account's approved link
 *       ({@code users.fhir_patient_id}), never from the request. Patients cannot create Encounters.</li>
 * </ul>
 * Every Encounter references the Patient ({@code subject}), one Practitioner linked to a MediCore clinician
 * ({@code participant.individual}, type ATND), the visit time ({@code period.start}, with offset), a valid R4 status
 * and a SNOMED CT visit type; Organization and Location only when given and found in FHIR. The search
 * ({@link FhirEncounterClient#findByPatientId}) rejects any result whose subject is not exactly that Patient and
 * follows at most 10 pages / 1,000 results. Nothing is stored in PostgreSQL. Logs contain account IDs only.
 */
@Service
public class EncounterService {

    private static final Logger log = LoggerFactory.getLogger(EncounterService.class);
    private static final String LOGICAL_ID = "[A-Za-z0-9.-]{1,64}";
    private static final DateTimeFormatter STORED = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX");
    /** Clock skew tolerated for a visit that has already taken place. */
    private static final Duration SKEW = Duration.ofMinutes(5);
    /** Upper bound for name lookups per list and resource type. */
    static final int MAX_NAME_LOOKUPS = 25;

    private final UserRepository users;
    private final PatientAccessService patientAccess;
    private final PatientRegistry registry;
    private final FhirPatientClient patients;
    private final FhirPractitionerClient practitioners;
    private final FhirOrganizationClient organizations;
    private final FhirLocationClient locations;
    private final FhirEncounterClient encounters;
    private final PractitionerDirectory practitionerDirectory;
    private final Clock clock;

    @Autowired
    public EncounterService(UserRepository users, PatientAccessService patientAccess, PatientRegistry registry,
            FhirPatientClient patients, FhirPractitionerClient practitioners, FhirOrganizationClient organizations,
            FhirLocationClient locations, FhirEncounterClient encounters, PractitionerDirectory practitionerDirectory) {
        this(users, patientAccess, registry, patients, practitioners, organizations, locations, encounters,
                practitionerDirectory, Clock.systemUTC());
    }

    EncounterService(UserRepository users, PatientAccessService patientAccess, PatientRegistry registry,
            FhirPatientClient patients, FhirPractitionerClient practitioners, FhirOrganizationClient organizations,
            FhirLocationClient locations, FhirEncounterClient encounters, PractitionerDirectory practitionerDirectory,
            Clock clock) {
        this.users = users;
        this.patientAccess = patientAccess;
        this.registry = registry;
        this.patients = patients;
        this.practitioners = practitioners;
        this.organizations = organizations;
        this.locations = locations;
        this.encounters = encounters;
        this.practitionerDirectory = practitionerDirectory;
        this.clock = clock;
    }

    // ---------- create ----------

    /**
     * Creates a visit for a registered Patient (DOCTOR and STAFF). Everything is validated before any FHIR write:
     * the request (400), the Practitioner's link to a MediCore clinician (400), the Patient's registry entry (404)
     * and existence (404), and the existence of Practitioner, Organization and Location (422).
     *
     * @throws EncounterRequestException   with a code (see there)
     * @throws PatientNotFoundException    if the Patient is not known to MediCore or does not exist
     * @throws AccessDeniedException       for other roles
     * @throws IllegalArgumentException    for a malformed Patient ID
     * @throws FhirServerException         for FHIR failures (a failed create may still have been committed)
     */
    public EncounterSummaryDto create(UserDto principal, String patientId, NewEncounterRequest request) {
        requireClinician(principal);
        requireLogicalId(patientId);
        patientAccess.checkPatientAccess(principal, patientId);
        ValidVisit visit = validate(request);
        if (!practitionerDirectory.isSelectable(visit.practitionerId())) {
            throw new EncounterRequestException("PRACTITIONER_NOT_AVAILABLE");
        }
        if (!registry.isKnown(patientId)) {
            log.info("Encounter not created, patient not found: accountId={}", principal.id());
            throw new PatientNotFoundException();
        }
        requirePatientExists(patientId);
        Practitioner practitioner = readOrFail(() -> practitioners.readPractitioner(visit.practitionerId()),
                "PRACTITIONER_NOT_FOUND");
        Organization organization = visit.organizationId() == null ? null
                : readOrFail(() -> organizations.readOrganization(visit.organizationId()), "ORGANIZATION_NOT_FOUND");
        Location location = visit.locationId() == null ? null
                : readOrFail(() -> locations.readLocation(visit.locationId()), "LOCATION_NOT_FOUND");

        CreateEncounterRequest fhirRequest = new CreateEncounterRequest(patientId, visit.status(),
                visit.type().encounterClass(),
                List.of(new Concept(visit.type().display(), List.of(new Coding(VisitType.SYSTEM, null,
                        visit.type().code(), visit.type().display(), null)))),
                null, null, visit.start(), null, List.of(),
                List.of(new CreateEncounterRequest.Participant(visit.practitionerId(),
                        List.of(CreateEncounterRequest.ParticipantType.ATND))),
                visit.organizationId(),
                visit.locationId() == null ? List.of()
                        : List.of(new CreateEncounterRequest.EncounterLocation(visit.locationId(), null)));
        String id = encounters.createEncounter(fhirRequest);
        log.info("Encounter created: accountId={}", principal.id());
        return new EncounterSummaryDto(id, visit.start(), null, visit.status().code(), visit.type().display(),
                nameList(PractitionerDirectory.displayName(practitioner)),
                organization == null ? null : blankToNull(organization.getName()),
                location == null ? null : blankToNull(location.getName()));
    }

    // ---------- list ----------

    /** The visits of a Patient known to MediCore, newest first (DOCTOR and STAFF). */
    public List<EncounterSummaryDto> listForPatient(UserDto principal, String patientId) {
        requireClinician(principal);
        requireLogicalId(patientId);
        patientAccess.checkPatientAccess(principal, patientId);
        if (!registry.isKnown(patientId)) {
            log.info("Encounters not listed, patient not found: accountId={}", principal.id());
            throw new PatientNotFoundException();
        }
        List<EncounterSummaryDto> result = summarize(encounters.findByPatientId(patientId));
        log.info("Encounters listed: accountId={}, count={}", principal.id(), result.size());
        return result;
    }

    /**
     * The signed-in PATIENT's own visits, newest first. The Patient is the account's approved link only.
     *
     * @throws EncounterRequestException RECORD_NOT_LINKED without an approved link
     */
    public List<EncounterSummaryDto> listOwn(UserDto principal) {
        User account = account(principal);
        if (account.getRole() != Role.PATIENT) throw denied();
        String patientId = account.getFhirPatientId();
        if (patientId == null) throw new EncounterRequestException("RECORD_NOT_LINKED");
        patientAccess.checkPatientAccess(principal, patientId);
        List<EncounterSummaryDto> result = summarize(encounters.findByPatientId(patientId));
        log.info("Own encounters listed: accountId={}, count={}", principal.id(), result.size());
        return result;
    }

    // ---------- validation ----------

    record ValidVisit(String start, String practitionerId, VisitType type, CreateEncounterRequest.Status status,
            String organizationId, String locationId) {
    }

    private ValidVisit validate(NewEncounterRequest request) {
        if (request == null) throw new EncounterRequestException("INVALID_REQUEST");
        CreateEncounterRequest.Status status = status(request.status());
        String start = startTime(request.startTime(), status);
        if (request.practitionerId() == null) throw new EncounterRequestException("PRACTITIONER_REQUIRED");
        if (!request.practitionerId().matches(LOGICAL_ID)) throw new EncounterRequestException("PRACTITIONER_NOT_AVAILABLE");
        VisitType type = type(request.type());
        if (request.organizationId() != null && !request.organizationId().matches(LOGICAL_ID)) {
            throw new EncounterRequestException("ORGANIZATION_INVALID");
        }
        if (request.locationId() != null && !request.locationId().matches(LOGICAL_ID)) {
            throw new EncounterRequestException("LOCATION_INVALID");
        }
        return new ValidVisit(start, request.practitionerId(), type, status, request.organizationId(),
                request.locationId());
    }

    /** FINISHED (default), IN_PROGRESS or PLANNED; other R4 statuses do not describe a newly documented visit. */
    private static CreateEncounterRequest.Status status(String value) {
        if (value == null) return CreateEncounterRequest.Status.FINISHED;
        return switch (value) {
            case "FINISHED" -> CreateEncounterRequest.Status.FINISHED;
            case "IN_PROGRESS" -> CreateEncounterRequest.Status.IN_PROGRESS;
            case "PLANNED" -> CreateEncounterRequest.Status.PLANNED;
            default -> throw new EncounterRequestException("STATUS_INVALID");
        };
    }

    private static VisitType type(String value) {
        if (value == null) throw new EncounterRequestException("TYPE_INVALID");
        for (VisitType type : VisitType.values()) {
            if (type.name().equals(value)) return type;
        }
        throw new EncounterRequestException("TYPE_INVALID");
    }

    /**
     * An ISO 8601 date-time with an offset (never guessed: a value without offset is rejected), stored with second
     * precision and the same offset. A finished or ongoing visit cannot start in the future.
     */
    private String startTime(String value, CreateEncounterRequest.Status status) {
        if (value == null) throw new EncounterRequestException("START_TIME_INVALID");
        OffsetDateTime start;
        try {
            start = OffsetDateTime.parse(value);
        } catch (DateTimeParseException invalid) {
            throw new EncounterRequestException("START_TIME_INVALID");
        }
        if (start.getYear() < 1900 || start.getYear() > 9999
                || Math.abs(start.getOffset().getTotalSeconds()) > 14 * 3600) {
            throw new EncounterRequestException("START_TIME_INVALID");
        }
        if (status != CreateEncounterRequest.Status.PLANNED
                && start.toInstant().isAfter(Instant.now(clock).plus(SKEW))) {
            throw new EncounterRequestException("START_TIME_IN_FUTURE");
        }
        return start.truncatedTo(ChronoUnit.SECONDS).format(STORED);
    }

    // ---------- summaries ----------

    private static final Pattern PRACTITIONER = Pattern.compile("Practitioner/(" + LOGICAL_ID + ")");
    private static final Pattern ORGANIZATION = Pattern.compile("Organization/(" + LOGICAL_ID + ")");
    private static final Pattern LOCATION = Pattern.compile("Location/(" + LOGICAL_ID + ")");

    /** Readable summaries, newest first; names of referenced resources are read once each (bounded). */
    private List<EncounterSummaryDto> summarize(List<EncounterDto> found) {
        Names practitionerNames = new Names(id -> PractitionerDirectory.displayName(practitioners.readPractitioner(id)));
        Names organizationNames = new Names(id -> blankToNull(organizations.readOrganization(id).getName()));
        Names locationNames = new Names(id -> blankToNull(locations.readLocation(id).getName()));
        List<EncounterSummaryDto> result = new ArrayList<>();
        for (EncounterDto encounter : found) {
            List<String> participantNames = encounter.participants().stream()
                    .map(participant -> practitionerNames.of(idOf(participant.individual(), PRACTITIONER)))
                    .filter(Objects::nonNull).distinct().toList();
            String organization = organizationNames.of(idOf(encounter.serviceProvider(), ORGANIZATION));
            String location = encounter.locations().isEmpty() ? null
                    : locationNames.of(idOf(encounter.locations().getFirst().location(), LOCATION));
            result.add(new EncounterSummaryDto(encounter.id(),
                    encounter.period() == null ? null : encounter.period().start(),
                    encounter.period() == null ? null : encounter.period().end(),
                    encounter.status(), typeText(encounter.types()), participantNames, organization, location));
        }
        result.sort(Comparator.comparing((EncounterSummaryDto summary) -> sortKey(summary.start()),
                Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(EncounterSummaryDto::id));
        return List.copyOf(result);
    }

    /** Name lookups with a per-list cache and bound; a resource that cannot be read simply has no name. */
    private static final class Names {
        private final Function<String, String> reader;
        private final Map<String, String> cache = new HashMap<>();

        Names(Function<String, String> reader) {
            this.reader = reader;
        }

        String of(String id) {
            if (id == null) return null;
            if (cache.containsKey(id)) return cache.get(id);
            if (cache.size() >= MAX_NAME_LOOKUPS) return null;
            String name;
            try {
                name = reader.apply(id);
            } catch (FhirServerException | IllegalArgumentException unreadable) {
                name = null;
            }
            cache.put(id, name);
            return name;
        }
    }

    /** The logical ID of a relative reference of the expected type, or null (absolute or other references). */
    private static String idOf(SubjectReference reference, Pattern pattern) {
        if (reference == null || reference.reference() == null) return null;
        Matcher matcher = pattern.matcher(reference.reference());
        return matcher.matches() ? matcher.group(1) : null;
    }

    static String typeText(List<Concept> types) {
        for (Concept type : types) {
            if (type.text() != null && !type.text().isBlank()) return type.text();
            for (Coding coding : type.coding()) {
                if (coding.display() != null && !coding.display().isBlank()) return coding.display();
            }
        }
        return null;
    }

    /** The instant of a FHIR dateTime, partial dates at their start (UTC); null if absent or unreadable. */
    static Instant sortKey(String start) {
        if (start == null) return null;
        try {
            if (start.contains("T")) return OffsetDateTime.parse(start).toInstant();
            if (start.length() == 4) return Year.parse(start).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            if (start.length() == 7) return YearMonth.parse(start).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            return LocalDate.parse(start).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException unreadable) {
            return null;
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
            throw new FhirServerException("patient-read", FhirServerException.Type.INVALID_RESPONSE, null, List.of(),
                    null);
        }
    }

    private static <T> T readOrFail(java.util.function.Supplier<T> read, String missingCode) {
        try {
            T resource = read.get();
            if (resource == null) throw new EncounterRequestException(missingCode);
            return resource;
        } catch (FhirServerException failure) {
            if (isMissing(failure)) throw new EncounterRequestException(missingCode);
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
        return users.findById(principal.id()).orElseThrow(EncounterService::denied);
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

    private static List<String> nameList(String name) {
        return name == null ? List.of() : List.of(name);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Access denied");
    }
}
