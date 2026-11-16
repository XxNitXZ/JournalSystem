package com.medinexus.medicore.patient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.access.AccessDeniedException;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.auth.service.PatientAccessService;
import com.medinexus.medicore.fhir.client.DuplicatePatientException;
import com.medinexus.medicore.fhir.client.FhirPatientClient;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.fhir.mapper.PatientMapper;
import com.medinexus.medicore.patient.dto.CreatePatientRequest;
import com.medinexus.medicore.patient.dto.PatientDto;
import com.medinexus.medicore.patient.dto.PatientDto.PatientIdentifier;
import com.medinexus.medicore.patient.dto.PatientDto.PatientName;
import com.medinexus.medicore.patient.registry.PatientRegistry;

/** US-04.01 service rules with mocked repository and FHIR client. Synthetic test personnummer only. */
@ExtendWith(OutputCaptureExtension.class)
class PatientServiceTest {

    private static final String PERSONNUMMER = "199001012385";
    private static final CreatePatientRequest REQUEST = new CreatePatientRequest(PERSONNUMMER, "Testsson",
            List.of("Tolvan"), CreatePatientRequest.Gender.MALE, LocalDate.of(1990, 1, 1));

    private final UserRepository users = mock(UserRepository.class);
    private final FhirPatientClient patients = mock(FhirPatientClient.class);
    private final PatientRegistry registry = mock(PatientRegistry.class);
    private final com.medinexus.medicore.journalaccess.JournalAccessMatcher matcher =
            mock(com.medinexus.medicore.journalaccess.JournalAccessMatcher.class);
    private final PatientService service = new PatientService(users, patients, new PatientMapper(),
            new PatientAccessService(users), registry, matcher);

    @BeforeEach
    void patientsAreKnownUnlessATestSaysOtherwise() {
        when(registry.isKnown(any())).thenReturn(true);
    }

    private void storedRole(long id, Role role) {
        User account = new User();
        account.setRole(role);
        when(users.findById(id)).thenReturn(Optional.of(account));
    }

    private static void assertNoPatientData(CapturedOutput output) {
        for (String forbidden : List.of(PERSONNUMMER, "9001012385", "Testsson", "Tolvan", "1990-01-01", "local-9")) {
            assertThat(output.getAll().contains(forbidden)).as("log contains patient data").isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void doctorsAndStaffRegisterAPatientAndGetTheRegisteredData(Role role, CapturedOutput output) {
        storedRole(7L, role);
        when(patients.createPatient(REQUEST)).thenReturn("local-9");

        PatientDto result = service.registerPatient(new UserDto(7L, "staff@example.test", role), REQUEST);

        assertThat(result).isEqualTo(new PatientDto("local-9",
                List.of(new PatientIdentifier("http://electronichealth.se/identifier/personnummer", PERSONNUMMER)),
                List.of(new PatientName("official", null, "Testsson", List.of("Tolvan"))), "male", "1990-01-01"));
        verify(patients).createPatient(REQUEST);
        assertThat(output.getAll()).contains("Patient registered: accountId=7");
        assertNoPatientData(output);
    }

    @Test
    void theRoleIsReadFromTheDatabaseNotFromTheSession() {
        // The session still says DOCTOR, but the account is now a PATIENT.
        storedRole(7L, Role.PATIENT);
        assertThatThrownBy(() -> service.registerPatient(new UserDto(7L, "x@example.test", Role.DOCTOR), REQUEST))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(patients);
    }

    @Test
    void missingOrUnknownAccountsAreDenied() {
        when(users.findById(any())).thenReturn(Optional.empty());
        for (UserDto principal : new UserDto[] {null, new UserDto(null, "x@example.test", Role.STAFF),
                new UserDto(0L, "x@example.test", Role.STAFF), new UserDto(-1L, "x@example.test", Role.STAFF),
                new UserDto(99L, "x@example.test", Role.STAFF)}) {
            assertThatThrownBy(() -> service.registerPatient(principal, REQUEST))
                    .isInstanceOf(AccessDeniedException.class).hasMessage("Access denied");
        }
        verifyNoInteractions(patients);
    }

    @Test
    void duplicatesArePassedOnAndLoggedWithoutPatientData(CapturedOutput output) {
        storedRole(7L, Role.STAFF);
        when(patients.createPatient(REQUEST)).thenThrow(new DuplicatePatientException());
        assertThatThrownBy(() -> service.registerPatient(new UserDto(7L, "x@example.test", Role.STAFF), REQUEST))
                .isInstanceOf(DuplicatePatientException.class);
        assertThat(output.getAll()).contains("Patient registration rejected as duplicate: accountId=7")
                .doesNotContain("Patient registered:");
        assertNoPatientData(output);
    }

    @Test
    void fhirFailuresAndInvalidDataArePassedOnUnchanged(CapturedOutput output) {
        storedRole(7L, Role.DOCTOR);
        UserDto doctor = new UserDto(7L, "x@example.test", Role.DOCTOR);
        FhirServerException unavailable = new FhirServerException("patient-create",
                FhirServerException.Type.UNAVAILABLE, null, List.of(), "ConnectException");
        when(patients.createPatient(REQUEST)).thenThrow(unavailable);
        assertThatThrownBy(() -> service.registerPatient(doctor, REQUEST)).isSameAs(unavailable);

        IllegalArgumentException invalid = new IllegalArgumentException("A valid Swedish personnummer is required");
        CreatePatientRequest bad = new CreatePatientRequest("199001012386", "Testsson", List.of("Tolvan"),
                CreatePatientRequest.Gender.MALE, LocalDate.of(1990, 1, 1));
        when(patients.createPatient(bad)).thenThrow(invalid);
        assertThatThrownBy(() -> service.registerPatient(doctor, bad)).isSameAs(invalid);

        assertThat(output.getAll()).doesNotContain("Patient registered:");
        assertNoPatientData(output);
    }

    @Test
    void onlyTheRegistryEntryIsWrittenAfterTheFhirPatientExists() {
        storedRole(7L, Role.STAFF);
        when(patients.createPatient(REQUEST)).thenReturn("local-9");
        service.registerPatient(new UserDto(7L, "x@example.test", Role.STAFF), REQUEST);
        var order = org.mockito.Mockito.inOrder(patients, registry);
        order.verify(patients).createPatient(REQUEST);
        order.verify(registry).record("local-9", 7L);
        verify(users).findById(7L);
        verify(users, never()).save(any());
        verify(users, never()).saveAndFlush(any());
    }

    // --- getPatient (US-04.02). Synthetic Patient only. ---

    private static final String PATIENT_ID = "syn-profile-41";

    private static Patient storedPatient(String id) {
        Patient patient = new Patient();
        patient.setId("Patient/" + id + "/_history/2");
        patient.addIdentifier().setSystem("http://electronichealth.se/identifier/personnummer").setValue(PERSONNUMMER);
        patient.addName().setUse(HumanName.NameUse.OFFICIAL).setFamily("Testsson").addGiven("Tolvan");
        patient.setGender(Enumerations.AdministrativeGender.MALE);
        patient.getBirthDateElement().setValueAsString("1990-01-01");
        return patient;
    }

    private static FhirServerException clientError(int status) {
        return new FhirServerException("patient-read", FhirServerException.Type.CLIENT_ERROR, status,
                List.of("error:not-found"), "ResourceNotFoundException");
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void doctorsAndStaffViewAPatientProfileReadFromFhir(Role role, CapturedOutput output) {
        storedRole(7L, role);
        when(patients.readPatient(PATIENT_ID)).thenReturn(storedPatient(PATIENT_ID));

        var profile = service.getPatient(new UserDto(7L, "x@example.test", role), PATIENT_ID);

        // No PATIENT account is linked to this Patient: no e-mail. The FHIR version (2) is included for editing.
        assertThat(profile).isEqualTo(com.medinexus.medicore.patient.dto.PatientProfileDto.of(new PatientDto(PATIENT_ID,
                List.of(new PatientIdentifier("http://electronichealth.se/identifier/personnummer", PERSONNUMMER)),
                List.of(new PatientName("official", null, "Testsson", List.of("Tolvan"))), "male", "1990-01-01"), null, "2"));
        verify(patients).readPatient(PATIENT_ID);
        assertThat(output.getAll()).contains("Patient profile viewed: accountId=7");
        assertNoPatientData(output);
        assertThat(output.getAll().contains(PATIENT_ID)).as("log contains the Patient ID").isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void invalidIdsAreRejectedBeforeAnyFhirRequest(Role role) {
        storedRole(7L, role);
        UserDto principal = new UserDto(7L, "x@example.test", role);
        for (String id : new String[] {null, "", " ", "bad_id", "Patient/1", "../1", "id?x=1", "ä", "x".repeat(65)}) {
            assertThatThrownBy(() -> service.getPatient(principal, id))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("A valid logical FHIR Patient ID is required");
        }
        verifyNoInteractions(patients);
    }

    @Test
    void patientAccountsCannotViewProfilesYetNotEvenTheirOwn() {
        // PATIENT self-view belongs to US-04.03.
        User account = new User();
        account.setRole(Role.PATIENT);
        account.setFhirPatientId(PATIENT_ID);
        when(users.findById(7L)).thenReturn(Optional.of(account));
        assertThatThrownBy(() -> service.getPatient(new UserDto(7L, "x@example.test", Role.PATIENT), PATIENT_ID))
                .isInstanceOf(AccessDeniedException.class);
        // A session that still says DOCTOR does not help either.
        assertThatThrownBy(() -> service.getPatient(new UserDto(7L, "x@example.test", Role.DOCTOR), PATIENT_ID))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(patients);
    }

    @Test
    void missingOrUnknownAccountsCannotViewProfiles() {
        when(users.findById(any())).thenReturn(Optional.empty());
        for (UserDto principal : new UserDto[] {null, new UserDto(null, "x@example.test", Role.DOCTOR),
                new UserDto(0L, "x@example.test", Role.DOCTOR), new UserDto(99L, "x@example.test", Role.DOCTOR)}) {
            assertThatThrownBy(() -> service.getPatient(principal, PATIENT_ID)).isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(patients);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {404, 410})
    void missingPatientsAreNotFound(int status, CapturedOutput output) {
        storedRole(7L, Role.DOCTOR);
        when(patients.readPatient(PATIENT_ID)).thenThrow(clientError(status));
        assertThatThrownBy(() -> service.getPatient(new UserDto(7L, "x@example.test", Role.DOCTOR), PATIENT_ID))
                .isInstanceOfSatisfying(PatientNotFoundException.class, failure -> {
                    assertThat(failure.getMessage()).isEqualTo("Patient not found");
                    assertThat(failure.getCause()).isNull();
                });
        assertThat(output.getAll()).contains("Patient profile not found: accountId=7");
        assertThat(output.getAll().contains(PATIENT_ID)).as("log contains the Patient ID").isFalse();
    }

    @Test
    void otherFhirFailuresArePassedOnUnchanged() {
        storedRole(7L, Role.STAFF);
        UserDto staff = new UserDto(7L, "x@example.test", Role.STAFF);
        for (FhirServerException failure : List.of(clientError(400), clientError(403),
                new FhirServerException("patient-read", FhirServerException.Type.UNAVAILABLE, null, List.of(), "ConnectException"),
                new FhirServerException("patient-read", FhirServerException.Type.TIMEOUT, null, List.of(), "SocketTimeoutException"),
                new FhirServerException("patient-read", FhirServerException.Type.SERVER_ERROR, 500, List.of(), null),
                new FhirServerException("patient-read", FhirServerException.Type.INVALID_RESPONSE, null, List.of(), null))) {
            // doThrow: re-stubbing with when(...) would invoke the previous throwing stub.
            org.mockito.Mockito.doThrow(failure).when(patients).readPatient(PATIENT_ID);
            assertThatThrownBy(() -> service.getPatient(staff, PATIENT_ID)).isSameAs(failure);
        }
    }

    @Test
    void aResponseForAnotherPatientIsNeverShown() {
        storedRole(7L, Role.DOCTOR);
        UserDto doctor = new UserDto(7L, "x@example.test", Role.DOCTOR);
        when(patients.readPatient(PATIENT_ID)).thenReturn(storedPatient("syn-other-42"));
        assertThatThrownBy(() -> service.getPatient(doctor, PATIENT_ID))
                .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                    assertThat(failure.type()).isEqualTo(FhirServerException.Type.INVALID_RESPONSE);
                    assertThat(failure.getMessage()).doesNotContain("syn-", PERSONNUMMER, "Testsson");
                });
        when(patients.readPatient(PATIENT_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.getPatient(doctor, PATIENT_ID)).isInstanceOf(FhirServerException.class);
    }

    @Test
    void viewingAProfileWritesNothing() {
        storedRole(7L, Role.DOCTOR);
        when(patients.readPatient(PATIENT_ID)).thenReturn(storedPatient(PATIENT_ID));
        service.getPatient(new UserDto(7L, "x@example.test", Role.DOCTOR), PATIENT_ID);
        verify(patients, never()).createPatient(any());
        verify(users, never()).save(any());
        verify(users, never()).saveAndFlush(any());
        verify(registry, never()).record(any(), any());
    }

    // --- Local registry and synthetic identity policy ---

    @Test
    void aPatientThatIsNotKnownToMediCoreIsNotFoundAndNeverRead(CapturedOutput output) {
        storedRole(7L, Role.DOCTOR);
        when(registry.isKnown(PATIENT_ID)).thenReturn(false);
        assertThatThrownBy(() -> service.getPatient(new UserDto(7L, "x@example.test", Role.DOCTOR), PATIENT_ID))
                .isInstanceOf(PatientNotFoundException.class);
        verifyNoInteractions(patients);
        assertThat(output.getAll().contains(PATIENT_ID)).isFalse();
    }

    @Test
    void aRegistryFailureAfterCreationIsReportedExplicitlyWithTheIdForTheOperator(CapturedOutput output) {
        storedRole(7L, Role.STAFF);
        when(patients.createPatient(REQUEST)).thenReturn("local-9");
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("database down"))
                .when(registry).record("local-9", 7L);

        assertThatThrownBy(() -> service.registerPatient(new UserDto(7L, "x@example.test", Role.STAFF), REQUEST))
                .isInstanceOf(PatientRegistryException.class)
                .hasMessage("The Patient was created but could not be recorded in MediCore");

        assertThat(output.getAll())
                .contains("Patient created in FHIR but not recorded in the registry: accountId=7, fhirPatientId=local-9")
                .doesNotContain("Patient registered:");
        assertNoPatientData(output.getAll().replace("fhirPatientId=local-9", ""));
    }

    @Test
    void nothingIsRecordedWhenTheFhirRegistrationFails() {
        storedRole(7L, Role.DOCTOR);
        when(patients.createPatient(REQUEST)).thenThrow(new DuplicatePatientException());
        UserDto doctor = new UserDto(7L, "x@example.test", Role.DOCTOR);
        assertThatThrownBy(() -> service.registerPatient(doctor, REQUEST)).isInstanceOf(DuplicatePatientException.class);
        org.mockito.Mockito.reset(patients);
        when(patients.createPatient(REQUEST)).thenThrow(new FhirServerException("patient-create",
                FhirServerException.Type.UNAVAILABLE, null, List.of(), "ConnectException"));
        assertThatThrownBy(() -> service.registerPatient(doctor, REQUEST)).isInstanceOf(FhirServerException.class);
        verify(registry, never()).record(any(), any());
    }

    /**
     * A freely chosen fictional personnummer: valid date and check digit, not one of Skatteverket's test numbers,
     * birth year 1820 (personnummer were introduced in 1947, so no living person can have it).
     */
    private static final String FICTIONAL = "182003151236";

    @Test
    void anyValidFictionalPersonnummerIsPassedOnForRegistration(CapturedOutput output) {
        storedRole(7L, Role.STAFF);
        CreatePatientRequest fictional = new CreatePatientRequest(FICTIONAL, "Ångström-Pihl", List.of("Zelda"),
                CreatePatientRequest.Gender.OTHER, LocalDate.of(1820, 3, 15));
        when(patients.createPatient(fictional)).thenReturn("local-12");

        PatientDto registered = service.registerPatient(new UserDto(7L, "x@example.test", Role.STAFF), fictional);

        assertThat(registered.identifiers().getFirst().value()).isEqualTo(FICTIONAL);
        verify(registry).record("local-12", 7L);
        for (String forbidden : List.of(FICTIONAL, "Zelda", "Ångström")) {
            assertThat(output.getAll().contains(forbidden)).as("log contains patient data").isFalse();
        }

        // Invalid numbers still fail validation (400) before any registry entry.
        CreatePatientRequest invalid = new CreatePatientRequest("182003151237", "Testsson", List.of("Tolvan"),
                CreatePatientRequest.Gender.MALE, LocalDate.of(1820, 3, 15));
        IllegalArgumentException validation = new IllegalArgumentException("A valid Swedish personnummer is required");
        when(patients.createPatient(invalid)).thenThrow(validation);
        assertThatThrownBy(() -> service.registerPatient(new UserDto(7L, "x@example.test", Role.STAFF), invalid))
                .isSameAs(validation);
        // Still only the one registry entry from the valid registration.
        verify(registry).record(any(), any());
    }

    // --- Search ---

    private static Patient found(String id, String given, String family, String personnummer, String birthDate) {
        Patient patient = new Patient();
        patient.setId(id);
        patient.addIdentifier().setSystem("http://electronichealth.se/identifier/personnummer").setValue(personnummer);
        patient.addName().setUse(HumanName.NameUse.OFFICIAL).setFamily(family).addGiven(given);
        patient.getBirthDateElement().setValueAsString(birthDate);
        return patient;
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void searchesOnlyKnownPatientsAndReturnsEveryMatchSorted(Role role, CapturedOutput output) {
        storedRole(7L, role);
        when(registry.knownIds()).thenReturn(List.of("a-1", "a-2", "b-1"));
        when(patients.searchPatients(any(), eq(List.of("a-1", "a-2", "b-1")))).thenReturn(List.of(
                found("b-1", "Tolvan", "Testsson", "198506152381", "1985-06-15"),
                found("a-2", "Anna", "Testsson", "200002292381", "2000-02-29"),
                found("a-1", "Anna", "Testsson", PERSONNUMMER, "1990-01-01")));

        List<PatientDto> results = service.searchPatients(new UserDto(7L, "x@example.test", role), "  Testsson ");

        // Identical names stay separate and are ordered by family name, given names and birth date.
        assertThat(results).extracting(PatientDto::id).containsExactly("a-1", "a-2", "b-1");
        assertThat(results.getFirst().identifiers().getFirst().value()).isEqualTo(PERSONNUMMER);
        var criteria = org.mockito.ArgumentCaptor.forClass(com.medinexus.medicore.patient.dto.PatientSearchCriteria.class);
        verify(patients).searchPatients(criteria.capture(), any());
        assertThat(criteria.getValue().nameParts()).containsExactly("Testsson");
        assertThat(output.getAll()).contains("Patient search: accountId=7, results=3");
        assertNoPatientData(output.getAll());
    }

    @Test
    void searchRequiresDoctorOrStaffAndAValidQueryAndNeverSearchesAnEmptyRegistry() {
        storedRole(7L, Role.PATIENT);
        assertThatThrownBy(() -> service.searchPatients(new UserDto(7L, "x@example.test", Role.DOCTOR), "Anna"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.searchPatients(null, "Anna")).isInstanceOf(AccessDeniedException.class);

        storedRole(8L, Role.STAFF);
        UserDto staff = new UserDto(8L, "x@example.test", Role.STAFF);
        for (String invalid : new String[] {null, "", " ", "a", "Anna1", "Anna <script>", "199002301234", "a b c d e f"}) {
            assertThatThrownBy(() -> service.searchPatients(staff, invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        when(registry.knownIds()).thenReturn(List.of());
        assertThat(service.searchPatients(staff, "Anna")).isEmpty();
        verifyNoInteractions(patients);
    }

    private static void assertNoPatientData(String log) {
        for (String forbidden : List.of(PERSONNUMMER, "198506152381", "Testsson", "Tolvan")) {
            assertThat(log.contains(forbidden)).as("log contains patient data").isFalse();
        }
    }

    // --- US-04.03: own record (PATIENT) and the linked account's e-mail (DOCTOR/STAFF) ---

    private User patientAccount(long id, String fhirPatientId) {
        User account = new User();
        org.springframework.test.util.ReflectionTestUtils.setField(account, "id", id);
        account.setRole(Role.PATIENT);
        account.setEmail("tolvan@example.test");
        account.setFhirPatientId(fhirPatientId);
        when(users.findById(id)).thenReturn(Optional.of(account));
        return account;
    }

    @Test
    void aLinkedPatientReadsTheirOwnRecordWithTheirEmail(CapturedOutput output) {
        patientAccount(8L, PATIENT_ID);
        when(patients.readPatient(PATIENT_ID)).thenReturn(storedPatient(PATIENT_ID));

        var record = service.getOwnRecord(new UserDto(8L, "tolvan@example.test", Role.PATIENT));

        assertThat(record.email()).isEqualTo("tolvan@example.test");
        assertThat(record.names()).singleElement().satisfies(name -> assertThat(name.family()).isEqualTo("Testsson"));
        assertThat(record.birthDate()).isEqualTo("1990-01-01");
        assertThat(record.gender()).isEqualTo("male");
        verify(patients).readPatient(PATIENT_ID);
        assertThat(output.getAll()).contains("Own patient record viewed: accountId=8");
        assertNoPatientData(output.getAll());
        assertThat(output.getAll().contains(PATIENT_ID)).isFalse();
    }

    @Test
    void theOwnRecordIsAlwaysTheLinkedOneAndNeverChosenByTheClient() {
        patientAccount(8L, PATIENT_ID);
        when(patients.readPatient(PATIENT_ID)).thenReturn(storedPatient(PATIENT_ID));
        // The session claims another id/e-mail; the record is still resolved from the stored account only.
        service.getOwnRecord(new UserDto(8L, "someone@example.test", Role.PATIENT));
        verify(patients).readPatient(PATIENT_ID);
        verify(patients, never()).readPatient("syn-other-42");
    }

    @Test
    void anUnlinkedPatientHasNoRecordYet() {
        patientAccount(8L, null);
        assertThatThrownBy(() -> service.getOwnRecord(new UserDto(8L, "x@example.test", Role.PATIENT)))
                .isInstanceOfSatisfying(OwnPatientRecordException.class,
                        failure -> assertThat(failure.code()).isEqualTo("RECORD_NOT_LINKED"));
        verifyNoInteractions(patients);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void onlyPatientsHaveAnOwnRecord(Role role) {
        storedRole(7L, role);
        assertThatThrownBy(() -> service.getOwnRecord(new UserDto(7L, "x@example.test", Role.PATIENT)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.getOwnRecord(null)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(patients);
    }

    @Test
    void aMissingOrMismatchingOwnRecordIsNeverShown() {
        patientAccount(8L, PATIENT_ID);
        UserDto patient = new UserDto(8L, "x@example.test", Role.PATIENT);
        when(patients.readPatient(PATIENT_ID)).thenThrow(clientError(410));
        assertThatThrownBy(() -> service.getOwnRecord(patient)).isInstanceOfSatisfying(OwnPatientRecordException.class,
                failure -> assertThat(failure.code()).isEqualTo("RECORD_NOT_FOUND"));

        FhirServerException down = new FhirServerException("patient-read", FhirServerException.Type.TIMEOUT, null,
                List.of(), "SocketTimeoutException");
        org.mockito.Mockito.doThrow(down).when(patients).readPatient(PATIENT_ID);
        assertThatThrownBy(() -> service.getOwnRecord(patient)).isSameAs(down);

        org.mockito.Mockito.doReturn(storedPatient("syn-other-42")).when(patients).readPatient(PATIENT_ID);
        assertThatThrownBy(() -> service.getOwnRecord(patient)).isInstanceOfSatisfying(FhirServerException.class,
                failure -> assertThat(failure.type()).isEqualTo(FhirServerException.Type.INVALID_RESPONSE));
    }

    @Test
    void theProfileShowsTheEmailOfTheLinkedPatientAccountOnly() {
        storedRole(7L, Role.DOCTOR);
        UserDto doctor = new UserDto(7L, "x@example.test", Role.DOCTOR);
        when(patients.readPatient(PATIENT_ID)).thenReturn(storedPatient(PATIENT_ID));

        User linked = new User();
        linked.setRole(Role.PATIENT);
        linked.setEmail("tolvan@example.test");
        when(users.findByFhirPatientId(PATIENT_ID)).thenReturn(Optional.of(linked));
        assertThat(service.getPatient(doctor, PATIENT_ID).email()).isEqualTo("tolvan@example.test");

        // No linked account (or a non-PATIENT account): no e-mail, the UI shows "Not available".
        when(users.findByFhirPatientId(PATIENT_ID)).thenReturn(Optional.empty());
        assertThat(service.getPatient(doctor, PATIENT_ID).email()).isNull();
        linked.setRole(Role.STAFF);
        when(users.findByFhirPatientId(PATIENT_ID)).thenReturn(Optional.of(linked));
        assertThat(service.getPatient(doctor, PATIENT_ID).email()).isNull();
    }

    // --- US-04.04: updating the gender (names, personnummer and birth date are immutable) ---

    private static final com.medinexus.medicore.patient.dto.UpdatePatientRequest UPDATE =
            new com.medinexus.medicore.patient.dto.UpdatePatientRequest(CreatePatientRequest.Gender.FEMALE);

    /** The FHIR server stores what it is sent, with the next version. */
    private void updatesAreStored() {
        when(patients.updatePatient(any(), any())).thenAnswer(invocation -> {
            Patient sent = invocation.<Patient>getArgument(0).copy();
            sent.setId("Patient/" + sent.getIdElement().getIdPart() + "/_history/3");
            return sent;
        });
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void doctorsAndStaffChangeOnlyTheGender(Role role, CapturedOutput output) {
        storedRole(7L, role);
        when(patients.readPatient(PATIENT_ID)).thenReturn(storedPatient(PATIENT_ID));
        updatesAreStored();

        var result = service.updatePatient(new UserDto(7L, "x@example.test", role), PATIENT_ID, "2", UPDATE);

        org.mockito.ArgumentCaptor<Patient> sent = org.mockito.ArgumentCaptor.forClass(Patient.class);
        verify(patients).updatePatient(sent.capture(), eq("2"));
        Patient written = sent.getValue();
        Patient original = storedPatient(PATIENT_ID);
        assertThat(written.getIdElement().getIdPart()).isEqualTo(PATIENT_ID);
        assertThat(written.getGender()).isEqualTo(Enumerations.AdministrativeGender.FEMALE);
        // Everything but the gender is exactly as read, names included.
        original.setGender(Enumerations.AdministrativeGender.FEMALE);
        assertThat(written.equalsDeep(original)).isTrue();
        assertThat(written.getIdentifierFirstRep().equalsDeep(original.getIdentifierFirstRep())).isTrue();
        assertThat(written.getBirthDateElement().getValueAsString()).isEqualTo("1990-01-01");

        assertThat(result.id()).isEqualTo(PATIENT_ID);
        assertThat(result.version()).isEqualTo("3");
        assertThat(result.gender()).isEqualTo("female");
        assertThat(result.names()).singleElement().satisfies(name -> {
            assertThat(name.family()).isEqualTo("Testsson");
            assertThat(name.given()).containsExactly("Tolvan");
        });
        assertThat(result.identifiers()).singleElement().satisfies(identifier ->
                assertThat(identifier.value()).isEqualTo(PERSONNUMMER));
        assertThat(result.birthDate()).isEqualTo("1990-01-01");
        // Nothing in PostgreSQL changes and journal access is not touched.
        verifyNoInteractions(matcher);
        verify(users, never()).save(any());
        verify(users, never()).saveAndFlush(any());
        verify(registry, never()).record(any(), any());
        assertThat(output.getAll()).contains("Patient updated: accountId=7");
        assertNoPatientData(output);
        assertThat(output.getAll()).doesNotContain(PATIENT_ID);
    }

    @Test
    void patientsAndUnknownAccountsCannotUpdate() {
        storedRole(7L, Role.PATIENT);
        assertThatThrownBy(() -> service.updatePatient(new UserDto(7L, "x@example.test", Role.DOCTOR), PATIENT_ID,
                "2", UPDATE)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.updatePatient(null, PATIENT_ID, "2", UPDATE))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(patients);
    }

    @Test
    void invalidDataIdsAndVersionsAreRejectedBeforeAnyFhirRequest() {
        storedRole(7L, Role.STAFF);
        UserDto staff = new UserDto(7L, "x@example.test", Role.STAFF);
        assertThatThrownBy(() -> service.updatePatient(staff, PATIENT_ID, "2",
                new com.medinexus.medicore.patient.dto.UpdatePatientRequest(null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updatePatient(staff, "bad_id", "2", UPDATE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updatePatient(staff, PATIENT_ID, null, UPDATE))
                .isInstanceOf(PatientVersionRequiredException.class);
        assertThatThrownBy(() -> service.updatePatient(staff, PATIENT_ID, "2\"x", UPDATE))
                .isInstanceOf(IllegalArgumentException.class);
        // Not registered by MediCore (another user's Patient on the shared server): not found, never read or written.
        when(registry.isKnown(PATIENT_ID)).thenReturn(false);
        assertThatThrownBy(() -> service.updatePatient(staff, PATIENT_ID, "2", UPDATE))
                .isInstanceOf(PatientNotFoundException.class);
        verifyNoInteractions(patients);
    }

    @Test
    void aChangeSinceTheEditedVersionIsAConflictAndNothingIsWritten() {
        storedRole(7L, Role.DOCTOR);
        UserDto doctor = new UserDto(7L, "x@example.test", Role.DOCTOR);
        when(patients.readPatient(PATIENT_ID)).thenReturn(storedPatient(PATIENT_ID)); // version 2
        assertThatThrownBy(() -> service.updatePatient(doctor, PATIENT_ID, "1", UPDATE))
                .isInstanceOf(PatientVersionConflictException.class);
        verify(patients, never()).updatePatient(any(), any());

        // A change between this read and the write: the FHIR server rejects the version (409 or 412).
        for (int status : new int[] {409, 412}) {
            org.mockito.Mockito.doThrow(new FhirServerException("patient-update", FhirServerException.Type.CLIENT_ERROR,
                    status, List.of(), null)).when(patients).updatePatient(any(), any());
            assertThatThrownBy(() -> service.updatePatient(doctor, PATIENT_ID, "2", UPDATE))
                    .isInstanceOf(PatientVersionConflictException.class);
        }
    }

    @Test
    void missingPatientsAndFhirFailuresAreReportedNeverAsSuccess() {
        storedRole(7L, Role.DOCTOR);
        UserDto doctor = new UserDto(7L, "x@example.test", Role.DOCTOR);
        when(patients.readPatient(PATIENT_ID)).thenThrow(clientError(404));
        assertThatThrownBy(() -> service.updatePatient(doctor, PATIENT_ID, "2", UPDATE))
                .isInstanceOf(PatientNotFoundException.class);

        org.mockito.Mockito.doReturn(storedPatient(PATIENT_ID)).when(patients).readPatient(PATIENT_ID);
        for (FhirServerException failure : List.of(
                new FhirServerException("patient-update", FhirServerException.Type.UNAVAILABLE, null, List.of(), "x"),
                new FhirServerException("patient-update", FhirServerException.Type.TIMEOUT, null, List.of(), "x"),
                new FhirServerException("patient-update", FhirServerException.Type.SERVER_ERROR, 500, List.of(), null),
                new FhirServerException("patient-update", FhirServerException.Type.CLIENT_ERROR, 422, List.of(), null))) {
            org.mockito.Mockito.doThrow(failure).when(patients).updatePatient(any(), any());
            assertThatThrownBy(() -> service.updatePatient(doctor, PATIENT_ID, "2", UPDATE)).isSameAs(failure);
        }
        org.mockito.Mockito.doThrow(clientError(410)).when(patients).updatePatient(any(), any());
        assertThatThrownBy(() -> service.updatePatient(doctor, PATIENT_ID, "2", UPDATE))
                .isInstanceOf(PatientNotFoundException.class);

        // The server answered, but stored a different personnummer: never reported as a success.
        org.mockito.Mockito.doAnswer(invocation -> {
            Patient sent = invocation.<Patient>getArgument(0).copy();
            sent.getIdentifierFirstRep().setValue("199001019999");
            return sent;
        }).when(patients).updatePatient(any(), any());
        assertThatThrownBy(() -> service.updatePatient(doctor, PATIENT_ID, "2", UPDATE))
                .isInstanceOfSatisfying(FhirServerException.class,
                        failure -> assertThat(failure.type()).isEqualTo(FhirServerException.Type.INVALID_RESPONSE));

        // The server stored a different name: names are immutable, so this is not a success either.
        org.mockito.Mockito.doAnswer(invocation -> {
            Patient sent = invocation.<Patient>getArgument(0).copy();
            sent.getNameFirstRep().setFamily("Changed");
            return sent;
        }).when(patients).updatePatient(any(), any());
        assertThatThrownBy(() -> service.updatePatient(doctor, PATIENT_ID, "2", UPDATE))
                .isInstanceOfSatisfying(FhirServerException.class,
                        failure -> assertThat(failure.type()).isEqualTo(FhirServerException.Type.INVALID_RESPONSE));

        // A read without a version cannot be updated safely.
        Patient unversioned = storedPatient(PATIENT_ID);
        unversioned.setId(PATIENT_ID);
        org.mockito.Mockito.doReturn(unversioned).when(patients).readPatient(PATIENT_ID);
        assertThatThrownBy(() -> service.updatePatient(doctor, PATIENT_ID, "2", UPDATE))
                .isInstanceOf(FhirServerException.class);
    }

    @Test
    void theProfileShowsEmailAndPhoneOfTheLinkedPatientAccountOnly() {
        storedRole(7L, Role.DOCTOR);
        UserDto doctor = new UserDto(7L, "x@example.test", Role.DOCTOR);
        Patient withTelecom = storedPatient(PATIENT_ID);
        // Contact data in FHIR is never used.
        withTelecom.addTelecom().setSystem(org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem.EMAIL)
                .setValue("fhir@example.test");
        when(patients.readPatient(PATIENT_ID)).thenReturn(withTelecom);
        assertThat(service.getPatient(doctor, PATIENT_ID).email()).isNull();
        assertThat(service.getPatient(doctor, PATIENT_ID).phoneNumber()).isNull();

        User linked = new User();
        linked.setRole(Role.PATIENT);
        linked.setEmail("tolvan@example.test");
        linked.setPhoneNumber("+46701234567");
        when(users.findByFhirPatientId(PATIENT_ID)).thenReturn(Optional.of(linked));
        var profile = service.getPatient(doctor, PATIENT_ID);
        assertThat(profile.email()).isEqualTo("tolvan@example.test");
        assertThat(profile.phoneNumber()).isEqualTo("+46701234567");

        // An account with another role is not a patient's contact.
        linked.setRole(Role.STAFF);
        assertThat(service.getPatient(doctor, PATIENT_ID).phoneNumber()).isNull();
    }

    @Test
    void theProfileCarriesTheVersionForEditing() {
        storedRole(7L, Role.STAFF);
        when(patients.readPatient(PATIENT_ID)).thenReturn(storedPatient(PATIENT_ID));
        assertThat(service.getPatient(new UserDto(7L, "x@example.test", Role.STAFF), PATIENT_ID).version())
                .isEqualTo("2");
    }
}
