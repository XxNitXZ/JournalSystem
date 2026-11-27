package com.medinexus.medicore.medicalrecord.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.access.AccessDeniedException;
import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.auth.service.PatientAccessService;
import com.medinexus.medicore.fhir.client.*;
import com.medinexus.medicore.fhir.mapper.ObservationMapper;
import com.medinexus.medicore.medicalrecord.dto.NewObservationRequest;
import com.medinexus.medicore.medicalrecord.dto.CreateObservationRequest;
import com.medinexus.medicore.patient.registry.PatientRegistry;

class ObservationServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final PatientRegistry registry = mock(PatientRegistry.class);
    private final FhirPatientClient patients = mock(FhirPatientClient.class);
    private final FhirObservationClient observations = mock(FhirObservationClient.class);
    private final ObservationService service = new ObservationService(users, new PatientAccessService(users), registry,
            patients, observations, new ObservationMapper());
    private final UserDto principal = new UserDto(1L, "synthetic@example.test", Role.DOCTOR);

    @Test void rejectsInvalidValueBeforeNetwork() {
        account(Role.DOCTOR);
        var concept = new NewObservationRequest.ConceptInput("Synthetic", List.of());
        var invalid = new NewObservationRequest(CreateObservationRequest.Status.FINAL, concept, "2026", null, null, null);
        assertThatThrownBy(() -> service.create(principal, "synthetic", invalid)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(patients, observations);
    }

    @ParameterizedTest @EnumSource(FhirServerException.Type.class)
    void observationSearchFailuresPropagateUnchanged(FhirServerException.Type type) {
        account(Role.DOCTOR);
        when(registry.isKnown("synthetic")).thenReturn(true);
        var patient = new org.hl7.fhir.r4.model.Patient(); patient.setId("synthetic");
        when(patients.readPatient("synthetic")).thenReturn(patient);
        var failure = new FhirServerException("observation-search", type, null, List.of(), null);
        when(observations.findByPatientId("synthetic")).thenThrow(failure);
        assertThatThrownBy(() -> service.listForPatient(principal, "synthetic")).isSameAs(failure);
    }

    @Test void anonymousAndStaleRolesFailWithoutNetwork() {
        assertThatThrownBy(() -> service.listOwn(null)).isInstanceOf(AccessDeniedException.class);
        account(Role.PATIENT);
        assertThatThrownBy(() -> service.listForPatient(principal, "synthetic")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(patients, observations);
    }

    private void account(Role role) {
        User user = new User(); user.setRole(role);
        when(users.findById(1L)).thenReturn(Optional.of(user));
    }
}
