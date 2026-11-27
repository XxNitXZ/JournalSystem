package com.medinexus.medicore.medicalrecord.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
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
import org.springframework.test.util.ReflectionTestUtils;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.auth.service.PatientAccessService;
import com.medinexus.medicore.fhir.client.FhirEncounterClient;
import com.medinexus.medicore.fhir.client.FhirLocationClient;
import com.medinexus.medicore.fhir.client.FhirOrganizationClient;
import com.medinexus.medicore.fhir.client.FhirPatientClient;
import com.medinexus.medicore.fhir.client.FhirPractitionerClient;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest;
import com.medinexus.medicore.medicalrecord.dto.EncounterDto;
import com.medinexus.medicore.medicalrecord.dto.EncounterSummaryDto;
import com.medinexus.medicore.medicalrecord.dto.NewEncounterRequest;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Coding;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.PeriodValue;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.SubjectReference;
import com.medinexus.medicore.patient.registry.PatientRegistry;
import com.medinexus.medicore.patient.service.PatientNotFoundException;
import com.medinexus.medicore.practitioner.service.PractitionerDirectory;

/** US-05.05: Encounter rules with mocked repositories and FHIR clients. Fictional data only. */
@ExtendWith(OutputCaptureExtension.class)
class EncounterServiceTest {

    private static final String PATIENT_ID = "syn-patient-1";
    private static final String PRACTITIONER_ID = "syn-practitioner-1";
    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

    private final UserRepository users = mock(UserRepository.class);
    private final PatientRegistry registry = mock(PatientRegistry.class);
    private final FhirPatientClient patients = mock(FhirPatientClient.class);
    private final FhirPractitionerClient practitioners = mock(FhirPractitionerClient.class);
    private final FhirOrganizationClient organizations = mock(FhirOrganizationClient.class);
    private final FhirLocationClient locations = mock(FhirLocationClient.class);
    private final FhirEncounterClient encounters = mock(FhirEncounterClient.class);
    private final PractitionerDirectory directory = mock(PractitionerDirectory.class);
    private final EncounterService service = new EncounterService(users, new PatientAccessService(users), registry,
            patients, practitioners, organizations, locations, encounters, directory, Clock.fixed(NOW, ZoneOffset.UTC));

    private static User account(long id, Role role, String fhirPatientId) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setRole(role);
        user.setFhirPatientId(fhirPatientId);
        return user;
    }

    private static Practitioner practitioner(String id, String prefix, String given, String family) {
        Practitioner practitioner = new Practitioner();
        practitioner.setId(id);
        HumanName name = practitioner.addName().setUse(HumanName.NameUse.OFFICIAL).setFamily(family).addGiven(given);
        if (prefix != null) name.addPrefix(prefix);
        return practitioner;
    }

    private static Patient patient(String id) {
        Patient patient = new Patient();
        patient.setId(id);
        return patient;
    }

    private UserDto signedIn(long id, Role role) {
        when(users.findById(id)).thenReturn(Optional.of(account(id, role, null)));
        return new UserDto(id, "user" + id + "@example.test", role);
    }

    @BeforeEach
    void aRegisteredPatientAndALinkedPractitioner() {
        when(registry.isKnown(PATIENT_ID)).thenReturn(true);
        when(directory.isSelectable(PRACTITIONER_ID)).thenReturn(true);
        when(patients.readPatient(PATIENT_ID)).thenReturn(patient(PATIENT_ID));
        when(practitioners.readPractitioner(PRACTITIONER_ID)).thenReturn(practitioner(PRACTITIONER_ID, "Dr", "Ali", "Khan"));
        when(encounters.createEncounter(any())).thenReturn("enc-1");
    }

    private static NewEncounterRequest visit(String start) {
        return new NewEncounterRequest(start, PRACTITIONER_ID, "CONSULTATION", null, null, null);
    }

    // ---------- create ----------

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void doctorsAndStaffCreateAVisitForTheOpenedPatient(Role role, CapturedOutput output) {
        UserDto clinician = signedIn(7L, role);

        EncounterSummaryDto created = service.create(clinician, PATIENT_ID, visit("2026-11-10T14:30+01:00"));

        ArgumentCaptor<CreateEncounterRequest> sent = ArgumentCaptor.forClass(CreateEncounterRequest.class);
        verify(encounters).createEncounter(sent.capture());
        CreateEncounterRequest request = sent.getValue();
        assertThat(request.patientId()).isEqualTo(PATIENT_ID);
        assertThat(request.status()).isEqualTo(CreateEncounterRequest.Status.FINISHED);
        assertThat(request.encounterClass()).isEqualTo(CreateEncounterRequest.EncounterClass.AMB);
        // Stored with second precision and the offset given by the client.
        assertThat(request.periodStart()).isEqualTo("2026-11-10T14:30:00+01:00");
        assertThat(request.periodEnd()).isNull();
        assertThat(request.participants()).singleElement().satisfies(participant -> {
            assertThat(participant.practitionerId()).isEqualTo(PRACTITIONER_ID);
            assertThat(participant.types()).containsExactly(CreateEncounterRequest.ParticipantType.ATND);
        });
        assertThat(request.type()).singleElement().satisfies(type -> {
            assertThat(type.text()).isEqualTo("Consultation");
            assertThat(type.coding()).singleElement().satisfies(coding -> {
                assertThat(coding.system()).isEqualTo("http://snomed.info/sct");
                assertThat(coding.code()).isEqualTo("11429006");
            });
        });
        assertThat(request.serviceProviderId()).isNull();
        assertThat(request.locations()).isEmpty();

        assertThat(created).isEqualTo(new EncounterSummaryDto("enc-1", "2026-11-10T14:30:00+01:00", null, "finished",
                "Consultation", List.of("Dr Ali Khan"), null, null));
        assertThat(output.getAll()).contains("Encounter created: accountId=7").doesNotContain(PATIENT_ID);
    }

    @Test
    void organizationLocationTypeAndStatusAreUsedWhenGiven() {
        UserDto doctor = signedIn(7L, Role.DOCTOR);
        Organization organization = new Organization().setName("Vårdcentral Test");
        organization.setId("org-1");
        Location location = new Location().setName("Mottagning 2");
        location.setId("loc-1");
        when(organizations.readOrganization("org-1")).thenReturn(organization);
        when(locations.readLocation("loc-1")).thenReturn(location);

        EncounterSummaryDto created = service.create(doctor, PATIENT_ID, new NewEncounterRequest(
                "2026-12-01T09:00:00Z", PRACTITIONER_ID, "TELEPHONE", "PLANNED", "org-1", "loc-1"));

        ArgumentCaptor<CreateEncounterRequest> sent = ArgumentCaptor.forClass(CreateEncounterRequest.class);
        verify(encounters).createEncounter(sent.capture());
        assertThat(sent.getValue().status()).isEqualTo(CreateEncounterRequest.Status.PLANNED);
        assertThat(sent.getValue().encounterClass()).isEqualTo(CreateEncounterRequest.EncounterClass.VR);
        assertThat(sent.getValue().periodStart()).isEqualTo("2026-12-01T09:00:00Z");
        assertThat(sent.getValue().serviceProviderId()).isEqualTo("org-1");
        assertThat(sent.getValue().locations()).singleElement()
                .satisfies(entry -> assertThat(entry.locationId()).isEqualTo("loc-1"));
        assertThat(created.organization()).isEqualTo("Vårdcentral Test");
        assertThat(created.location()).isEqualTo("Mottagning 2");
        assertThat(created.status()).isEqualTo("planned");
        assertThat(created.type()).isEqualTo("Telephone encounter");
    }

    @Test
    void patientsAndUnknownAccountsCannotCreate() {
        UserDto patient = signedIn(8L, Role.PATIENT);
        assertThatThrownBy(() -> service.create(patient, PATIENT_ID, visit("2026-11-10T14:30:00+01:00")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.create(null, PATIENT_ID, visit("2026-11-10T14:30:00+01:00")))
                .isInstanceOf(AccessDeniedException.class);
        // A stale DOCTOR session for an account that is now a PATIENT.
        assertThatThrownBy(() -> service.create(new UserDto(8L, "x@example.test", Role.DOCTOR), PATIENT_ID,
                visit("2026-11-10T14:30:00+01:00"))).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(encounters, patients, practitioners);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            // start time: missing, no offset, not a date, impossible date, absurd year and offset, future finished
            "null, CONSULTATION, null, START_TIME_INVALID",
            "2026-11-10T14:30, CONSULTATION, null, START_TIME_INVALID",
            "yesterday, CONSULTATION, null, START_TIME_INVALID",
            "2026-02-30T10:00:00+01:00, CONSULTATION, null, START_TIME_INVALID",
            "1850-01-01T10:00:00Z, CONSULTATION, null, START_TIME_INVALID",
            "2026-11-10T10:00:00+15:00, CONSULTATION, null, START_TIME_INVALID",
            "2026-11-12T10:00:00Z, CONSULTATION, FINISHED, START_TIME_IN_FUTURE",
            "2026-11-12T10:00:00Z, CONSULTATION, IN_PROGRESS, START_TIME_IN_FUTURE",
            // type and status
            "2026-11-10T10:00:00Z, null, null, TYPE_INVALID",
            "2026-11-10T10:00:00Z, consultation, null, TYPE_INVALID",
            "2026-11-10T10:00:00Z, SURGERY, null, TYPE_INVALID",
            "2026-11-10T10:00:00Z, CONSULTATION, ARRIVED, STATUS_INVALID",
            "2026-11-10T10:00:00Z, CONSULTATION, finished, STATUS_INVALID"})
    void invalidVisitsAreRejectedBeforeAnyFhirRequest(String start, String type, String status, String code) {
        UserDto doctor = signedIn(7L, Role.DOCTOR);
        assertCode(() -> service.create(doctor, PATIENT_ID,
                new NewEncounterRequest(start, PRACTITIONER_ID, type, status, null, null)), code);
        verifyNoInteractions(encounters, patients, practitioners);
    }

    @Test
    void practitionerOrganizationAndLocationReferencesAreValidated() {
        UserDto doctor = signedIn(7L, Role.DOCTOR);
        String start = "2026-11-10T10:00:00Z";
        assertCode(() -> service.create(doctor, PATIENT_ID, new NewEncounterRequest(start, null, "CONSULTATION", null,
                null, null)), "PRACTITIONER_REQUIRED");
        assertCode(() -> service.create(doctor, PATIENT_ID, new NewEncounterRequest(start, "Practitioner/x",
                "CONSULTATION", null, null, null)), "PRACTITIONER_NOT_AVAILABLE");
        // A Practitioner that is not linked to a MediCore clinician (for example another user's on the shared server).
        assertCode(() -> service.create(doctor, PATIENT_ID, new NewEncounterRequest(start, "syn-foreign",
                "CONSULTATION", null, null, null)), "PRACTITIONER_NOT_AVAILABLE");
        assertCode(() -> service.create(doctor, PATIENT_ID, new NewEncounterRequest(start, PRACTITIONER_ID,
                "CONSULTATION", null, "https://evil.example/Organization/1", null)), "ORGANIZATION_INVALID");
        assertCode(() -> service.create(doctor, PATIENT_ID, new NewEncounterRequest(start, PRACTITIONER_ID,
                "CONSULTATION", null, null, "bad id")), "LOCATION_INVALID");
        verifyNoInteractions(encounters, patients, practitioners);
    }

    @Test
    void theReferencedResourcesMustExistInFhirBeforeAnythingIsCreated() {
        UserDto staff = signedIn(9L, Role.STAFF);
        FhirServerException missing = new FhirServerException("x-read", FhirServerException.Type.CLIENT_ERROR, 404,
                List.of(), null);
        when(practitioners.readPractitioner(PRACTITIONER_ID)).thenThrow(missing);
        assertCode(() -> service.create(staff, PATIENT_ID, visit("2026-11-10T10:00:00Z")), "PRACTITIONER_NOT_FOUND");

        org.mockito.Mockito.doReturn(practitioner(PRACTITIONER_ID, null, "Ali", "Khan"))
                .when(practitioners).readPractitioner(PRACTITIONER_ID);
        when(organizations.readOrganization("org-x")).thenThrow(missing);
        assertCode(() -> service.create(staff, PATIENT_ID, new NewEncounterRequest("2026-11-10T10:00:00Z",
                PRACTITIONER_ID, "CONSULTATION", null, "org-x", null)), "ORGANIZATION_NOT_FOUND");
        when(locations.readLocation("loc-x")).thenThrow(missing);
        assertCode(() -> service.create(staff, PATIENT_ID, new NewEncounterRequest("2026-11-10T10:00:00Z",
                PRACTITIONER_ID, "CONSULTATION", null, null, "loc-x")), "LOCATION_NOT_FOUND");
        verify(encounters, never()).createEncounter(any());
    }

    @Test
    void thePatientMustBeRegisteredInMediCoreAndExist() {
        UserDto doctor = signedIn(7L, Role.DOCTOR);
        when(registry.isKnown("syn-foreign")).thenReturn(false);
        assertThatThrownBy(() -> service.create(doctor, "syn-foreign", visit("2026-11-10T10:00:00Z")))
                .isInstanceOf(PatientNotFoundException.class);
        verifyNoInteractions(patients, encounters);

        when(patients.readPatient(PATIENT_ID)).thenThrow(new FhirServerException("patient-read",
                FhirServerException.Type.CLIENT_ERROR, 410, List.of(), null));
        assertThatThrownBy(() -> service.create(doctor, PATIENT_ID, visit("2026-11-10T10:00:00Z")))
                .isInstanceOf(PatientNotFoundException.class);
        assertThatThrownBy(() -> service.create(doctor, "bad_id", visit("2026-11-10T10:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(encounters, never()).createEncounter(any());
    }

    @Test
    void fhirFailuresAreReportedNeverAsSuccess() {
        UserDto doctor = signedIn(7L, Role.DOCTOR);
        for (FhirServerException failure : List.of(
                new FhirServerException("encounter-create", FhirServerException.Type.TIMEOUT, null, List.of(), "x"),
                new FhirServerException("encounter-create", FhirServerException.Type.UNAVAILABLE, null, List.of(), "x"),
                new FhirServerException("encounter-create", FhirServerException.Type.INVALID_RESPONSE, null, List.of(),
                        null))) {
            org.mockito.Mockito.doThrow(failure).when(encounters).createEncounter(any());
            assertThatThrownBy(() -> service.create(doctor, PATIENT_ID, visit("2026-11-10T10:00:00Z"))).isSameAs(failure);
        }
        FhirServerException down = new FhirServerException("patient-read", FhirServerException.Type.UNAVAILABLE, null,
                List.of(), "x");
        org.mockito.Mockito.doThrow(down).when(patients).readPatient(PATIENT_ID);
        assertThatThrownBy(() -> service.create(doctor, PATIENT_ID, visit("2026-11-10T10:00:00Z"))).isSameAs(down);
    }

    @Test
    void aPlannedVisitMayBeInTheFuture() {
        UserDto doctor = signedIn(7L, Role.DOCTOR);
        assertThat(service.create(doctor, PATIENT_ID, new NewEncounterRequest("2027-01-15T08:00:00+01:00",
                PRACTITIONER_ID, "FOLLOW_UP", "PLANNED", null, null)).status()).isEqualTo("planned");
        // Within five minutes of now counts as now for a finished visit.
        assertThat(service.create(doctor, PATIENT_ID, visit("2026-11-11T12:04:00Z")).status()).isEqualTo("finished");
    }

    // ---------- list ----------

    private static EncounterDto found(String id, String start, String practitionerId, String typeText) {
        return new EncounterDto(id, "finished", new Coding(CreateEncounterRequest.EncounterClass.SYSTEM, null, "AMB",
                null, null), typeText == null ? List.of() : List.of(new Concept(typeText, List.of())), null, null,
                new SubjectReference("Patient/" + PATIENT_ID, null, null, null),
                practitionerId == null ? List.of() : List.of(new EncounterDto.Participant(List.of(), null,
                        new SubjectReference("Practitioner/" + practitionerId, null, null, null))),
                start == null ? null : new PeriodValue(start, null), null, List.of(), List.of(), List.of(), List.of(),
                null);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void doctorsAndStaffListTheVisitsNewestFirstWithNames(Role role) {
        UserDto clinician = signedIn(7L, role);
        when(encounters.findByPatientId(PATIENT_ID)).thenReturn(new com.medinexus.medicore.medicalrecord.dto.EncounterSearchResult(List.of(
                found("e-old", "2025-03-01", PRACTITIONER_ID, "Follow-up encounter"),
                found("e-none", null, null, null),
                found("e-new", "2026-11-10T14:30:00+01:00", PRACTITIONER_ID, "Consultation"),
                found("e-mid", "2026-11-10T09:00:00Z", "syn-unknown", null)), true, 0));
        when(practitioners.readPractitioner("syn-unknown")).thenThrow(new FhirServerException("practitioner-read",
                FhirServerException.Type.CLIENT_ERROR, 404, List.of(), null));

        List<EncounterSummaryDto> visits = service.listForPatient(clinician, PATIENT_ID).encounters();

        assertThat(visits).extracting(EncounterSummaryDto::id).containsExactly("e-new", "e-mid", "e-old", "e-none");
        assertThat(visits.getFirst().practitioners()).containsExactly("Dr Ali Khan");
        assertThat(visits.getFirst().type()).isEqualTo("Consultation");
        assertThat(visits.get(1).practitioners()).isEmpty();
        // Each Practitioner is read once per list.
        verify(practitioners, times(1)).readPractitioner(PRACTITIONER_ID);
        assertThat(visits.toString()).doesNotContain("Practitioner/", "Patient/");
    }

    @Test
    void preservesCompletenessWhenSummarizingOwnAndClinicianResults() {
        UserDto doctor = signedIn(7L, Role.DOCTOR);
        when(encounters.findByPatientId(PATIENT_ID)).thenReturn(
                new com.medinexus.medicore.medicalrecord.dto.EncounterSearchResult(List.of(), false, 3));
        var result = service.listForPatient(doctor, PATIENT_ID);
        assertThat(result.encounters()).isEmpty();
        assertThat(result.complete()).isFalse();
        assertThat(result.excludedCount()).isEqualTo(3);
        when(users.findById(9L)).thenReturn(Optional.of(account(9L, Role.PATIENT, PATIENT_ID)));
        var own = service.listOwn(new UserDto(9L, "synthetic@example.test", Role.PATIENT));
        assertThat(own).isEqualTo(result);
    }

    @Test
    void anEmptyListIsFineAndUnknownPatientsAreNotSearched() {
        UserDto doctor = signedIn(7L, Role.DOCTOR);
        when(encounters.findByPatientId(PATIENT_ID)).thenReturn(new com.medinexus.medicore.medicalrecord.dto.EncounterSearchResult(List.of(), true, 0));
        assertThat(service.listForPatient(doctor, PATIENT_ID).encounters()).isEmpty();

        when(registry.isKnown("syn-foreign")).thenReturn(false);
        assertThatThrownBy(() -> service.listForPatient(doctor, "syn-foreign"))
                .isInstanceOf(PatientNotFoundException.class);
        verify(encounters, never()).findByPatientId("syn-foreign");
    }

    @Test
    void searchFailuresIncludingRejectedCrossPatientResultsArePassedOn() {
        UserDto doctor = signedIn(7L, Role.DOCTOR);
        // The client rejects a result for another Patient as an invalid response; nothing is returned.
        FhirServerException invalid = new FhirServerException("encounter-search", FhirServerException.Type.INVALID_RESPONSE,
                null, List.of(), "DataFormatException");
        when(encounters.findByPatientId(PATIENT_ID)).thenThrow(invalid);
        assertThatThrownBy(() -> service.listForPatient(doctor, PATIENT_ID)).isSameAs(invalid);
    }

    @Test
    void patientsCannotListAnyPatientsVisitsByIdButTheirOwn() {
        when(users.findById(8L)).thenReturn(Optional.of(account(8L, Role.PATIENT, PATIENT_ID)));
        UserDto patient = new UserDto(8L, "p@example.test", Role.PATIENT);
        assertThatThrownBy(() -> service.listForPatient(patient, PATIENT_ID)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.listForPatient(patient, "syn-other")).isInstanceOf(AccessDeniedException.class);

        when(encounters.findByPatientId(PATIENT_ID)).thenReturn(new com.medinexus.medicore.medicalrecord.dto.EncounterSearchResult(List.of(found("e-1", "2026-11-10", PRACTITIONER_ID, null)), true, 0));
        assertThat(service.listOwn(patient).encounters()).extracting(EncounterSummaryDto::id).containsExactly("e-1");
        verify(encounters).findByPatientId(PATIENT_ID);
        verify(encounters, never()).findByPatientId("syn-other");
    }

    @Test
    void anUnlinkedPatientHasNoVisitsAndClinicianscannotUseTheOwnView() {
        when(users.findById(8L)).thenReturn(Optional.of(account(8L, Role.PATIENT, null)));
        assertCode(() -> service.listOwn(new UserDto(8L, "p@example.test", Role.PATIENT)), "RECORD_NOT_LINKED");
        UserDto doctor = signedIn(7L, Role.DOCTOR);
        assertThatThrownBy(() -> service.listOwn(doctor)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.listOwn(null)).isInstanceOf(AccessDeniedException.class);
        verify(encounters, never()).findByPatientId(anyString());
    }

    @Test
    void sortKeysHandlePartialAndInvalidDates() {
        assertThat(EncounterService.sortKey("2026")).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(EncounterService.sortKey("2026-11")).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(EncounterService.sortKey("2026-11-10T14:30:00+01:00")).isEqualTo(Instant.parse("2026-11-10T13:30:00Z"));
        assertThat(EncounterService.sortKey("garbage")).isNull();
        assertThat(EncounterService.sortKey(null)).isNull();
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(EncounterRequestException.class,
                failure -> assertThat(failure.code()).isEqualTo(code));
    }
}
