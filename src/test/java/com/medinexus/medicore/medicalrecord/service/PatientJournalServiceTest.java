package com.medinexus.medicore.medicalrecord.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.medicalrecord.dto.*;

class PatientJournalServiceTest {
    private final EncounterService encounters = mock(EncounterService.class);
    private final DiagnosisService diagnoses = mock(DiagnosisService.class);
    private final ClinicalNoteService notes = mock(ClinicalNoteService.class);
    private final ObservationService observations = mock(ObservationService.class);
    private final PatientJournalService service = new PatientJournalService(encounters, diagnoses, notes, observations);
    private final UserDto principal = new UserDto(7L, "synthetic@example.test", Role.PATIENT);
    private final String patientId = "synthetic-patient";

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aggregatesExistingDtosAndForwardsTheSameIdentity(boolean own) {
        var encounter = new EncounterSummaryDto("e1", "2026", null, "finished", "Synthetic", List.of(), null, null);
        var diagnosis = new DiagnosisDto("d1", patientId, "Synthetic", null, null, null, null, null, null, null, null);
        var note = new ClinicalNoteDto("n1", patientId, null, null, null, "current", null, "Synthetic", true);
        var result = new ObservationSearchResult(List.of(new ObservationDto("o1", "final", null, null,
                null, null, List.of(), null, null)), true, 0);
        stub(own, List.of(encounter), List.of(diagnosis), List.of(note), result);

        var journal = load(own);

        assertThat(journal.encounters()).containsExactly(encounter);
        assertThat(journal.diagnoses()).containsExactly(diagnosis);
        assertThat(journal.clinicalNotes()).containsExactly(note);
        assertThat(journal.observations()).isSameAs(result);
        if (own) {
            verify(encounters).listOwn(same(principal));
            verify(diagnoses).listOwn(same(principal));
            verify(notes).listOwn(same(principal));
            verify(observations).listOwn(same(principal));
        } else {
            verify(encounters).listForPatient(same(principal), eq(patientId));
            verify(diagnoses).listForPatient(same(principal), eq(patientId));
            verify(notes).listForPatient(same(principal), eq(patientId));
            verify(observations).listForPatient(same(principal), eq(patientId));
        }
        verifyNoMoreInteractions(encounters, diagnoses, notes, observations);
    }

    @ParameterizedTest
    @CsvSource({"true,0", "true,2", "false,0", "false,2"})
    void preservesEmptyResultsAndObservationCompleteness(boolean own, int excluded) {
        var result = new ObservationSearchResult(List.of(), excluded == 0, excluded);
        stub(own, List.of(), List.of(), List.of(), result);
        var journal = load(own);
        assertThat(journal.encounters()).isEmpty();
        assertThat(journal.diagnoses()).isEmpty();
        assertThat(journal.clinicalNotes()).isEmpty();
        assertThat(journal.observations()).isSameAs(result);
        assertThat(journal.observations().complete()).isEqualTo(excluded == 0);
        assertThat(journal.observations().excludedCount()).isEqualTo(excluded);
    }

    @ParameterizedTest
    @CsvSource({"true,0", "true,1", "true,2", "true,3", "false,0", "false,1", "false,2", "false,3"})
    void propagatesUpstreamFailureFromEverySection(boolean own, int section) {
        stub(own, List.of(), List.of(), List.of(), new ObservationSearchResult(List.of(), true, 0));
        var failure = new FhirServerException("synthetic-search", FhirServerException.Type.INVALID_RESPONSE,
                null, List.of(), null);
        if (own) {
            switch (section) {
                case 0 -> when(encounters.listOwn(principal)).thenThrow(failure);
                case 1 -> when(diagnoses.listOwn(principal)).thenThrow(failure);
                case 2 -> when(notes.listOwn(principal)).thenThrow(failure);
                case 3 -> when(observations.listOwn(principal)).thenThrow(failure);
            }
        } else {
            switch (section) {
                case 0 -> when(encounters.listForPatient(principal, patientId)).thenThrow(failure);
                case 1 -> when(diagnoses.listForPatient(principal, patientId)).thenThrow(failure);
                case 2 -> when(notes.listForPatient(principal, patientId)).thenThrow(failure);
                case 3 -> when(observations.listForPatient(principal, patientId)).thenThrow(failure);
            }
        }
        assertThatThrownBy(() -> load(own)).isSameAs(failure);
    }

    @Test
    void delegatesAnonymousRejectionWithoutLoadingOtherSections() {
        var denied = new AccessDeniedException("Access denied");
        when(encounters.listOwn(null)).thenThrow(denied);
        assertThatThrownBy(() -> service.listOwn(null)).isSameAs(denied);
        verifyNoInteractions(diagnoses, notes, observations);
    }

    @Test
    void preservesClinicianAuthorizationAndInvalidIdentityFailures() {
        var denied = new AccessDeniedException("Access denied");
        when(encounters.listForPatient(principal, patientId)).thenThrow(denied);
        assertThatThrownBy(() -> service.listForPatient(principal, patientId)).isSameAs(denied);
        var invalid = new IllegalArgumentException("Invalid Patient ID");
        when(encounters.listForPatient(principal, "../other")).thenThrow(invalid);
        assertThatThrownBy(() -> service.listForPatient(principal, "../other")).isSameAs(invalid);
        verifyNoInteractions(diagnoses, notes, observations);
    }

    @Test
    void journalCollectionsAreImmutableSnapshotsOfInputLists() {
        var source = new ArrayList<EncounterSummaryDto>();
        var journal = new PatientJournalDto(source, List.of(), List.of(),
                new ObservationSearchResult(List.of(), true, 0));
        source.add(new EncounterSummaryDto("e1", null, null, "finished", null, List.of(), null, null));
        assertThat(journal.encounters()).isEmpty();
        assertThatThrownBy(() -> journal.encounters().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> journal.diagnoses().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> journal.clinicalNotes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private PatientJournalDto load(boolean own) {
        return own ? service.listOwn(principal) : service.listForPatient(principal, patientId);
    }

    private void stub(boolean own, List<EncounterSummaryDto> visits, List<DiagnosisDto> conditions,
            List<ClinicalNoteDto> clinicalNotes, ObservationSearchResult result) {
        if (own) {
            when(encounters.listOwn(principal)).thenReturn(visits);
            when(diagnoses.listOwn(principal)).thenReturn(conditions);
            when(notes.listOwn(principal)).thenReturn(clinicalNotes);
            when(observations.listOwn(principal)).thenReturn(result);
        } else {
            when(encounters.listForPatient(principal, patientId)).thenReturn(visits);
            when(diagnoses.listForPatient(principal, patientId)).thenReturn(conditions);
            when(notes.listForPatient(principal, patientId)).thenReturn(clinicalNotes);
            when(observations.listForPatient(principal, patientId)).thenReturn(result);
        }
    }
}
