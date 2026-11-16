package com.medinexus.medicore.patient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.access.AccessDeniedException;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.fhir.client.FhirPatientClient;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.journalaccess.JournalAccessMatcher;
import com.medinexus.medicore.patient.dto.PatientCandidateDto;
import com.medinexus.medicore.patient.dto.PatientSearchCriteria;
import com.medinexus.medicore.patient.registry.PatientRegistry;

/** Import of existing FHIR Patients with mocked registry, matcher and FHIR client. Fictional data only. */
@ExtendWith(OutputCaptureExtension.class)
class PatientImportServiceTest {

    private static final String SYSTEM = "http://electronichealth.se/identifier/personnummer";
    private static final String PNR = "200412201971";

    private final UserRepository users = mock(UserRepository.class);
    private final FhirPatientClient patients = mock(FhirPatientClient.class);
    private final PatientRegistry registry = mock(PatientRegistry.class);
    private final JournalAccessMatcher matcher = mock(JournalAccessMatcher.class);
    private final PatientImportService service = new PatientImportService(users, patients, registry, matcher);

    private UserDto signedIn(Role role) {
        User account = new User();
        account.setRole(role);
        when(users.findById(7L)).thenReturn(Optional.of(account));
        return new UserDto(7L, "x@example.test", role);
    }

    private static Patient patient(String id, String given, String family, String personnummer, String birthDate) {
        Patient patient = new Patient();
        patient.setId(id);
        if (family != null || given != null) {
            HumanName name = patient.addName().setUse(HumanName.NameUse.OFFICIAL).setFamily(family);
            if (given != null) name.addGiven(given);
        }
        if (personnummer != null) patient.addIdentifier().setSystem(SYSTEM).setValue(personnummer);
        if (birthDate != null) patient.getBirthDateElement().setValueAsString(birthDate);
        patient.setGender(AdministrativeGender.MALE);
        return patient;
    }

    @BeforeEach
    void defaults() {
        when(registry.knownIds()).thenReturn(List.of("ext-known"));
        when(registry.recordImport(anyString(), any())).thenReturn(true);
    }

    // ---------- search ----------

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void searchesByNameAndMarksKnownImportableAndMatchablePatients(Role role, CapturedOutput output) {
        UserDto clinician = signedIn(role);
        when(patients.discoverPatients(any())).thenReturn(new FhirPatientClient.Discovery(List.of(
                patient("ext-known", "Ali", "Khan", PNR, "2004-12-20"),
                patient("ext-2", "Ali", "Khan", null, "1990-01-01"),
                patient("ext-3", "Ali", "Khan", "20041220-1971", null),
                patient("ext-4", null, null, null, null),
                patient("ext-5", "Ali", "Khan", "20041320-1971", null)), true));

        var result = service.discover(clinician, "NAME", "  Ali Khan ");

        ArgumentCaptor<PatientSearchCriteria> criteria = ArgumentCaptor.forClass(PatientSearchCriteria.class);
        verify(patients).discoverPatients(criteria.capture());
        assertThat(criteria.getValue().nameParts()).containsExactly("Ali", "Khan");
        assertThat(result.truncated()).isTrue();
        assertThat(result.patients()).containsExactly(
                new PatientCandidateDto("ext-known", "Ali Khan", PNR, "male", "2004-12-20", true, true, true),
                // No personnummer: importable for clinical use, but never matched with an account.
                new PatientCandidateDto("ext-2", "Ali Khan", null, "male", "1990-01-01", false, true, false),
                // YYYYMMDD-XXXX (as on the KTH server) is the same personnummer, in canonical form and matchable.
                new PatientCandidateDto("ext-3", "Ali Khan", PNR, "male", null, false, true, true),
                // Without a name it cannot be told apart.
                new PatientCandidateDto("ext-4", null, null, "male", null, false, false, false),
                // Not a real date: shown as stored, never matchable.
                new PatientCandidateDto("ext-5", "Ali Khan", "20041320-1971", "male", null, false, true, false,
                        "INVALID"));
        assertThat(output.getAll()).contains("Patient import search: accountId=7, mode=NAME, count=5")
                .doesNotContain("Khan", PNR);
    }

    @Test
    void searchesByExactPersonnummerAndByExactId() {
        UserDto doctor = signedIn(Role.DOCTOR);
        when(patients.discoverPatients(any())).thenReturn(new FhirPatientClient.Discovery(List.of(), false));
        service.discover(doctor, "PERSONNUMMER", "20041220-1971");
        ArgumentCaptor<PatientSearchCriteria> criteria = ArgumentCaptor.forClass(PatientSearchCriteria.class);
        verify(patients).discoverPatients(criteria.capture());
        assertThat(criteria.getValue().personnummer()).isEqualTo(PNR);

        when(patients.readPatient("ext-9")).thenReturn(patient("ext-9", "Ali", "Khan", null, "1990-01-01"));
        assertThat(service.discover(doctor, "ID", " ext-9 ").patients()).extracting(PatientCandidateDto::id)
                .containsExactly("ext-9");
        when(patients.readPatient("ext-404")).thenThrow(new FhirServerException("patient-read",
                FhirServerException.Type.CLIENT_ERROR, 404, List.of(), null));
        assertThat(service.discover(doctor, "ID", "ext-404").patients()).isEmpty();
        when(patients.readPatient("ext-x")).thenReturn(patient("ext-other", "A", "B", null, "1990-01-01"));
        assertThatThrownBy(() -> service.discover(doctor, "ID", "ext-x")).isInstanceOf(FhirServerException.class);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "null, Ali", "name, Ali", "SURNAME, Ali", "NAME, null", "NAME, a", "NAME, Ali1", "NAME, 200412201971",
            "PERSONNUMMER, 12345", "PERSONNUMMER, 200402301234", "PERSONNUMMER, Ali", "ID, bad_id", "ID, null",
            "ID, Patient/ext-1"})
    void invalidSearchesAreRejectedBeforeAnyFhirRequest(String mode, String value) {
        UserDto staff = signedIn(Role.STAFF);
        assertThatThrownBy(() -> service.discover(staff, mode, value))
                .isInstanceOfSatisfying(PatientImportException.class,
                        failure -> assertThat(failure.code()).isEqualTo("SEARCH_INVALID"));
        verifyNoInteractions(patients);
    }

    @Test
    void patientsAndUnknownAccountsCannotSearchOrImport() {
        UserDto patient = signedIn(Role.PATIENT);
        assertThatThrownBy(() -> service.discover(patient, "NAME", "Ali")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.importPatient(patient, "ext-1")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.discover(null, "NAME", "Ali")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(patients, matcher);
        verify(registry, never()).recordImport(anyString(), any());
    }

    @Test
    void outagesArePassedOn() {
        UserDto doctor = signedIn(Role.DOCTOR);
        FhirServerException down = new FhirServerException("patient-discovery", FhirServerException.Type.UNAVAILABLE,
                null, List.of(), "x");
        when(patients.discoverPatients(any())).thenThrow(down);
        assertThatThrownBy(() -> service.discover(doctor, "NAME", "Ali")).isSameAs(down);
    }

    // ---------- import ----------

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void importRecordsOnlyTheIdAndLetsAWaitingRequestMatch(Role role, CapturedOutput output) {
        UserDto clinician = signedIn(role);
        when(patients.readPatient("ext-1")).thenReturn(patient("ext-1", "Ali", "Khan", PNR, "2004-12-20"));
        when(matcher.onPatientImported(PNR)).thenReturn(true);

        PatientCandidateDto imported = service.importPatient(clinician, "ext-1");

        assertThat(imported).isEqualTo(new PatientCandidateDto("ext-1", "Ali Khan", PNR, "male", "2004-12-20", true,
                true, true));
        verify(registry).recordImport("ext-1", 7L);
        verify(matcher).onPatientImported(PNR);
        // Nothing is created or changed in FHIR.
        verify(patients, never()).createPatient(any());
        verify(patients, never()).updatePatient(any(), any());
        assertThat(output.getAll()).contains("Patient imported: accountId=7, journalRequestMatched=true")
                .doesNotContain(PNR, "Khan", "ext-1");
    }

    @Test
    void withoutAValidPersonnummerNothingIsMatchedButTheImportWorks() {
        UserDto doctor = signedIn(Role.DOCTOR);
        // Wrong separator placement, an impossible date, a future birth date: never matchable.
        for (String invalid : new String[] {"2004122-01971", "20041232-1971", "20991220-1971"}) {
            String id = "ext-invalid-" + invalid.hashCode();
            when(patients.readPatient(id)).thenReturn(patient(id, "Ali", "Khan", invalid, "2004-12-20"));
            assertThat(service.importPatient(doctor, id).journalMatchable()).as(invalid).isFalse();
        }
        when(patients.readPatient("ext-3")).thenReturn(patient("ext-3", "Ali", "Khan", null, "2004-12-20"));
        assertThat(service.importPatient(doctor, "ext-3").registered()).isTrue();
        verifyNoInteractions(matcher);
    }

    @Test
    void aMatchingFailureDoesNotUndoTheImport(CapturedOutput output) {
        UserDto doctor = signedIn(Role.DOCTOR);
        when(patients.readPatient("ext-1")).thenReturn(patient("ext-1", "Ali", "Khan", PNR, "2004-12-20"));
        when(matcher.onPatientImported(PNR)).thenThrow(new FhirServerException("patient-search",
                FhirServerException.Type.UNAVAILABLE, null, List.of(), "x"));
        assertThat(service.importPatient(doctor, "ext-1").registered()).isTrue();
        assertThat(output.getAll()).contains("Journal access matching after patient import failed: accountId=7");
    }

    @Test
    void duplicatesUnknownInvalidAndUnusablePatientsAreRejected() {
        UserDto doctor = signedIn(Role.DOCTOR);
        when(registry.isRegistered("ext-dup")).thenReturn(true);
        assertCode(() -> service.importPatient(doctor, "ext-dup"), "ALREADY_REGISTERED");
        verify(patients, never()).readPatient("ext-dup");

        // Registered by another request between the check and the insert.
        when(patients.readPatient("ext-race")).thenReturn(patient("ext-race", "Ali", "Khan", null, "2004-12-20"));
        when(registry.recordImport("ext-race", 7L)).thenReturn(false);
        assertCode(() -> service.importPatient(doctor, "ext-race"), "ALREADY_REGISTERED");

        when(patients.readPatient("ext-404")).thenThrow(new FhirServerException("patient-read",
                FhirServerException.Type.CLIENT_ERROR, 410, List.of(), null));
        assertThatThrownBy(() -> service.importPatient(doctor, "ext-404")).isInstanceOf(PatientNotFoundException.class);

        when(patients.readPatient("ext-noname")).thenReturn(patient("ext-noname", null, null, PNR, "2004-12-20"));
        assertCode(() -> service.importPatient(doctor, "ext-noname"), "PATIENT_NOT_IMPORTABLE");
        when(patients.readPatient("ext-bare")).thenReturn(patient("ext-bare", "Ali", "Khan", null, null));
        assertCode(() -> service.importPatient(doctor, "ext-bare"), "PATIENT_NOT_IMPORTABLE");

        for (String invalid : new String[] {null, "", "bad_id", "Patient/1", "x".repeat(65)}) {
            assertCode(() -> service.importPatient(doctor, invalid), "INVALID_REQUEST");
        }
        verify(registry, never()).recordImport("ext-noname", 7L);
        verify(registry, never()).recordImport("ext-bare", 7L);
        verifyNoInteractions(matcher);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PatientImportException.class,
                failure -> assertThat(failure.code()).isEqualTo(code));
    }
}
