package com.medinexus.medicore.journalaccess;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.hl7.fhir.r4.model.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.fhir.client.FhirPatientClient;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.fhir.mapper.PatientMapper;
import com.medinexus.medicore.journalaccess.dto.JournalAccessRequestDetail;
import com.medinexus.medicore.journalaccess.dto.JournalAccessRequestSummary;
import com.medinexus.medicore.journalaccess.dto.JournalAccessStatusDto;
import com.medinexus.medicore.patient.dto.PatientDto;
import com.medinexus.medicore.patient.validation.Personnummer;
import com.medinexus.medicore.patient.validation.PatientPersonnummer;

/**
 * Journal access requests: created only when a PATIENT asks for access ({@link #requestAccess}), reviewed by
 * DOCTOR accounts, and the journal access status of PATIENT accounts.
 *
 * <p>Roles are always read from PostgreSQL (a stale session role is not enough). Approval verifies the request,
 * the PATIENT account, the FHIR Patient (one remote read, outside any transaction) and the personnummer, and then
 * updates the request and links the account in <strong>one database transaction</strong>: the request is only
 * decided while it is still PENDING, and the account is only linked while it is still an unlinked PATIENT
 * ({@code UserRepository.assignPatientIfUnlinked}, the conditional update also used by
 * {@code AccountFhirIdentityService}); the unique constraint on {@code users.fhir_patient_id} stops a second
 * account. If any step fails, nothing is changed. Logs contain request and account IDs only.
 */
@Service
public class JournalAccessService {

    private static final Logger log = LoggerFactory.getLogger(JournalAccessService.class);

    private final JournalAccessRequestRepository requests;
    private final UserRepository users;
    private final FhirPatientClient patients;
    private final PatientMapper mapper;
    private final JournalAccessMatcher matcher;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public JournalAccessService(JournalAccessRequestRepository requests, UserRepository users, FhirPatientClient patients,
            PatientMapper mapper, JournalAccessMatcher matcher, PlatformTransactionManager transactionManager) {
        this(requests, users, patients, mapper, matcher, new TransactionTemplate(transactionManager), Clock.systemUTC());
    }

    JournalAccessService(JournalAccessRequestRepository requests, UserRepository users, FhirPatientClient patients,
            PatientMapper mapper, JournalAccessMatcher matcher, TransactionTemplate transactions, Clock clock) {
        this.requests = requests;
        this.users = users;
        this.patients = patients;
        this.mapper = mapper;
        this.matcher = matcher;
        this.transactions = transactions;
        this.clock = clock;
    }

    // ---------- PATIENT ----------

    private static final List<JournalAccessStatus> OPEN =
            List.of(JournalAccessStatus.AWAITING_RECORD, JournalAccessStatus.PENDING);

    /**
     * The signed-in PATIENT's journal access: APPROVED (linked), AWAITING_RECORD, PENDING, REJECTED (latest request
     * rejected) or NONE. <strong>Never creates a request.</strong> A request AWAITING_RECORD is matched again first
     * (if the FHIR server is unavailable, it simply stays awaiting until a later call).
     */
    public JournalAccessStatusDto statusFor(UserDto principal) {
        User account = requireAccount(principal, Role.PATIENT);
        if (account.getFhirPatientId() != null) return status(JournalAccessStatus.APPROVED);
        retryAwaiting(account);
        return requests.findFirstByUserIdAndStatusIn(account.getId(), OPEN)
                .map(open -> status(open.getStatus()))
                .orElseGet(() -> requests.findFirstByUserIdOrderByCreatedAtDescIdDesc(account.getId())
                        .filter(latest -> latest.getStatus() == JournalAccessStatus.REJECTED)
                        .map(latest -> status(JournalAccessStatus.REJECTED))
                        .orElse(new JournalAccessStatusDto("NONE")));
    }

    /**
     * The PATIENT asks for access to their journal: the only way a request is created. The account comes from the
     * session; no user ID, personnummer or FHIR ID is accepted from the client. If MediCore has registered a FHIR
     * Patient with the account's personnummer, the request is PENDING (a doctor reviews it); otherwise, or if the
     * FHIR server cannot be searched right now, it is AWAITING_RECORD and is matched later. Repeated or concurrent
     * calls return the existing open request instead of creating another one.
     *
     * @throws JournalAccessConflictException PERSONNUMMER_MISSING, REQUEST_REJECTED (a doctor can reconsider),
     *                                        LINK_CONFLICT (the record belongs to another account) or
     *                                        ACCOUNT_NOT_ELIGIBLE
     */
    public JournalAccessStatusDto requestAccess(UserDto principal) {
        User account = requireAccount(principal, Role.PATIENT);
        if (account.getFhirPatientId() != null) return status(JournalAccessStatus.APPROVED);
        if (account.getFhirPractitionerId() != null) throw conflict("ACCOUNT_NOT_ELIGIBLE");
        Optional<JournalAccessRequest> open = requests.findFirstByUserIdAndStatusIn(account.getId(), OPEN);
        if (open.isPresent()) {
            retryAwaiting(account);
            return status(requests.findFirstByUserIdAndStatusIn(account.getId(), OPEN).orElse(open.get()).getStatus());
        }
        if (account.getPersonnummer() == null) throw conflict("PERSONNUMMER_MISSING");
        boolean rejected = requests.findFirstByUserIdOrderByCreatedAtDescIdDesc(account.getId())
                .filter(latest -> latest.getStatus() == JournalAccessStatus.REJECTED).isPresent();
        if (rejected) throw conflict("REQUEST_REJECTED");

        JournalAccessMatcher.Match match;
        try {
            match = matcher.findRegisteredPatient(account);
        } catch (FhirServerException | DataAccessException failure) {
            // Not a confirmed non-match: the request waits and is matched again later.
            log.warn("Journal access matching deferred: accountId={}, cause={}", account.getId(),
                    failure.getClass().getSimpleName());
            match = new JournalAccessMatcher.Match(JournalAccessMatcher.MatchKind.NONE, null);
        }
        if (match.kind() == JournalAccessMatcher.MatchKind.LINKED_ELSEWHERE) throw conflict("LINK_CONFLICT");
        Instant now = Instant.now(clock);
        JournalAccessRequest request = match.kind() == JournalAccessMatcher.MatchKind.FOUND
                ? new JournalAccessRequest(account.getId(), match.fhirPatientId(), now)
                : JournalAccessRequest.awaitingRecord(account.getId(), now);
        try {
            request = requests.saveAndFlush(request);
        } catch (DataIntegrityViolationException concurrent) {
            // Another call of the same patient created the open request first (unique index): return that one.
            return requests.findFirstByUserIdAndStatusIn(account.getId(), OPEN).map(other -> status(other.getStatus()))
                    .orElseThrow(() -> conflict("REQUEST_CONFLICT"));
        }
        log.info("Journal access requested: requestId={}, accountId={}, status={}", request.getId(), account.getId(),
                request.getStatus());
        return status(request.getStatus());
    }

    private void retryAwaiting(User account) {
        try {
            matcher.resolveAwaiting(account.getId());
        } catch (FhirServerException | DataAccessException failure) {
            log.warn("Journal access matching deferred: accountId={}, cause={}", account.getId(),
                    failure.getClass().getSimpleName());
        }
    }

    private static JournalAccessStatusDto status(JournalAccessStatus status) {
        return new JournalAccessStatusDto(status.name());
    }

    // ---------- DOCTOR ----------

    /** All requests, pending first, with the account's names, personnummer and e-mail. */
    public List<JournalAccessRequestSummary> list(UserDto principal) {
        requireAccount(principal, Role.DOCTOR);
        List<JournalAccessRequest> all = requests.findAllForReview();
        Map<Long, User> accounts = users.findAllById(all.stream().map(JournalAccessRequest::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return all.stream().filter(request -> accounts.containsKey(request.getUserId()))
                .map(request -> summary(request, accounts.get(request.getUserId()))).toList();
    }

    public long pendingCount(UserDto principal) {
        requireAccount(principal, Role.DOCTOR);
        return requests.countByStatus(JournalAccessStatus.PENDING);
    }

    /** One request with the account and, if it can be read, the matching FHIR Patient. */
    public JournalAccessRequestDetail detail(UserDto principal, Long requestId) {
        requireAccount(principal, Role.DOCTOR);
        JournalAccessRequest request = find(requestId);
        User account = users.findById(request.getUserId()).orElseThrow(JournalAccessRequestNotFoundException::new);
        if (request.getFhirPatientId() == null) {
            // AWAITING_RECORD: no registered Patient yet, nothing to read.
            return new JournalAccessRequestDetail(request.getId(), request.getStatus(), request.getCreatedAt(),
                    request.getReviewedAt(), account(account), null,
                    JournalAccessRequestDetail.RecordStatus.NOT_REGISTERED, false);
        }
        JournalAccessRequestDetail.PatientRecord record = null;
        JournalAccessRequestDetail.RecordStatus recordStatus;
        PatientPersonnummer recordPersonnummer = null;
        try {
            Patient patient = patients.readPatient(request.getFhirPatientId());
            if (patient == null || !request.getFhirPatientId().equals(patient.getIdElement().getIdPart())) {
                recordStatus = JournalAccessRequestDetail.RecordStatus.UNAVAILABLE;
            } else {
                recordPersonnummer = PatientPersonnummer.of(patient,
                        LocalDate.now(clock.withZone(ZoneId.of("Europe/Stockholm"))));
                record = record(mapper.toDto(patient), recordPersonnummer);
                recordStatus = JournalAccessRequestDetail.RecordStatus.AVAILABLE;
            }
        } catch (FhirServerException failure) {
            recordStatus = isMissing(failure) ? JournalAccessRequestDetail.RecordStatus.NOT_FOUND
                    : JournalAccessRequestDetail.RecordStatus.UNAVAILABLE;
        }
        // Canonical comparison: YYYYMMDD-XXXX in FHIR is the same personnummer as YYYYMMDDXXXX on the account.
        boolean matches = recordPersonnummer != null && recordPersonnummer.matches(account.getPersonnummer());
        return new JournalAccessRequestDetail(request.getId(), request.getStatus(), request.getCreatedAt(),
                request.getReviewedAt(), account(account), record, recordStatus, matches);
    }

    /**
     * Approves a PENDING request and links the PATIENT account to the FHIR Patient, atomically.
     *
     * @throws JournalAccessConflictException with a code describing why approval is not possible
     * @throws FhirServerException            if the FHIR Patient cannot be verified because of a FHIR failure
     */
    public JournalAccessRequestSummary approve(UserDto principal, Long requestId) {
        User reviewer = requireAccount(principal, Role.DOCTOR);
        JournalAccessRequest request = find(requestId);
        requirePending(request);
        User account = users.findById(request.getUserId()).orElseThrow(() -> conflict("ACCOUNT_NOT_ELIGIBLE"));
        String fhirPatientId = request.getFhirPatientId();
        verifyLinkable(account, fhirPatientId);

        // Remote verification, deliberately outside the database transaction.
        Patient patient;
        try {
            patient = patients.readPatient(fhirPatientId);
        } catch (FhirServerException failure) {
            if (isMissing(failure)) throw conflict("FHIR_PATIENT_MISSING");
            throw failure;
        }
        if (patient == null || !fhirPatientId.equals(patient.getIdElement().getIdPart())) {
            throw conflict("FHIR_PATIENT_MISSING");
        }
        // Canonical comparison (YYYYMMDDXXXX or YYYYMMDD-XXXX in FHIR); conflicting personnummer never match.
        if (account.getPersonnummer() == null || !PatientPersonnummer.of(patient,
                LocalDate.now(clock.withZone(ZoneId.of("Europe/Stockholm")))).matches(account.getPersonnummer())) {
            throw conflict("PERSONNUMMER_MISMATCH");
        }

        Instant now = Instant.now(clock);
        try {
            transactions.executeWithoutResult(status -> {
                if (requests.decideIfPending(requestId, JournalAccessStatus.APPROVED, reviewer.getId(), now) != 1) {
                    throw conflict("REQUEST_NOT_PENDING");
                }
                boolean alreadyLinkedHere = fhirPatientId.equals(account.getFhirPatientId());
                if (!alreadyLinkedHere && users.assignPatientIfUnlinked(account.getId(), fhirPatientId) != 1) {
                    throw conflict("LINK_CONFLICT");
                }
            });
        } catch (DataIntegrityViolationException linkedElsewhere) {
            // users.fhir_patient_id is unique: another account was linked concurrently. Rolled back.
            throw conflict("LINK_CONFLICT");
        }
        log.info("Journal access request approved: requestId={}, accountId={}, reviewerId={}", requestId,
                account.getId(), reviewer.getId());
        return summary(find(requestId), users.findById(account.getId()).orElse(account));
    }

    /** Rejects a PENDING request; the account link is never changed. */
    public JournalAccessRequestSummary reject(UserDto principal, Long requestId) {
        User reviewer = requireAccount(principal, Role.DOCTOR);
        JournalAccessRequest request = find(requestId);
        requirePending(request);
        if (requests.decideIfPending(requestId, JournalAccessStatus.REJECTED, reviewer.getId(),
                        Instant.now(clock)) != 1) {
            throw conflict("REQUEST_NOT_PENDING");
        }
        log.info("Journal access request rejected: requestId={}, reviewerId={}", requestId, reviewer.getId());
        return summary(find(requestId), users.findById(request.getUserId()).orElseThrow(
                JournalAccessRequestNotFoundException::new));
    }

    /**
     * Reconsideration of a REJECTED request: creates a new PENDING request for the same account and Patient. The
     * rejected request stays as history. This is the only way a rejected match becomes pending again.
     */
    public JournalAccessRequestSummary reopen(UserDto principal, Long requestId) {
        requireAccount(principal, Role.DOCTOR);
        JournalAccessRequest rejected = find(requestId);
        if (rejected.getStatus() != JournalAccessStatus.REJECTED) throw conflict("REQUEST_NOT_REJECTED");
        User account = users.findById(rejected.getUserId()).orElseThrow(() -> conflict("ACCOUNT_NOT_ELIGIBLE"));
        verifyLinkable(account, rejected.getFhirPatientId());
        // At most one open request per account (unique index).
        if (requests.findFirstByUserIdAndStatusIn(account.getId(), OPEN).isPresent()) throw conflict("ALREADY_PENDING");
        JournalAccessRequest reopened;
        try {
            reopened = requests.saveAndFlush(new JournalAccessRequest(account.getId(), rejected.getFhirPatientId(),
                    Instant.now(clock)));
        } catch (DataIntegrityViolationException concurrent) {
            throw conflict("ALREADY_PENDING");
        }
        log.info("Journal access request reopened: requestId={}, newRequestId={}", requestId, reopened.getId());
        return summary(reopened, account);
    }

    // ---------- helpers ----------

    /** The account must be an unlinked PATIENT (or already linked to exactly this Patient) and the Patient free. */
    private void verifyLinkable(User account, String fhirPatientId) {
        if (account.getRole() != Role.PATIENT || account.getFhirPractitionerId() != null) {
            throw conflict("ACCOUNT_NOT_ELIGIBLE");
        }
        if (account.getFhirPatientId() != null && !account.getFhirPatientId().equals(fhirPatientId)) {
            throw conflict("LINK_CONFLICT");
        }
        users.findByFhirPatientId(fhirPatientId).filter(other -> !Objects.equals(other.getId(), account.getId()))
                .ifPresent(other -> { throw conflict("LINK_CONFLICT"); });
    }

    /** Only PENDING requests (with a registered FHIR Patient) can be decided; AWAITING_RECORD never. */
    private static void requirePending(JournalAccessRequest request) {
        if (request.getStatus() == JournalAccessStatus.AWAITING_RECORD) throw conflict("AWAITING_RECORD");
        if (request.getStatus() != JournalAccessStatus.PENDING) throw conflict("REQUEST_NOT_PENDING");
    }

    private static JournalAccessRequestDetail.Account account(User account) {
        return new JournalAccessRequestDetail.Account(account.getGivenName(), account.getFamilyName(),
                account.getPersonnummer(), account.getEmail());
    }

    private User requireAccount(UserDto principal, Role role) {
        if (principal == null || principal.id() == null || principal.id() <= 0) throw denied();
        User account = users.findById(principal.id()).orElseThrow(JournalAccessService::denied);
        if (account.getRole() != role) throw denied();
        return account;
    }

    private JournalAccessRequest find(Long requestId) {
        if (requestId == null || requestId <= 0) throw new JournalAccessRequestNotFoundException();
        return requests.findById(requestId).orElseThrow(JournalAccessRequestNotFoundException::new);
    }

    private static JournalAccessRequestSummary summary(JournalAccessRequest request, User account) {
        return new JournalAccessRequestSummary(request.getId(), account.getGivenName(), account.getFamilyName(),
                account.getPersonnummer(), account.getEmail(), request.getStatus(), request.getCreatedAt(),
                request.getReviewedAt());
    }

    /** The record for review; a valid personnummer in its canonical 12-digit form, otherwise as stored. */
    private static JournalAccessRequestDetail.PatientRecord record(PatientDto patient, PatientPersonnummer canonical) {
        var name = patient.names().stream().filter(candidate -> "official".equals(candidate.use())).findFirst()
                .or(() -> patient.names().stream().findFirst());
        String personnummer = canonical.status() == PatientPersonnummer.Status.VALID ? canonical.value()
                : patient.identifiers().stream().filter(identifier -> Personnummer.SYSTEM.equals(identifier.system()))
                        .map(PatientDto.PatientIdentifier::value).findFirst().orElse(null);
        return new JournalAccessRequestDetail.PatientRecord(name.map(PatientDto.PatientName::given).orElse(List.of()),
                name.map(PatientDto.PatientName::family).orElse(null), personnummer, patient.gender(), patient.birthDate());
    }


    private static boolean isMissing(FhirServerException failure) {
        int status = failure.httpStatus().orElse(0);
        return failure.type() == FhirServerException.Type.CLIENT_ERROR && (status == 404 || status == 410);
    }

    private static JournalAccessConflictException conflict(String code) {
        return new JournalAccessConflictException(code);
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Access denied");
    }
}
