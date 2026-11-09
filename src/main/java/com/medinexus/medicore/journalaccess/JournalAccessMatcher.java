package com.medinexus.medicore.journalaccess;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.fhir.client.FhirPatientClient;
import com.medinexus.medicore.patient.dto.PatientSearchCriteria;
import com.medinexus.medicore.patient.registry.PatientRegistry;

/**
 * Matching of PATIENT accounts and Patients registered by MediCore, by normalized personnummer only (never by
 * name). <strong>The matcher never creates a journal access request</strong>: requests are created only when a
 * patient asks for access ({@link JournalAccessService#requestAccess}). The matcher only
 * <ul>
 *   <li>finds the registered FHIR Patient for an explicit request ({@link #findRegisteredPatient}), and</li>
 *   <li>attaches a registered FHIR Patient to a request that is AWAITING_RECORD, making it PENDING: when a DOCTOR
 *       or STAFF registers a Patient ({@link #onPatientRegistered}, PostgreSQL only) and when the patient's status
 *       is read ({@link #resolveAwaiting}, which retries after a FHIR outage).</li>
 * </ul>
 * Searches are restricted to MediCore's registered Patients ({@code _id}-restricted FHIR search by exact
 * personnummer); other Patients on the shared server are never requested. A Patient that is linked to another
 * account is never attached. Attaching is a conditional update, so retries and concurrent attempts are
 * idempotent. Logs contain request and account IDs only, never personnummer or FHIR IDs.
 */
@Service
public class JournalAccessMatcher {

    private static final Logger log = LoggerFactory.getLogger(JournalAccessMatcher.class);

    /** Outcome of looking for the registered FHIR Patient with an account's personnummer. */
    public enum MatchKind {
        /** Exactly one registered Patient that is not linked to another account. */
        FOUND,
        /** No registered Patient with this personnummer. */
        NONE,
        /** The only matching Patient is already linked to another account. */
        LINKED_ELSEWHERE,
        /** Several registered Patients have this personnummer; nothing is attached automatically. */
        AMBIGUOUS
    }

    public record Match(MatchKind kind, String fhirPatientId) {
        static Match of(MatchKind kind) {
            return new Match(kind, null);
        }
    }

    private final JournalAccessRequestRepository requests;
    private final UserRepository users;
    private final PatientRegistry registry;
    private final FhirPatientClient patients;

    public JournalAccessMatcher(JournalAccessRequestRepository requests, UserRepository users, PatientRegistry registry,
            FhirPatientClient patients) {
        this.requests = requests;
        this.users = users;
        this.registry = registry;
        this.patients = patients;
    }

    /**
     * A Patient with this personnummer was just registered (and recorded in the registry). If the PATIENT account
     * with the same personnummer has a request AWAITING_RECORD, the Patient is attached to it. Without such a
     * request nothing happens. No FHIR request.
     *
     * @return true if an awaiting request became PENDING
     */
    public boolean onPatientRegistered(String fhirPatientId, String personnummer) {
        if (fhirPatientId == null || personnummer == null) return false;
        Optional<User> account = users.findByPersonnummer(personnummer).filter(JournalAccessMatcher::isUnlinkedPatient);
        if (account.isEmpty()) return false;
        Optional<JournalAccessRequest> awaiting = requests.findFirstByUserIdAndStatus(account.get().getId(),
                JournalAccessStatus.AWAITING_RECORD);
        if (awaiting.isEmpty() || users.existsByFhirPatientId(fhirPatientId)) return false;
        return attach(awaiting.get(), fhirPatientId);
    }

    /**
     * Retries matching for the account's request AWAITING_RECORD, if it has one. Without such a request this does
     * nothing and makes no FHIR request. FHIR failures are thrown to the caller; the request then stays awaiting.
     *
     * @return true if the awaiting request became PENDING
     */
    public boolean resolveAwaiting(Long accountId) {
        User account = accountId == null ? null : users.findById(accountId).orElse(null);
        if (account == null || !isUnlinkedPatient(account) || account.getPersonnummer() == null) return false;
        Optional<JournalAccessRequest> awaiting = requests.findFirstByUserIdAndStatus(account.getId(),
                JournalAccessStatus.AWAITING_RECORD);
        if (awaiting.isEmpty()) return false;
        Match match = findRegisteredPatient(account);
        if (match.kind() == MatchKind.AMBIGUOUS) {
            log.warn("Journal access request not matched, several registered patient records: requestId={}",
                    awaiting.get().getId());
        }
        return match.kind() == MatchKind.FOUND && attach(awaiting.get(), match.fhirPatientId());
    }

    /**
     * The registered FHIR Patient with the account's personnummer: an exact search restricted to MediCore's
     * registry. Never creates or changes anything. FHIR failures are thrown to the caller.
     */
    public Match findRegisteredPatient(User account) {
        if (account == null || account.getPersonnummer() == null) return Match.of(MatchKind.NONE);
        List<String> registered = registry.registeredIds();
        if (registered.isEmpty()) return Match.of(MatchKind.NONE);
        List<String> found = patients.searchPatients(PatientSearchCriteria.parse(account.getPersonnummer()), registered)
                .stream().map(patient -> patient.getIdElement().getIdPart()).distinct().toList();
        if (found.isEmpty()) return Match.of(MatchKind.NONE);
        List<String> free = found.stream()
                .filter(id -> users.findByFhirPatientId(id).map(owner -> owner.getId().equals(account.getId())).orElse(true))
                .toList();
        if (free.isEmpty()) return Match.of(MatchKind.LINKED_ELSEWHERE);
        if (free.size() > 1) return Match.of(MatchKind.AMBIGUOUS);
        return new Match(MatchKind.FOUND, free.getFirst());
    }

    private static boolean isUnlinkedPatient(User account) {
        return account.getRole() == Role.PATIENT && account.getFhirPatientId() == null
                && account.getFhirPractitionerId() == null;
    }

    private boolean attach(JournalAccessRequest request, String fhirPatientId) {
        if (requests.attachRecordIfAwaiting(request.getId(), fhirPatientId) != 1) return false;
        log.info("Journal access request matched to a registered patient record: requestId={}, accountId={}",
                request.getId(), request.getUserId());
        return true;
    }
}
