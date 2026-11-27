package com.medinexus.medicore.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.clinicalapi.ClinicalClient;

/** Linking an account to a FHIR Patient or Practitioner, verified through Clinical (C3, C4). */
class AccountFhirIdentityServiceTest {
    private final UserRepository repository = mock(UserRepository.class);
    private final ClinicalClient clinical = mock(ClinicalClient.class);
    private final AccountFhirIdentityService service = new AccountFhirIdentityService(repository, clinical);

    @ParameterizedTest
    @EnumSource(Role.class)
    void assignsUsingOnlyPersistedRole(Role role) {
        prepare(role);
        if (role == Role.PATIENT) when(repository.assignPatientIfUnlinked(1L, "synthetic-1")).thenReturn(1);
        else when(repository.assignPractitionerIfUnlinked(1L, role, "synthetic-1")).thenReturn(1);
        service.assign(1L, "synthetic-1");
        verify(repository).findById(1L);
        if (role == Role.PATIENT) {
            // C3 verifies the Patient and puts it in Clinical's registry (D5).
            verify(clinical).registerPatient("synthetic-1");
            verify(repository).assignPatientIfUnlinked(1L, "synthetic-1");
        } else {
            verify(clinical).practitionerExists("synthetic-1");
            verify(repository).assignPractitionerIfUnlinked(1L, role, "synthetic-1");
        }
        verifyNoMoreInteractions(repository, clinical);
    }

    @Test
    void rejectsInvalidInputsWithoutDatabaseOrClinicalCalls() {
        for (Long accountId : new Long[] {null, 0L, -1L}) {
            assertThatThrownBy(() -> service.assign(accountId, "synthetic-1")).isInstanceOf(IllegalArgumentException.class);
        }
        for (String id : new String[] {null, "", " ", "Patient/1", "https://example.test/1", "id_name", "x".repeat(65)}) {
            assertThatThrownBy(() -> service.assign(1L, id)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(repository, clinical);
    }

    @Test
    void rejectsMissingAccount() {
        when(repository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.assign(1L, "synthetic-1")).isInstanceOf(AccountFhirIdentityException.class);
        verifyNoInteractions(clinical);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void identicalExistingAssociationIsIdempotentAndStillVerified(Role role) {
        User account = prepare(role);
        link(account, role, "synthetic-1");
        service.assign(1L, "synthetic-1");
        verify(repository, times(2)).findById(1L);
        if (role == Role.PATIENT) verify(clinical).registerPatient("synthetic-1");
        else verify(clinical).practitionerExists("synthetic-1");
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void rejectsReassignmentWithoutCallingClinical(Role role) {
        User account = account(role);
        link(account, role, "existing");
        when(repository.findById(1L)).thenReturn(Optional.of(account));
        assertThatThrownBy(() -> service.assign(1L, "synthetic-1")).isInstanceOf(AccountFhirIdentityException.class);
        verifyNoInteractions(clinical);
    }

    @Test
    void clinicalFailuresPropagateUnchangedWithoutUpdates() {
        for (RestClientException failure : new RestClientException[] {
                new ResourceAccessException("Clinical unreachable"),
                HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", null, null, null)}) {
            org.mockito.Mockito.reset(repository, clinical);
            prepare(Role.PATIENT);
            when(clinical.registerPatient("synthetic-1")).thenThrow(failure);
            assertThatThrownBy(() -> service.assign(1L, "synthetic-1")).isSameAs(failure);
            verify(repository).findById(1L);
            verifyNoMoreInteractions(repository);
        }
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void aResourceClinicalDoesNotFindIsNeverLinked(Role role) {
        prepare(role);
        if (role == Role.PATIENT) when(clinical.registerPatient("synthetic-1")).thenReturn(false);
        else when(clinical.practitionerExists("synthetic-1")).thenReturn(false);
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
        if (role == Role.PATIENT) when(clinical.registerPatient("synthetic-1")).thenReturn(true);
        else when(clinical.practitionerExists("synthetic-1")).thenReturn(true);
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
