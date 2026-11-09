package com.medinexus.medicore.journalaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.fhir.client.FhirPatientClient;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.fhir.mapper.PatientMapper;
import com.medinexus.medicore.journalaccess.dto.JournalAccessRequestDetail;

/** Review rules with mocked repositories, FHIR client and transaction manager. Fictional personnummer only. */
@ExtendWith(OutputCaptureExtension.class)
class JournalAccessServiceTest {

    private static final String PERSONNUMMER = "182003151236";
    private static final String FHIR_ID = "fhir-ali";
    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
    private static final UserDto DOCTOR = new UserDto(1L, "doctor@example.test", Role.DOCTOR);
    private static final UserDto PATIENT = new UserDto(7L, "ali@example.test", Role.PATIENT);

    private final JournalAccessRequestRepository requests = mock(JournalAccessRequestRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final FhirPatientClient patients = mock(FhirPatientClient.class);
    private final JournalAccessMatcher matcher = mock(JournalAccessMatcher.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final JournalAccessService service = new JournalAccessService(requests, users, patients, new PatientMapper(),
            matcher, new TransactionTemplate(transactionManager), Clock.fixed(NOW, ZoneOffset.UTC));

    private static final List<JournalAccessStatus> OPEN =
            List.of(JournalAccessStatus.AWAITING_RECORD, JournalAccessStatus.PENDING);

    private User patientAccount;

    @BeforeEach
    void accounts() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(users.findById(1L)).thenReturn(Optional.of(user(1L, Role.DOCTOR, null)));
        patientAccount = user(7L, Role.PATIENT, PERSONNUMMER);
        patientAccount.setGivenName("Ali");
        patientAccount.setFamilyName("Khan");
        patientAccount.setEmail("ali@example.test");
        when(users.findById(7L)).thenReturn(Optional.of(patientAccount));
    }

    private static User user(long id, Role role, String personnummer) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setRole(role);
        user.setPersonnummer(personnummer);
        return user;
    }

    private JournalAccessRequest request(long id, JournalAccessStatus status) {
        JournalAccessRequest request = new JournalAccessRequest(7L, FHIR_ID, NOW.minusSeconds(60));
        ReflectionTestUtils.setField(request, "id", id);
        ReflectionTestUtils.setField(request, "status", status);
        when(requests.findById(id)).thenReturn(Optional.of(request));
        return request;
    }

    private static Patient fhirPatient(String id, String personnummer) {
        Patient patient = new Patient();
        patient.setId(id);
        patient.addIdentifier().setSystem("http://electronichealth.se/identifier/personnummer").setValue(personnummer);
        patient.addName().setUse(HumanName.NameUse.OFFICIAL).setFamily("Khan").addGiven("Ali");
        patient.setGender(AdministrativeGender.MALE);
        patient.getBirthDateElement().setValueAsString("1820-03-15");
        return patient;
    }

    private static FhirServerException fhir(FhirServerException.Type type, Integer status) {
        return new FhirServerException("patient-read", type, status, List.of(), null);
    }

    // ---------- PATIENT status ----------

    @Test
    void aLinkedPatientHasAccessWithoutAnyMatching() {
        patientAccount.setFhirPatientId(FHIR_ID);
        assertThat(service.statusFor(PATIENT).status()).isEqualTo("APPROVED");
        verifyNoInteractions(matcher);
    }

    @Test
    void theStatusFollowsTheStoredRequestsAndReadingItNeverCreatesOne(CapturedOutput output) {
        assertThat(service.statusFor(PATIENT).status()).isEqualTo("NONE");
        verify(matcher).resolveAwaiting(7L);

        when(requests.findFirstByUserIdAndStatusIn(7L, OPEN)).thenReturn(Optional.of(
                JournalAccessRequest.awaitingRecord(7L, NOW)));
        assertThat(service.statusFor(PATIENT).status()).isEqualTo("AWAITING_RECORD");
        when(requests.findFirstByUserIdAndStatusIn(7L, OPEN)).thenReturn(Optional.of(
                new JournalAccessRequest(7L, FHIR_ID, NOW)));
        assertThat(service.statusFor(PATIENT).status()).isEqualTo("PENDING");

        when(requests.findFirstByUserIdAndStatusIn(7L, OPEN)).thenReturn(Optional.empty());
        JournalAccessRequest rejected = new JournalAccessRequest(7L, FHIR_ID, NOW);
        ReflectionTestUtils.setField(rejected, "status", JournalAccessStatus.REJECTED);
        when(requests.findFirstByUserIdOrderByCreatedAtDescIdDesc(7L)).thenReturn(Optional.of(rejected));
        assertThat(service.statusFor(PATIENT).status()).isEqualTo("REJECTED");

        // FHIR down: retrying an awaiting request is deferred, the stored status is still returned.
        when(matcher.resolveAwaiting(7L)).thenThrow(new FhirServerException("patient-search",
                FhirServerException.Type.UNAVAILABLE, null, List.of(), "ConnectException"));
        assertThat(service.statusFor(PATIENT).status()).isEqualTo("REJECTED");
        assertThat(output.getAll()).contains("Journal access matching deferred: accountId=7").doesNotContain(PERSONNUMMER);
        verify(requests, never()).saveAndFlush(any());
        verify(requests, never()).save(any());
    }

    // ---------- PATIENT asks for access ----------

    private static final JournalAccessMatcher.Match NO_MATCH =
            new JournalAccessMatcher.Match(JournalAccessMatcher.MatchKind.NONE, null);

    private void savedWithId(long id) {
        when(requests.saveAndFlush(any())).thenAnswer(invocation -> {
            JournalAccessRequest saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", id);
            return saved;
        });
    }

    @Test
    void anExplicitRequestForARegisteredRecordIsPendingForTheSessionAccount(CapturedOutput output) {
        when(matcher.findRegisteredPatient(patientAccount))
                .thenReturn(new JournalAccessMatcher.Match(JournalAccessMatcher.MatchKind.FOUND, FHIR_ID));
        savedWithId(40L);

        assertThat(service.requestAccess(PATIENT).status()).isEqualTo("PENDING");

        ArgumentCaptor<JournalAccessRequest> saved = ArgumentCaptor.forClass(JournalAccessRequest.class);
        verify(requests).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(7L);
        assertThat(saved.getValue().getFhirPatientId()).isEqualTo(FHIR_ID);
        assertThat(saved.getValue().getStatus()).isEqualTo(JournalAccessStatus.PENDING);
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(NOW);
        assertThat(output.getAll()).contains("Journal access requested: requestId=40, accountId=7, status=PENDING")
                .doesNotContain(PERSONNUMMER).doesNotContain(FHIR_ID);
    }

    @Test
    void anExplicitRequestWithoutARegisteredRecordAwaitsIt() {
        when(matcher.findRegisteredPatient(patientAccount)).thenReturn(NO_MATCH);
        savedWithId(41L);
        assertThat(service.requestAccess(PATIENT).status()).isEqualTo("AWAITING_RECORD");
        ArgumentCaptor<JournalAccessRequest> saved = ArgumentCaptor.forClass(JournalAccessRequest.class);
        verify(requests).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getFhirPatientId()).isNull();
        assertThat(saved.getValue().getStatus()).isEqualTo(JournalAccessStatus.AWAITING_RECORD);
    }

    @Test
    void aFhirOutageKeepsTheExplicitRequestAsAwaiting(CapturedOutput output) {
        when(matcher.findRegisteredPatient(patientAccount)).thenThrow(new FhirServerException("patient-search",
                FhirServerException.Type.TIMEOUT, null, List.of(), "SocketTimeoutException"));
        savedWithId(42L);
        assertThat(service.requestAccess(PATIENT).status()).isEqualTo("AWAITING_RECORD");
        verify(requests).saveAndFlush(any());
        assertThat(output.getAll()).contains("Journal access matching deferred: accountId=7");
    }

    @Test
    void repeatedAndConcurrentRequestsReturnTheOpenRequestInsteadOfCreatingAnother() {
        // Already open: nothing new, but an awaiting request is matched again.
        when(requests.findFirstByUserIdAndStatusIn(7L, OPEN)).thenReturn(Optional.of(
                JournalAccessRequest.awaitingRecord(7L, NOW)));
        assertThat(service.requestAccess(PATIENT).status()).isEqualTo("AWAITING_RECORD");
        verify(matcher).resolveAwaiting(7L);
        verify(requests, never()).saveAndFlush(any());

        // A concurrent call created it between the check and the insert (unique index): that one is returned.
        when(requests.findFirstByUserIdAndStatusIn(7L, OPEN)).thenReturn(Optional.empty(),
                Optional.of(new JournalAccessRequest(7L, FHIR_ID, NOW)));
        when(matcher.findRegisteredPatient(patientAccount))
                .thenReturn(new JournalAccessMatcher.Match(JournalAccessMatcher.MatchKind.FOUND, FHIR_ID));
        when(requests.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_journal_access_requests_open"));
        assertThat(service.requestAccess(PATIENT).status()).isEqualTo("PENDING");
    }

    @Test
    void requestsAreRefusedWhenTheyCannotOrMustNotBeCreated() {
        // Already linked: access exists, nothing is created.
        patientAccount.setFhirPatientId(FHIR_ID);
        assertThat(service.requestAccess(PATIENT).status()).isEqualTo("APPROVED");
        patientAccount.setFhirPatientId(null);

        // The latest request was rejected: not resubmitted, a doctor can reconsider.
        JournalAccessRequest rejected = new JournalAccessRequest(7L, FHIR_ID, NOW);
        ReflectionTestUtils.setField(rejected, "status", JournalAccessStatus.REJECTED);
        when(requests.findFirstByUserIdOrderByCreatedAtDescIdDesc(7L)).thenReturn(Optional.of(rejected));
        assertConflict(() -> service.requestAccess(PATIENT), "REQUEST_REJECTED");
        when(requests.findFirstByUserIdOrderByCreatedAtDescIdDesc(7L)).thenReturn(Optional.empty());

        // The only matching record belongs to another account.
        when(matcher.findRegisteredPatient(patientAccount))
                .thenReturn(new JournalAccessMatcher.Match(JournalAccessMatcher.MatchKind.LINKED_ELSEWHERE, null));
        assertConflict(() -> service.requestAccess(PATIENT), "LINK_CONFLICT");

        // An account created before personnummer were stored.
        patientAccount.setPersonnummer(null);
        assertConflict(() -> service.requestAccess(PATIENT), "PERSONNUMMER_MISSING");

        verify(requests, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void onlyPatientsCanAskForAccess(Role role) {
        when(users.findById(3L)).thenReturn(Optional.of(user(3L, role, PERSONNUMMER)));
        assertThatThrownBy(() -> service.requestAccess(new UserDto(3L, "x@example.test", Role.PATIENT)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.requestAccess(null)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(matcher);
        verify(requests, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void onlyPatientsHaveAJournalAccessStatus(Role role) {
        when(users.findById(3L)).thenReturn(Optional.of(user(3L, role, null)));
        assertThatThrownBy(() -> service.statusFor(new UserDto(3L, "x@example.test", Role.PATIENT)))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(matcher);
    }

    // ---------- DOCTOR review ----------

    @Test
    void onlyDoctorsCanReviewAccordingToTheDatabaseRole() {
        when(users.findById(3L)).thenReturn(Optional.of(user(3L, Role.STAFF, null)));
        UserDto staff = new UserDto(3L, "s@example.test", Role.DOCTOR); // stale session role
        assertThatThrownBy(() -> service.list(staff)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.pendingCount(staff)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.detail(staff, 5L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.approve(staff, 5L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.reject(staff, 5L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.reopen(staff, 5L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.approve(PATIENT, 5L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.list(null)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(requests, patients, transactionManager);
    }

    @Test
    void theListShowsTheAccountDataAndTheCountOnlyPendingRequests() {
        JournalAccessRequest pending = request(5L, JournalAccessStatus.PENDING);
        when(requests.findAllForReview()).thenReturn(List.of(pending));
        when(users.findAllById(List.of(7L))).thenReturn(List.of(patientAccount));
        when(requests.countByStatus(JournalAccessStatus.PENDING)).thenReturn(3L);

        var list = service.list(DOCTOR);
        assertThat(list).singleElement().satisfies(row -> {
            assertThat(row.givenName()).isEqualTo("Ali");
            assertThat(row.familyName()).isEqualTo("Khan");
            assertThat(row.personnummer()).isEqualTo(PERSONNUMMER);
            assertThat(row.email()).isEqualTo("ali@example.test");
            assertThat(row.status()).isEqualTo(JournalAccessStatus.PENDING);
        });
        assertThat(list.getFirst().toString()).doesNotContain(FHIR_ID);
        assertThat(service.pendingCount(DOCTOR)).isEqualTo(3L);
    }

    @Test
    void theDetailShowsTheAccountNextToTheFhirRecord() {
        request(5L, JournalAccessStatus.PENDING);
        when(patients.readPatient(FHIR_ID)).thenReturn(fhirPatient(FHIR_ID, PERSONNUMMER));
        JournalAccessRequestDetail detail = service.detail(DOCTOR, 5L);
        assertThat(detail.account()).isEqualTo(new JournalAccessRequestDetail.Account("Ali", "Khan", PERSONNUMMER,
                "ali@example.test"));
        assertThat(detail.record()).isEqualTo(new JournalAccessRequestDetail.PatientRecord(List.of("Ali"), "Khan",
                PERSONNUMMER, "male", "1820-03-15"));
        assertThat(detail.recordStatus()).isEqualTo(JournalAccessRequestDetail.RecordStatus.AVAILABLE);
        assertThat(detail.personnummerMatches()).isTrue();
        assertThat(detail.toString()).doesNotContain(FHIR_ID);

        when(patients.readPatient(FHIR_ID)).thenThrow(fhir(FhirServerException.Type.CLIENT_ERROR, 404));
        assertThat(service.detail(DOCTOR, 5L).recordStatus()).isEqualTo(JournalAccessRequestDetail.RecordStatus.NOT_FOUND);
        org.mockito.Mockito.reset(patients);
        when(patients.readPatient(FHIR_ID)).thenThrow(fhir(FhirServerException.Type.UNAVAILABLE, null));
        JournalAccessRequestDetail unavailable = service.detail(DOCTOR, 5L);
        assertThat(unavailable.recordStatus()).isEqualTo(JournalAccessRequestDetail.RecordStatus.UNAVAILABLE);
        assertThat(unavailable.record()).isNull();
        assertThat(unavailable.personnummerMatches()).isFalse();
        assertThatThrownBy(() -> service.detail(DOCTOR, 404L)).isInstanceOf(JournalAccessRequestNotFoundException.class);
    }

    @Test
    void approvalLinksTheAccountAndDecidesTheRequestInOneTransaction(CapturedOutput output) {
        request(5L, JournalAccessStatus.PENDING);
        when(patients.readPatient(FHIR_ID)).thenReturn(fhirPatient(FHIR_ID, PERSONNUMMER));
        when(requests.decideIfPending(5L, JournalAccessStatus.APPROVED, 1L, NOW)).thenReturn(1);
        when(users.assignPatientIfUnlinked(7L, FHIR_ID)).thenReturn(1);

        service.approve(DOCTOR, 5L);

        var order = inOrder(patients, transactionManager, requests, users);
        order.verify(patients).readPatient(FHIR_ID);           // remote read before the transaction
        order.verify(transactionManager).getTransaction(any());
        order.verify(requests).decideIfPending(5L, JournalAccessStatus.APPROVED, 1L, NOW);
        order.verify(users).assignPatientIfUnlinked(7L, FHIR_ID);
        order.verify(transactionManager).commit(any());
        verify(transactionManager, never()).rollback(any());
        assertThat(output.getAll()).contains("Journal access request approved: requestId=5, accountId=7, reviewerId=1")
                .doesNotContain(PERSONNUMMER).doesNotContain(FHIR_ID);
    }

    @Test
    void approvalChecksTheRequestTheAccountAndTheLinksBeforeReadingFhir() {
        request(5L, JournalAccessStatus.APPROVED);
        assertConflict(() -> service.approve(DOCTOR, 5L), "REQUEST_NOT_PENDING");
        request(6L, JournalAccessStatus.REJECTED);
        assertConflict(() -> service.approve(DOCTOR, 6L), "REQUEST_NOT_PENDING");

        request(5L, JournalAccessStatus.PENDING);
        patientAccount.setRole(Role.STAFF);
        assertConflict(() -> service.approve(DOCTOR, 5L), "ACCOUNT_NOT_ELIGIBLE");
        patientAccount.setRole(Role.PATIENT);

        patientAccount.setFhirPatientId("fhir-other");
        assertConflict(() -> service.approve(DOCTOR, 5L), "LINK_CONFLICT");
        patientAccount.setFhirPatientId(null);

        when(users.findByFhirPatientId(FHIR_ID)).thenReturn(Optional.of(user(8L, Role.PATIENT, "182511044568")));
        assertConflict(() -> service.approve(DOCTOR, 5L), "LINK_CONFLICT");

        verifyNoInteractions(patients, transactionManager);
        verify(requests, never()).decideIfPending(anyLong(), any(), anyLong(), any());
    }

    @Test
    void approvalVerifiesTheFhirPatientAndThePersonnummer() {
        request(5L, JournalAccessStatus.PENDING);
        when(patients.readPatient(FHIR_ID)).thenThrow(fhir(FhirServerException.Type.CLIENT_ERROR, 410));
        assertConflict(() -> service.approve(DOCTOR, 5L), "FHIR_PATIENT_MISSING");

        org.mockito.Mockito.reset(patients);
        FhirServerException down = fhir(FhirServerException.Type.TIMEOUT, null);
        when(patients.readPatient(FHIR_ID)).thenThrow(down);
        assertThatThrownBy(() -> service.approve(DOCTOR, 5L)).isSameAs(down);

        org.mockito.Mockito.reset(patients);
        when(patients.readPatient(FHIR_ID)).thenReturn(fhirPatient(FHIR_ID, "182511044568"));
        assertConflict(() -> service.approve(DOCTOR, 5L), "PERSONNUMMER_MISMATCH");

        org.mockito.Mockito.reset(patients);
        when(patients.readPatient(FHIR_ID)).thenReturn(fhirPatient("another-id", PERSONNUMMER));
        assertConflict(() -> service.approve(DOCTOR, 5L), "FHIR_PATIENT_MISSING");

        // An account created before personnummer were stored can never match.
        org.mockito.Mockito.reset(patients);
        when(patients.readPatient(FHIR_ID)).thenReturn(fhirPatient(FHIR_ID, PERSONNUMMER));
        patientAccount.setPersonnummer(null);
        assertConflict(() -> service.approve(DOCTOR, 5L), "PERSONNUMMER_MISMATCH");

        verifyNoInteractions(transactionManager);
        verify(users, never()).assignPatientIfUnlinked(anyLong(), anyString());
    }

    @Test
    void aConcurrentDecisionOrLinkRollsBackTheWholeApproval() {
        request(5L, JournalAccessStatus.PENDING);
        when(patients.readPatient(FHIR_ID)).thenReturn(fhirPatient(FHIR_ID, PERSONNUMMER));

        // Another doctor decided first: nothing is linked.
        when(requests.decideIfPending(eq(5L), any(), anyLong(), any())).thenReturn(0);
        assertConflict(() -> service.approve(DOCTOR, 5L), "REQUEST_NOT_PENDING");
        verify(users, never()).assignPatientIfUnlinked(anyLong(), anyString());

        // The request was decided, but the account was linked elsewhere meanwhile: the decision is rolled back.
        when(requests.decideIfPending(eq(5L), any(), anyLong(), any())).thenReturn(1);
        when(users.assignPatientIfUnlinked(7L, FHIR_ID)).thenReturn(0);
        assertConflict(() -> service.approve(DOCTOR, 5L), "LINK_CONFLICT");

        // Another account got this FHIR Patient concurrently (unique constraint): also rolled back.
        when(users.assignPatientIfUnlinked(7L, FHIR_ID)).thenThrow(new DataIntegrityViolationException("uq"));
        assertConflict(() -> service.approve(DOCTOR, 5L), "LINK_CONFLICT");

        verify(transactionManager, org.mockito.Mockito.times(3)).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void rejectionRecordsTheReviewerAndNeverLinks() {
        request(5L, JournalAccessStatus.PENDING);
        when(requests.decideIfPending(5L, JournalAccessStatus.REJECTED, 1L, NOW)).thenReturn(1);
        service.reject(DOCTOR, 5L);
        verify(requests).decideIfPending(5L, JournalAccessStatus.REJECTED, 1L, NOW);
        verify(users, never()).assignPatientIfUnlinked(anyLong(), anyString());
        verifyNoInteractions(patients);

        request(6L, JournalAccessStatus.APPROVED);
        assertConflict(() -> service.reject(DOCTOR, 6L), "REQUEST_NOT_PENDING");
        request(7L, JournalAccessStatus.PENDING);
        when(requests.decideIfPending(eq(7L), any(), anyLong(), any())).thenReturn(0);
        assertConflict(() -> service.reject(DOCTOR, 7L), "REQUEST_NOT_PENDING");
    }

    @Test
    void aRejectedRequestCanBeReopenedAsANewPendingRequest() {
        request(5L, JournalAccessStatus.REJECTED);
        when(requests.saveAndFlush(any())).thenAnswer(invocation -> {
            JournalAccessRequest saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 9L);
            return saved;
        });
        var reopened = service.reopen(DOCTOR, 5L);
        assertThat(reopened.id()).isEqualTo(9L);
        assertThat(reopened.status()).isEqualTo(JournalAccessStatus.PENDING);

        request(6L, JournalAccessStatus.PENDING);
        assertConflict(() -> service.reopen(DOCTOR, 6L), "REQUEST_NOT_REJECTED");

        request(5L, JournalAccessStatus.REJECTED);
        when(requests.findFirstByUserIdAndStatusIn(7L, OPEN)).thenReturn(Optional.of(new JournalAccessRequest(7L, FHIR_ID, NOW)));
        assertConflict(() -> service.reopen(DOCTOR, 5L), "ALREADY_PENDING");

        when(requests.findFirstByUserIdAndStatusIn(7L, OPEN)).thenReturn(Optional.empty());
        patientAccount.setFhirPatientId("fhir-other");
        assertConflict(() -> service.reopen(DOCTOR, 5L), "LINK_CONFLICT");
    }

    @Test
    void aRequestAwaitingItsRecordCanNeitherBeApprovedNorRejectedAndHasNoRecordToShow() {
        JournalAccessRequest awaiting = JournalAccessRequest.awaitingRecord(7L, NOW);
        ReflectionTestUtils.setField(awaiting, "id", 8L);
        when(requests.findById(8L)).thenReturn(Optional.of(awaiting));

        assertConflict(() -> service.approve(DOCTOR, 8L), "AWAITING_RECORD");
        assertConflict(() -> service.reject(DOCTOR, 8L), "AWAITING_RECORD");
        JournalAccessRequestDetail detail = service.detail(DOCTOR, 8L);
        assertThat(detail.status()).isEqualTo(JournalAccessStatus.AWAITING_RECORD);
        assertThat(detail.recordStatus()).isEqualTo(JournalAccessRequestDetail.RecordStatus.NOT_REGISTERED);
        assertThat(detail.record()).isNull();
        assertThat(detail.personnummerMatches()).isFalse();
        assertThat(detail.account().personnummer()).isEqualTo(PERSONNUMMER);

        verifyNoInteractions(patients, transactionManager);
        verify(requests, never()).decideIfPending(anyLong(), any(), anyLong(), any());
        verify(users, never()).assignPatientIfUnlinked(anyLong(), anyString());
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(JournalAccessConflictException.class,
                conflict -> assertThat(conflict.code()).isEqualTo(code));
    }
}
