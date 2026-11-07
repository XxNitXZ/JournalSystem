package com.medinexus.medicore.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.access.AccessDeniedException;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;

class PatientAccessServiceTest {
    private final UserRepository repository = mock(UserRepository.class);
    private final PatientAccessService service = new PatientAccessService(repository);
    private final UserDto principal = new UserDto(918273L, "synthetic@example.test", Role.PATIENT);

    @Test
    void permitsPatientsOwnIdentityUsingLocalAccountId() {
        account(Role.PATIENT, "synthetic-patient");
        service.checkPatientAccess(principal, "synthetic-patient");
        verify(repository).findById(principal.id());
        verifyNoMoreInteractions(repository);
    }

    @Test
    void deniesAnotherPatientIncludingCaseDifferences() {
        account(Role.PATIENT, "synthetic-patient");
        denied(principal, "another-patient");
        denied(principal, "Synthetic-patient");
    }

    @Test
    void deniesUnlinkedPatient() {
        account(Role.PATIENT, null);
        denied(principal, "synthetic-patient");
    }

    @Test
    void deniesMissingOrDeletedAccount() {
        when(repository.findById(principal.id())).thenReturn(Optional.empty());
        denied(principal, "synthetic-patient");
    }

    @Test
    void deniesNullPrincipalAndInvalidAccountIdsBeforeLookup() {
        denied(null, "synthetic-patient");
        for (Long id : new Long[] {null, 0L, -1L}) {
            denied(new UserDto(id, principal.email(), Role.DOCTOR), "synthetic-patient");
        }
        verifyNoInteractions(repository);
    }

    @Test
    void deniesInvalidPatientIdsBeforeLookupEvenForProfessionalPrincipal() {
        var doctor = new UserDto(principal.id(), principal.email(), Role.DOCTOR);
        for (String id : new String[] {null, "", " ", " patient", "patient ", "Patient/1",
                "https://example.test/Patient/1", "patient_name", "patient?x=1", "x".repeat(65)}) {
            denied(doctor, id);
        }
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void permitsPersistedProfessionalRoleDespitePatientSessionRole(Role role) {
        account(role, null);
        service.checkPatientAccess(principal, "any-patient");
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void persistedPatientCannotUseStaleProfessionalSessionRole(Role sessionRole) {
        account(Role.PATIENT, "synthetic-patient");
        var stale = new UserDto(principal.id(), principal.email(), sessionRole);
        denied(stale, "another-patient");
        service.checkPatientAccess(stale, "synthetic-patient");
    }

    @Test
    void deniesNullPersistedRoleEvenWithMatchingIdentity() {
        // Null is the only invalid Role value representable by this enum in a unit test.
        account(null, "synthetic-patient");
        denied(principal, "synthetic-patient");
    }

    @Test
    void acceptsLogicalIdBoundariesWithoutNormalization() {
        for (String id : new String[] {"a", "A0.-", "x".repeat(64)}) {
            account(Role.PATIENT, id);
            service.checkPatientAccess(principal, id);
        }
    }

    private void account(Role role, String patientId) {
        User account = new User();
        account.setRole(role);
        account.setFhirPatientId(patientId);
        when(repository.findById(principal.id())).thenReturn(Optional.of(account));
    }

    private void denied(UserDto user, String patientId) {
        assertThatThrownBy(() -> service.checkPatientAccess(user, patientId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access denied")
                .hasMessageNotContaining(principal.id().toString())
                .hasMessageNotContaining(principal.email())
                .hasMessageNotContaining("synthetic-patient")
                .hasMessageNotContaining("another-patient")
                .hasCause(null);
    }
}
