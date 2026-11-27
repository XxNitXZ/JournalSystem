package com.medinexus.medicore.journalaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;

import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.clinicalapi.ClinicalClient;
import com.medinexus.medicore.clinicalapi.ClinicalClient.RegisteredRecord;
import com.medinexus.medicore.journalaccess.JournalAccessMatcher.MatchKind;

/**
 * Matching by personnummer with mocked repositories and a mocked Clinical API (C1). The matcher never creates a
 * request; it only finds records and attaches them to requests that patients made. How a FHIR identifier becomes a
 * personnummer verdict (formatting, conflicting identifiers) is Clinical's job and tested there
 * (PatientPersonnummerTest); here Identity acts on the verdict. Fictional personnummer only.
 */
@ExtendWith(OutputCaptureExtension.class)
class JournalAccessMatcherTest {

    private static final String PERSONNUMMER = "182003151236";

    private final JournalAccessRequestRepository requests = mock(JournalAccessRequestRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ClinicalClient clinical = mock(ClinicalClient.class);
    private final JournalAccessMatcher matcher = new JournalAccessMatcher(requests, users, clinical);

    private static User account(long id, Role role, String personnummer) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setRole(role);
        user.setPersonnummer(personnummer);
        return user;
    }

    /** A record as C1 returns it: a valid personnummer equal to the searched one. */
    private static RegisteredRecord record(String id) {
        return new RegisteredRecord(id, "VALID", PERSONNUMMER);
    }

    private JournalAccessRequest awaiting(long requestId, long accountId) {
        JournalAccessRequest request = JournalAccessRequest.awaitingRecord(accountId, Instant.EPOCH);
        ReflectionTestUtils.setField(request, "id", requestId);
        when(requests.findFirstByUserIdAndStatus(accountId, JournalAccessStatus.AWAITING_RECORD))
                .thenReturn(Optional.of(request));
        return request;
    }

    // ---- Clinical's personnummer verdict ----

    @Test
    void aValidMatchingRecordIsFoundAndTheSearchUsesTheAccountsPersonnummer() {
        User account = account(7L, Role.PATIENT, PERSONNUMMER);
        when(clinical.registeredRecords(PERSONNUMMER)).thenReturn(List.of(record("fhir-1")));
        assertThat(matcher.findRegisteredPatient(account))
                .isEqualTo(new JournalAccessMatcher.Match(MatchKind.FOUND, "fhir-1"));
        verify(clinical).registeredRecords(PERSONNUMMER);
    }

    @Test
    void aRecordWhosePersonnummerIsNotClearlyTheAccountsIsNeverMatchedAutomatically() {
        User account = account(7L, Role.PATIENT, PERSONNUMMER);
        when(users.findById(7L)).thenReturn(Optional.of(account));
        awaiting(30L, 7L);
        for (RegisteredRecord unclear : List.of(
                new RegisteredRecord("fhir-1", "AMBIGUOUS", null),
                new RegisteredRecord("fhir-1", "INVALID", null),
                new RegisteredRecord("fhir-1", "MISSING", null),
                new RegisteredRecord("fhir-1", "VALID", "182511044568"))) {
            when(clinical.registeredRecords(PERSONNUMMER)).thenReturn(List.of(unclear));
            assertThat(matcher.findRegisteredPatient(account).kind()).as(unclear.toString())
                    .isEqualTo(MatchKind.AMBIGUOUS);
            assertThat(matcher.resolveAwaiting(7L)).isFalse();
        }
        verify(requests, never()).attachRecordIfAwaiting(anyLong(), anyString());
    }

    // ---- A Patient is registered ----

    @Test
    void aNewPatientIsAttachedToTheWaitingRequestOfTheAccountWithTheSamePersonnummer(CapturedOutput output) {
        when(users.findByPersonnummer(PERSONNUMMER)).thenReturn(Optional.of(account(7L, Role.PATIENT, PERSONNUMMER)));
        awaiting(30L, 7L);
        when(requests.attachRecordIfAwaiting(30L, "fhir-1")).thenReturn(1);

        assertThat(matcher.onPatientRegistered("fhir-1", PERSONNUMMER)).isTrue();

        verify(requests).attachRecordIfAwaiting(30L, "fhir-1");
        verify(requests, never()).saveAndFlush(any());
        verify(requests, never()).save(any());
        verifyNoInteractions(clinical);
        assertThat(output.getAll()).contains("Journal access request matched to a registered patient record: requestId=30")
                .doesNotContain(PERSONNUMMER).doesNotContain("fhir-1");
    }

    @Test
    void aMatchingAccountWithoutARequestGetsNoRequest() {
        // The core rule: a match alone never creates a request.
        when(users.findByPersonnummer(PERSONNUMMER)).thenReturn(Optional.of(account(7L, Role.PATIENT, PERSONNUMMER)));
        when(requests.findFirstByUserIdAndStatus(7L, JournalAccessStatus.AWAITING_RECORD)).thenReturn(Optional.empty());

        assertThat(matcher.onPatientRegistered("fhir-1", PERSONNUMMER)).isFalse();

        verify(requests, never()).saveAndFlush(any());
        verify(requests, never()).save(any());
        verify(requests, never()).attachRecordIfAwaiting(anyLong(), anyString());
    }

    @Test
    void nothingIsAttachedForClinicalOrLinkedAccountsLinkedPatientsOrNoAccount() {
        for (Role role : List.of(Role.DOCTOR, Role.STAFF)) {
            when(users.findByPersonnummer(PERSONNUMMER)).thenReturn(Optional.of(account(8L, role, PERSONNUMMER)));
            awaiting(31L, 8L);
            assertThat(matcher.onPatientRegistered("fhir-1", PERSONNUMMER)).isFalse();
        }
        User linked = account(9L, Role.PATIENT, PERSONNUMMER);
        linked.setFhirPatientId("fhir-0");
        when(users.findByPersonnummer(PERSONNUMMER)).thenReturn(Optional.of(linked));
        awaiting(32L, 9L);
        assertThat(matcher.onPatientRegistered("fhir-1", PERSONNUMMER)).isFalse();

        when(users.findByPersonnummer(PERSONNUMMER)).thenReturn(Optional.of(account(10L, Role.PATIENT, PERSONNUMMER)));
        awaiting(33L, 10L);
        when(users.existsByFhirPatientId("fhir-1")).thenReturn(true);
        assertThat(matcher.onPatientRegistered("fhir-1", PERSONNUMMER)).isFalse();

        when(users.findByPersonnummer("182511044568")).thenReturn(Optional.empty());
        assertThat(matcher.onPatientRegistered("fhir-2", "182511044568")).isFalse();
        assertThat(matcher.onPatientRegistered(null, PERSONNUMMER)).isFalse();
        verify(requests, never()).attachRecordIfAwaiting(anyLong(), anyString());
        verify(requests, never()).saveAndFlush(any());
    }

    @Test
    void anAlreadyAttachedRequestIsNotAttachedTwice() {
        when(users.findByPersonnummer(PERSONNUMMER)).thenReturn(Optional.of(account(7L, Role.PATIENT, PERSONNUMMER)));
        awaiting(30L, 7L);
        when(requests.attachRecordIfAwaiting(30L, "fhir-1")).thenReturn(0); // attached concurrently
        assertThat(matcher.onPatientRegistered("fhir-1", PERSONNUMMER)).isFalse();
    }

    // ---- Retrying an awaiting request ----

    @Test
    void anAwaitingRequestIsResolvedThroughClinicalsRegistrySearch() {
        when(users.findById(7L)).thenReturn(Optional.of(account(7L, Role.PATIENT, PERSONNUMMER)));
        awaiting(30L, 7L);
        when(clinical.registeredRecords(PERSONNUMMER)).thenReturn(List.of(record("fhir-2")));
        when(requests.attachRecordIfAwaiting(30L, "fhir-2")).thenReturn(1);

        assertThat(matcher.resolveAwaiting(7L)).isTrue();

        verify(clinical).registeredRecords(PERSONNUMMER);
        verify(requests, never()).saveAndFlush(any());
    }

    @Test
    void withoutAnAwaitingRequestNothingIsSearchedOrCreated() {
        when(users.findById(7L)).thenReturn(Optional.of(account(7L, Role.PATIENT, PERSONNUMMER)));
        when(requests.findFirstByUserIdAndStatus(7L, JournalAccessStatus.AWAITING_RECORD)).thenReturn(Optional.empty());

        assertThat(matcher.resolveAwaiting(7L)).isFalse();
        verifyNoInteractions(clinical);
        verify(requests, never()).saveAndFlush(any());

        // Clinical, linked, legacy or unknown accounts: nothing either.
        User doctor = account(1L, Role.DOCTOR, PERSONNUMMER);
        User linked = account(2L, Role.PATIENT, PERSONNUMMER);
        linked.setFhirPatientId("fhir-0");
        User legacy = account(3L, Role.PATIENT, null);
        for (User user : List.of(doctor, linked, legacy)) {
            when(users.findById(user.getId())).thenReturn(Optional.of(user));
            awaiting(40L + user.getId(), user.getId());
            assertThat(matcher.resolveAwaiting(user.getId())).isFalse();
        }
        assertThat(matcher.resolveAwaiting(99L)).isFalse();
        assertThat(matcher.resolveAwaiting(null)).isFalse();
        verifyNoInteractions(clinical);
    }

    @Test
    void clinicalFailuresLeaveTheRequestAwaitingForTheCaller() {
        when(users.findById(7L)).thenReturn(Optional.of(account(7L, Role.PATIENT, PERSONNUMMER)));
        awaiting(30L, 7L);
        ResourceAccessException down = new ResourceAccessException("Clinical unreachable");
        when(clinical.registeredRecords(PERSONNUMMER)).thenThrow(down);
        assertThatThrownBy(() -> matcher.resolveAwaiting(7L)).isSameAs(down);
        verify(requests, never()).attachRecordIfAwaiting(anyLong(), anyString());
    }

    // ---- Finding the record for an explicit request ----

    @Test
    void findingTheRecordDistinguishesFoundNoneLinkedElsewhereAndAmbiguous(CapturedOutput output) {
        User account = account(7L, Role.PATIENT, PERSONNUMMER);

        when(clinical.registeredRecords(PERSONNUMMER)).thenReturn(List.of());
        assertThat(matcher.findRegisteredPatient(account).kind()).isEqualTo(MatchKind.NONE);

        when(clinical.registeredRecords(PERSONNUMMER)).thenReturn(List.of(record("fhir-1")));
        assertThat(matcher.findRegisteredPatient(account)).isEqualTo(new JournalAccessMatcher.Match(MatchKind.FOUND,
                "fhir-1"));

        when(users.findByFhirPatientId("fhir-1")).thenReturn(Optional.of(account(8L, Role.PATIENT, "182511044568")));
        assertThat(matcher.findRegisteredPatient(account).kind()).isEqualTo(MatchKind.LINKED_ELSEWHERE);

        when(clinical.registeredRecords(PERSONNUMMER)).thenReturn(List.of(record("fhir-1"), record("fhir-2")));
        // fhir-1 is linked elsewhere, fhir-2 is free: the free one is the match.
        assertThat(matcher.findRegisteredPatient(account).fhirPatientId()).isEqualTo("fhir-2");
        when(users.findByFhirPatientId("fhir-1")).thenReturn(Optional.empty());
        assertThat(matcher.findRegisteredPatient(account).kind()).isEqualTo(MatchKind.AMBIGUOUS);

        // No personnummer on the account: no call to Clinical at all.
        org.mockito.Mockito.reset(clinical);
        assertThat(matcher.findRegisteredPatient(account(3L, Role.PATIENT, null)).kind()).isEqualTo(MatchKind.NONE);
        verifyNoInteractions(clinical);
        verify(requests, never()).saveAndFlush(any());
        assertThat(output.getAll()).doesNotContain(PERSONNUMMER);
    }

    @Test
    void anAmbiguousMatchIsNotAttached() {
        when(users.findById(7L)).thenReturn(Optional.of(account(7L, Role.PATIENT, PERSONNUMMER)));
        awaiting(30L, 7L);
        when(clinical.registeredRecords(PERSONNUMMER)).thenReturn(List.of(record("fhir-1"), record("fhir-2")));
        assertThat(matcher.resolveAwaiting(7L)).isFalse();
        verify(requests, never()).attachRecordIfAwaiting(anyLong(), anyString());
    }
}
