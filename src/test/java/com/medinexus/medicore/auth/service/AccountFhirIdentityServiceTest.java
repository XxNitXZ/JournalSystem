package com.medinexus.medicore.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;

import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.fhir.client.FhirPatientClient;
import com.medinexus.medicore.fhir.client.FhirPractitionerClient;
import com.medinexus.medicore.fhir.client.FhirServerException;

class AccountFhirIdentityServiceTest {
    private final UserRepository repository = mock(UserRepository.class);
    private final FhirPatientClient patients = mock(FhirPatientClient.class);
    private final FhirPractitionerClient practitioners = mock(FhirPractitionerClient.class);
    private final AccountFhirIdentityService service = new AccountFhirIdentityService(repository, patients, practitioners);

    @ParameterizedTest
    @EnumSource(Role.class)
    void assignsUsingOnlyPersistedRole(Role role) {
        prepare(role);
        if (role == Role.PATIENT) when(repository.assignPatientIfUnlinked(1L, "synthetic-1")).thenReturn(1);
        else when(repository.assignPractitionerIfUnlinked(1L, role, "synthetic-1")).thenReturn(1);
        service.assign(1L, "synthetic-1");
        verify(repository).findById(1L);
        if (role == Role.PATIENT) {
            verify(patients).readPatient("synthetic-1");
            verify(repository).assignPatientIfUnlinked(1L, "synthetic-1");
            verifyNoInteractions(practitioners);
        } else {
            verify(practitioners).readPractitioner("synthetic-1");
            verify(repository).assignPractitionerIfUnlinked(1L, role, "synthetic-1");
            verifyNoInteractions(patients);
        }
        verifyNoMoreInteractions(repository);
    }

    @Test
    void rejectsInvalidInputsWithoutDatabaseOrFhirCalls() {
        for (Long accountId : new Long[] {null, 0L, -1L}) {
            assertThatThrownBy(() -> service.assign(accountId, "synthetic-1")).isInstanceOf(IllegalArgumentException.class);
        }
        for (String id : new String[] {null, "", " ", "Patient/1", "https://example.test/1", "id_name", "x".repeat(65)}) {
            assertThatThrownBy(() -> service.assign(1L, id)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(repository, patients, practitioners);
    }

    @Test
    void rejectsMissingAccount() {
        when(repository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.assign(1L, "synthetic-1")).isInstanceOf(AccountFhirIdentityException.class);
        verifyNoInteractions(patients, practitioners);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void identicalExistingAssociationIsIdempotentAndStillVerified(Role role) {
        User account = prepare(role);
        link(account, role, "synthetic-1");
        service.assign(1L, "synthetic-1");
        verify(repository, times(2)).findById(1L);
        if (role == Role.PATIENT) verify(patients).readPatient("synthetic-1");
        else verify(practitioners).readPractitioner("synthetic-1");
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void rejectsReassignmentWithoutFhirCall(Role role) {
        User account = account(role);
        link(account, role, "existing");
        when(repository.findById(1L)).thenReturn(Optional.of(account));
        assertThatThrownBy(() -> service.assign(1L, "synthetic-1")).isInstanceOf(AccountFhirIdentityException.class);
        verifyNoInteractions(patients, practitioners);
    }

    @ParameterizedTest
    @EnumSource(FhirServerException.Type.class)
    void externalFailuresPropagateUnchangedWithoutUpdates(FhirServerException.Type type) {
        prepare(Role.PATIENT);
        FhirServerException failure = new FhirServerException("patient-read", type,
                type == FhirServerException.Type.CLIENT_ERROR ? 404 : null, List.of(), "SyntheticCause");
        when(patients.readPatient("synthetic-1")).thenThrow(failure);
        assertThatThrownBy(() -> service.assign(1L, "synthetic-1")).isSameAs(failure);
        verify(repository).findById(1L);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void practitioner404PropagatesUnchanged() {
        prepare(Role.STAFF);
        FhirServerException failure = new FhirServerException("practitioner-read",
                FhirServerException.Type.CLIENT_ERROR, 404, List.of(), "SyntheticCause");
        when(practitioners.readPractitioner("synthetic-1")).thenThrow(failure);
        assertThatThrownBy(() -> service.assign(1L, "synthetic-1")).isSameAs(failure);
        verify(repository).findById(1L);
        verifyNoMoreInteractions(repository);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void rejectsReturnedIdMismatch(Role role) {
        prepare(role);
        if (role == Role.PATIENT) {
            Patient resource = new Patient();
            resource.setId("other");
            when(patients.readPatient("synthetic-1")).thenReturn(resource);
        }
        else {
            Practitioner resource = new Practitioner();
            resource.setId("other");
            when(practitioners.readPractitioner("synthetic-1")).thenReturn(resource);
        }
        assertThatThrownBy(() -> service.assign(1L, "synthetic-1")).isInstanceOf(AccountFhirIdentityException.class);
        verify(repository).findById(1L);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void databaseConflictDoesNotExposeIdentifiersOrOriginalCause() {
        prepare(Role.PATIENT);
        when(repository.assignPatientIfUnlinked(1L, "synthetic-1"))
                .thenThrow(new DataIntegrityViolationException("SENSITIVE identifier"));
        assertThatThrownBy(() -> service.assign(1L, "synthetic-1"))
                .isInstanceOf(AccountFhirIdentityException.class)
                .hasMessageNotContaining("SENSITIVE").hasCause(null);
    }

    @Test
    void concurrentIdenticalAssignmentIsIdempotent() {
        prepare(Role.PATIENT);
        User current = account(Role.PATIENT);
        current.setFhirPatientId("synthetic-1");
        when(repository.findById(1L)).thenReturn(Optional.of(account(Role.PATIENT)), Optional.of(current));
        service.assign(1L, "synthetic-1");
    }

    @Test
    void rejectsConcurrentRoleChangeEvenForSamePractitionerId() {
        prepare(Role.DOCTOR);
        User current = account(Role.STAFF);
        current.setFhirPractitionerId("synthetic-1");
        when(repository.findById(1L)).thenReturn(Optional.of(account(Role.DOCTOR)), Optional.of(current));
        assertThatThrownBy(() -> service.assign(1L, "synthetic-1")).isInstanceOf(AccountFhirIdentityException.class);
    }

    @Test
    void rejectsConcurrentDifferentAssociationAndDeletion() {
        prepare(Role.PATIENT);
        User current = account(Role.PATIENT);
        current.setFhirPatientId("other");
        when(repository.findById(1L)).thenReturn(Optional.of(account(Role.PATIENT)), Optional.of(current));
        assertThatThrownBy(() -> service.assign(1L, "synthetic-1")).isInstanceOf(AccountFhirIdentityException.class);
        when(repository.findById(1L)).thenReturn(Optional.of(account(Role.PATIENT)), Optional.empty());
        assertThatThrownBy(() -> service.assign(1L, "synthetic-1")).isInstanceOf(AccountFhirIdentityException.class);
    }

    private User prepare(Role role) {
        User account = account(role);
        when(repository.findById(1L)).thenReturn(Optional.of(account));
        if (role == Role.PATIENT) {
            Patient resource = new Patient(); resource.setId("synthetic-1");
            when(patients.readPatient("synthetic-1")).thenReturn(resource);
        } else {
            Practitioner resource = new Practitioner(); resource.setId("synthetic-1");
            when(practitioners.readPractitioner("synthetic-1")).thenReturn(resource);
        }
        return account;
    }

    private User account(Role role) {
        User account = new User(); account.setRole(role); return account;
    }

    private void link(User account, Role role, String id) {
        if (role == Role.PATIENT) account.setFhirPatientId(id);
        else account.setFhirPractitionerId(id);
    }
}
