package com.medinexus.medicore.medicalrecord.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Enumerations.DocumentReferenceStatus;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.InstantType;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import com.medinexus.medicore.fhir.client.FhirDocumentReferenceClient;
import com.medinexus.medicore.fhir.client.FhirEncounterClient;
import com.medinexus.medicore.fhir.client.FhirPatientClient;
import com.medinexus.medicore.fhir.client.FhirPractitionerClient;
import com.medinexus.medicore.fhir.client.FhirRequestExecutor;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.fhir.mapper.ClinicalNoteMapper;
import com.medinexus.medicore.fhir.mapper.EncounterMapper;
import com.medinexus.medicore.medicalrecord.dto.ClinicalNoteDto;
import com.medinexus.medicore.medicalrecord.dto.CreateClinicalNoteRequest;
import com.medinexus.medicore.medicalrecord.dto.EncounterDto;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.PeriodValue;
import com.medinexus.medicore.patient.registry.PatientRegistry;
import com.medinexus.medicore.patient.service.PatientNotFoundException;

import ca.uhn.fhir.rest.client.api.IGenericClient;

/** US-05.01: clinical note rules with mocked repositories and FHIR clients. Fictional data only. */
@ExtendWith(OutputCaptureExtension.class)
class ClinicalNoteServiceTest {

    private static final String PATIENT_ID = "syn-patient-1";
    private static final String OTHER_PATIENT_ID = "syn-patient-2";
    private static final String ENCOUNTER_ID = "syn-enc-1";
    private static final String PRACTITIONER_ID = "syn-practitioner-1";
    private static final String BASE = "http://fhir.test/fhir";
    private static final Instant NOW = Instant.parse("2026-11-12T09:30:12.345Z");
    private static final String TEXT = "Patienten mår bättre.\nÅterbesök om två veckor.";

    private final UserRepository users = mock(UserRepository.class);
    private final PatientRegistry registry = mock(PatientRegistry.class);
    private final FhirPatientClient patients = mock(FhirPatientClient.class);
    private final FhirEncounterClient encounters = mock(FhirEncounterClient.class);
    private final FhirPractitionerClient practitioners = mock(FhirPractitionerClient.class);
    private final IGenericClient genericClient = mock(IGenericClient.class);
    private final FhirDocumentReferenceClient notes = spy(new FhirDocumentReferenceClient(genericClient,
            mock(FhirRequestExecutor.class)));
    private final ClinicalNoteService service = new ClinicalNoteService(users, new PatientAccessService(users), registry,
            patients, encounters, practitioners, notes, new ClinicalNoteMapper(), new EncounterMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private UserDto signedIn(long id, Role role, String practitionerId, String patientId) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setRole(role);
        user.setFhirPractitionerId(practitionerId);
        user.setFhirPatientId(patientId);
        when(users.findById(id)).thenReturn(Optional.of(user));
        return new UserDto(id, "user" + id + "@example.test", role);
    }

    private static Practitioner practitioner(String id, String given, String family) {
        Practitioner practitioner = new Practitioner();
        practitioner.setId(id);
        practitioner.addName().setUse(HumanName.NameUse.OFFICIAL).setFamily(family).addGiven(given).addPrefix("Dr");
        return practitioner;
    }

    private static Encounter encounter(String id, String subject) {
        Encounter encounter = new Encounter();
        encounter.setId(id);
        encounter.setStatus(Encounter.EncounterStatus.FINISHED);
        encounter.setClass_(new Coding("http://terminology.hl7.org/CodeSystem/v3-ActCode", "AMB", null));
        encounter.setSubject(new Reference(subject));
        encounter.getPeriod().setStartElement(new DateTimeType("2026-11-10T14:30:00+01:00"));
        encounter.addType().setText("Consultation");
        return encounter;
    }

    private static EncounterDto visit(String id, String start, String type) {
        return new EncounterDto(id, "finished", null, List.of(new Concept(type, List.of())), null, null, null,
                List.of(), new PeriodValue(start, null), null, List.of(), List.of(), List.of(), List.of(), null);
    }

    private static DocumentReference stored(String id, String date, String encounterId, String practitionerId,
            String text) {
        DocumentReference note = new ClinicalNoteMapper().toResource(PATIENT_ID, encounterId, practitionerId, text,
                Instant.parse(date));
        note.setId(id);
        return note;
    }

    @BeforeEach
    void aRegisteredPatientWithAVisit() {
        when(genericClient.getServerBase()).thenReturn(BASE);
        when(registry.isKnown(PATIENT_ID)).thenReturn(true);
        Patient patient = new Patient();
        patient.setId(PATIENT_ID);
        when(patients.readPatient(PATIENT_ID)).thenReturn(patient);
        when(encounters.readEncounter(ENCOUNTER_ID)).thenReturn(encounter(ENCOUNTER_ID, "Patient/" + PATIENT_ID));
        when(practitioners.readPractitioner(PRACTITIONER_ID)).thenReturn(practitioner(PRACTITIONER_ID, "Ali", "Khan"));
        doReturn("note-1").when(notes).createDocumentReference(any());
    }

    private DocumentReference sent() {
        ArgumentCaptor<DocumentReference> sent = ArgumentCaptor.forClass(DocumentReference.class);
        verify(notes).createDocumentReference(sent.capture());
        return sent.getValue();
    }

    // ---------- create ----------

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void doctorsAndStaffWithAPractitionerCreateANoteOnTheVisit(Role role, CapturedOutput output) {
        UserDto clinician = signedIn(7L, role, PRACTITIONER_ID, null);

        ClinicalNoteDto created = service.create(clinician, PATIENT_ID,
                new CreateClinicalNoteRequest(ENCOUNTER_ID, "  " + TEXT.replace("\n", "\r\n") + "\n\n"));

        DocumentReference note = sent();
        assertThat(note.getSubject().getReference()).isEqualTo("Patient/" + PATIENT_ID);
        assertThat(note.getContext().getEncounterFirstRep().getReference()).isEqualTo("Encounter/" + ENCOUNTER_ID);
        assertThat(note.getAuthorFirstRep().getReference()).isEqualTo("Practitioner/" + PRACTITIONER_ID);
        assertThat(note.getStatus()).isEqualTo(DocumentReferenceStatus.CURRENT);
        assertThat(note.getDateElement().getValueAsString()).isEqualTo("2026-11-12T09:30:12+00:00");
        assertThat(new String(note.getContentFirstRep().getAttachment().getData(), StandardCharsets.UTF_8))
                .isEqualTo(TEXT);

        assertThat(created).isEqualTo(new ClinicalNoteDto("note-1", PATIENT_ID,
                new ClinicalNoteDto.EncounterContext(ENCOUNTER_ID, "2026-11-10T14:30:00+01:00", "Consultation"),
                "Dr Ali Khan", "2026-11-12T09:30:12+00:00", "current", "Clinical note", TEXT, true));
        assertThat(output.getAll()).contains("Clinical note created: accountId=7")
                .doesNotContain(PATIENT_ID, "mår", ENCOUNTER_ID);
    }

    @Test
    void theAuthorIsAlwaysTheSignedInCliniciansOwnPractitioner() {
        UserDto staff = signedIn(8L, Role.STAFF, "syn-practitioner-2", null);
        when(practitioners.readPractitioner("syn-practitioner-2")).thenReturn(practitioner("syn-practitioner-2", "Sara", "Lind"));

        assertThat(service.create(staff, PATIENT_ID, new CreateClinicalNoteRequest(ENCOUNTER_ID, TEXT)).author())
                .isEqualTo("Dr Sara Lind");
        assertThat(sent().getAuthorFirstRep().getReference()).isEqualTo("Practitioner/syn-practitioner-2");
    }

    @Test
    void patientsAndUnknownAccountsCannotCreateNotes() {
        UserDto patient = signedIn(9L, Role.PATIENT, null, PATIENT_ID);
        CreateClinicalNoteRequest request = new CreateClinicalNoteRequest(ENCOUNTER_ID, TEXT);
        assertThatThrownBy(() -> service.create(patient, PATIENT_ID, request)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.create(new UserDto(99L, "x@example.test", Role.DOCTOR), PATIENT_ID, request))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.create(null, PATIENT_ID, request)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(registry, patients, encounters, practitioners);
        verify(notes, never()).createDocumentReference(any());
    }

    @Test
    void aClinicianWithoutALinkedPractitionerCannotAuthorNotes() {
        UserDto staff = signedIn(8L, Role.STAFF, null, null);
        assertThatThrownBy(() -> service.create(staff, PATIENT_ID, new CreateClinicalNoteRequest(ENCOUNTER_ID, TEXT)))
                .isInstanceOfSatisfying(ClinicalNoteException.class, rejected -> {
                    assertThat(rejected.code()).isEqualTo("PRACTITIONER_NOT_LINKED");
                    assertThat(rejected.status()).isEqualTo(422);
                });
        verifyNoInteractions(patients, encounters, practitioners);
        verify(notes, never()).createDocumentReference(any());
    }

    @Test
    void theLinkedPractitionerMustExistInFhir() {
        UserDto doctor = signedIn(7L, Role.DOCTOR, PRACTITIONER_ID, null);
        when(practitioners.readPractitioner(PRACTITIONER_ID)).thenThrow(missing("practitioner-read"));
        assertThatThrownBy(() -> service.create(doctor, PATIENT_ID, new CreateClinicalNoteRequest(ENCOUNTER_ID, TEXT)))
                .isInstanceOfSatisfying(ClinicalNoteException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo("PRACTITIONER_NOT_FOUND"));
        verify(notes, never()).createDocumentReference(any());
    }

    @Test
    void theVisitMustExistAndBelongToThePatient() {
        UserDto doctor = signedIn(7L, Role.DOCTOR, PRACTITIONER_ID, null);
        when(encounters.readEncounter("syn-enc-other")).thenReturn(encounter("syn-enc-other", "Patient/" + OTHER_PATIENT_ID));
        when(encounters.readEncounter("syn-enc-group")).thenReturn(encounter("syn-enc-group", "Group/" + PATIENT_ID));
        when(encounters.readEncounter("syn-enc-gone")).thenThrow(missing("encounter-read"));
        when(encounters.readEncounter("syn-enc-abs")).thenReturn(encounter("syn-enc-abs", BASE + "/Patient/" + PATIENT_ID));

        for (String[] c : List.of(new String[] {"syn-enc-other", "ENCOUNTER_NOT_FOR_PATIENT"},
                new String[] {"syn-enc-group", "ENCOUNTER_NOT_FOR_PATIENT"},
                new String[] {"syn-enc-gone", "ENCOUNTER_NOT_FOUND"},
                new String[] {null, "ENCOUNTER_REQUIRED"},
                new String[] {"Encounter/x", "ENCOUNTER_INVALID"},
                new String[] {"bad_id", "ENCOUNTER_INVALID"})) {
            assertThatThrownBy(() -> service.create(doctor, PATIENT_ID, new CreateClinicalNoteRequest(c[0], TEXT)))
                    .as(String.valueOf(c[0])).isInstanceOfSatisfying(ClinicalNoteException.class,
                            rejected -> assertThat(rejected.code()).isEqualTo(c[1]));
        }
        verify(notes, never()).createDocumentReference(any());
        // An absolute reference to this Patient on the configured server is the same Patient.
        assertThat(service.create(doctor, PATIENT_ID, new CreateClinicalNoteRequest("syn-enc-abs", TEXT)).id())
                .isEqualTo("note-1");
        // No visit is ever created for a note.
        verify(encounters, never()).createEncounter(any());
    }

    @Test
    void thePatientMustBeKnownToMediCoreAndExist() {
        UserDto doctor = signedIn(7L, Role.DOCTOR, PRACTITIONER_ID, null);
        CreateClinicalNoteRequest request = new CreateClinicalNoteRequest(ENCOUNTER_ID, TEXT);
        assertThatThrownBy(() -> service.create(doctor, "syn-unregistered", request))
                .isInstanceOf(PatientNotFoundException.class);
        when(registry.isKnown("syn-deleted")).thenReturn(true);
        when(patients.readPatient("syn-deleted")).thenThrow(missing("patient-read"));
        assertThatThrownBy(() -> service.create(doctor, "syn-deleted", request)).isInstanceOf(PatientNotFoundException.class);
        for (String id : new String[] {null, "Patient/x", "bad_id", "x".repeat(65)}) {
            assertThatThrownBy(() -> service.create(doctor, id, request)).isInstanceOf(IllegalArgumentException.class);
        }
        verify(notes, never()).createDocumentReference(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\n\n", " \t \r\n "})
    void emptyAndWhitespaceOnlyNotesAreRejected(String content) {
        UserDto doctor = signedIn(7L, Role.DOCTOR, PRACTITIONER_ID, null);
        assertThatThrownBy(() -> service.create(doctor, PATIENT_ID, new CreateClinicalNoteRequest(ENCOUNTER_ID, content)))
                .isInstanceOfSatisfying(ClinicalNoteException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo("CONTENT_REQUIRED"));
        assertThatThrownBy(() -> service.create(doctor, PATIENT_ID, new CreateClinicalNoteRequest(ENCOUNTER_ID, null)))
                .isInstanceOfSatisfying(ClinicalNoteException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo("CONTENT_REQUIRED"));
        verifyNoInteractions(patients, encounters);
    }

    @Test
    void theLengthLimitCountsCharactersAndOversizedNotesAreRejected() {
        assertThat(ClinicalNoteService.normalizedContent("a".repeat(10_000))).hasSize(10_000);
        // 10,000 emoji are 10,000 characters (20,000 UTF-16 units).
        assertThat(ClinicalNoteService.normalizedContent("🙂".repeat(10_000)).codePointCount(0, 20_000)).isEqualTo(10_000);
        // Surrounding whitespace does not count.
        assertThat(ClinicalNoteService.normalizedContent("\n " + "ö".repeat(10_000) + " \n")).hasSize(10_000);
        UserDto doctor = signedIn(7L, Role.DOCTOR, PRACTITIONER_ID, null);
        assertThatThrownBy(() -> service.create(doctor, PATIENT_ID,
                new CreateClinicalNoteRequest(ENCOUNTER_ID, "å".repeat(10_001))))
                .isInstanceOfSatisfying(ClinicalNoteException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo("CONTENT_TOO_LONG"));
        verify(notes, never()).createDocumentReference(any());
    }

    @Test
    void unicodeAndLineBreaksArePreservedAndControlCharactersRejected() {
        assertThat(ClinicalNoteService.normalizedContent("Rad 1\r\nRad 2\rRad 3\n\n\tIndrag ÅÄÖ åäö é ü 🙂 <script>"))
                .isEqualTo("Rad 1\nRad 2\nRad 3\n\n\tIndrag ÅÄÖ åäö é ü 🙂 <script>");
        for (String invalid : new String[] {"a\u0000b", "a\u0007b", "a\u007Fb", "a\u0085b", "a\uD800b"}) {
            assertThatThrownBy(() -> ClinicalNoteService.normalizedContent(invalid))
                    .isInstanceOfSatisfying(ClinicalNoteException.class,
                            rejected -> assertThat(rejected.code()).isEqualTo("CONTENT_INVALID"));
        }
    }

    @Test
    void fhirFailuresPropagateAndAreNeverRetried() {
        UserDto doctor = signedIn(7L, Role.DOCTOR, PRACTITIONER_ID, null);
        FhirServerException timeout = new FhirServerException("document-reference-create",
                FhirServerException.Type.TIMEOUT, null, List.of(), null);
        doThrow(timeout).when(notes).createDocumentReference(any());
        assertThatThrownBy(() -> service.create(doctor, PATIENT_ID, new CreateClinicalNoteRequest(ENCOUNTER_ID, TEXT)))
                .isSameAs(timeout);
        verify(notes, times(1)).createDocumentReference(any());

        FhirServerException down = new FhirServerException("encounter-read", FhirServerException.Type.UNAVAILABLE, 503,
                List.of(), null);
        when(encounters.readEncounter(ENCOUNTER_ID)).thenThrow(down);
        assertThatThrownBy(() -> service.create(doctor, PATIENT_ID, new CreateClinicalNoteRequest(ENCOUNTER_ID, TEXT)))
                .isSameAs(down);
    }

    // ---------- read ----------

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void cliniciansReadNotesNewestFirstWithVisitAndAuthor(Role role, CapturedOutput output) {
        UserDto clinician = signedIn(7L, role, null, null);
        DocumentReference older = stored("n-old", "2026-11-01T08:00:00Z", ENCOUNTER_ID, PRACTITIONER_ID, "Första");
        DocumentReference newer = stored("n-new", "2026-11-11T08:00:00Z", "syn-enc-2", PRACTITIONER_ID, "Andra\nrad");
        DocumentReference error = stored("n-error", "2026-11-12T08:00:00Z", ENCOUNTER_ID, PRACTITIONER_ID, "Fel");
        error.setStatus(DocumentReferenceStatus.ENTEREDINERROR);
        DocumentReference foreignVisit = stored("n-foreign", "2026-11-05T08:00:00Z", "syn-enc-of-other", "syn-gone", "Tredje");
        foreignVisit.getContentFirstRep().setAttachment(new Attachment().setContentType("application/pdf")
                .setUrl("http://evil.example/x.pdf"));
        doReturn(List.of(older, error, foreignVisit, newer)).when(notes).findByPatientId(PATIENT_ID);
        when(encounters.findByPatientId(PATIENT_ID)).thenReturn(List.of(
                visit(ENCOUNTER_ID, "2026-11-01T07:30:00+01:00", "Consultation"),
                visit("syn-enc-2", "2026-11-11", "Follow-up encounter")));
        when(practitioners.readPractitioner("syn-gone")).thenThrow(missing("practitioner-read"));

        List<ClinicalNoteDto> listed = service.listForPatient(clinician, PATIENT_ID);

        assertThat(listed).extracting(ClinicalNoteDto::id).containsExactly("n-new", "n-foreign", "n-old");
        assertThat(listed.getFirst()).isEqualTo(new ClinicalNoteDto("n-new", PATIENT_ID,
                new ClinicalNoteDto.EncounterContext("syn-enc-2", "2026-11-11", "Follow-up encounter"), "Dr Ali Khan",
                "2026-11-11T08:00:00+00:00", "current", "Clinical note", "Andra\nrad", true));
        // A visit that is not one of this Patient's visits is not shown; unreadable content is never fetched.
        assertThat(listed.get(1).encounter()).isNull();
        assertThat(listed.get(1).author()).isNull();
        assertThat(listed.get(1).content()).isNull();
        assertThat(listed.get(1).contentAvailable()).isFalse();
        assertThat(listed.get(2).encounter().type()).isEqualTo("Consultation");
        // One read per author.
        verify(practitioners, times(1)).readPractitioner(PRACTITIONER_ID);
        assertThat(output.getAll()).contains("Clinical notes listed: accountId=7, count=3")
                .doesNotContain("Första", PATIENT_ID);
    }

    @Test
    void notesWithoutDateAreLastAndAnEmptyListNeedsNoVisitSearch() {
        UserDto doctor = signedIn(7L, Role.DOCTOR, null, null);
        doReturn(List.of()).when(notes).findByPatientId(PATIENT_ID);
        assertThat(service.listForPatient(doctor, PATIENT_ID)).isEmpty();
        verifyNoInteractions(encounters);

        DocumentReference undated = stored("n-a", "2026-11-01T08:00:00Z", ENCOUNTER_ID, PRACTITIONER_ID, "x");
        undated.setDateElement(null);
        DocumentReference lastUpdated = stored("n-b", "2026-11-01T08:00:00Z", ENCOUNTER_ID, PRACTITIONER_ID, "y");
        lastUpdated.setDateElement(null);
        lastUpdated.getMeta().setLastUpdatedElement(new InstantType("2026-10-01T00:00:00Z"));
        DocumentReference dated = stored("n-c", "2026-09-01T08:00:00Z", ENCOUNTER_ID, PRACTITIONER_ID, "z");
        doReturn(List.of(undated, dated, lastUpdated)).when(notes).findByPatientId(PATIENT_ID);
        // The visits cannot be read: the notes are still shown, without visit details.
        when(encounters.findByPatientId(PATIENT_ID)).thenThrow(new FhirServerException("encounter-search",
                FhirServerException.Type.UNAVAILABLE, 503, List.of(), null));
        List<ClinicalNoteDto> listed = service.listForPatient(doctor, PATIENT_ID);
        assertThat(listed).extracting(ClinicalNoteDto::id).containsExactly("n-b", "n-c", "n-a");
        assertThat(listed).allSatisfy(note -> assertThat(note.encounter()).isNull());
    }

    @Test
    void cliniciansReadOnlyPatientsKnownToMediCoreAndPatientsNeverByIdentifier() {
        UserDto doctor = signedIn(7L, Role.DOCTOR, null, null);
        assertThatThrownBy(() -> service.listForPatient(doctor, "syn-unregistered")).isInstanceOf(PatientNotFoundException.class);
        UserDto patient = signedIn(9L, Role.PATIENT, null, PATIENT_ID);
        assertThatThrownBy(() -> service.listForPatient(patient, PATIENT_ID)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.listForPatient(patient, OTHER_PATIENT_ID)).isInstanceOf(AccessDeniedException.class);
        verify(notes, never()).findByPatientId(anyString());
    }

    @Test
    void patientsReadOnlyTheirOwnApprovedRecord(CapturedOutput output) {
        UserDto patient = signedIn(9L, Role.PATIENT, null, PATIENT_ID);
        doReturn(List.of(stored("n-1", "2026-11-01T08:00:00Z", ENCOUNTER_ID, PRACTITIONER_ID, "Hej"))).when(notes)
                .findByPatientId(PATIENT_ID);
        when(encounters.findByPatientId(PATIENT_ID)).thenReturn(List.of());

        assertThat(service.listOwn(patient)).singleElement()
                .satisfies(note -> assertThat(note.content()).isEqualTo("Hej"));
        verify(notes).findByPatientId(PATIENT_ID);
        assertThat(output.getAll()).contains("Own clinical notes listed: accountId=9, count=1");

        UserDto unlinked = signedIn(10L, Role.PATIENT, null, null);
        assertThatThrownBy(() -> service.listOwn(unlinked)).isInstanceOfSatisfying(ClinicalNoteException.class,
                rejected -> {
                    assertThat(rejected.code()).isEqualTo("RECORD_NOT_LINKED");
                    assertThat(rejected.status()).isEqualTo(404);
                });
        UserDto doctor = signedIn(7L, Role.DOCTOR, PRACTITIONER_ID, null);
        assertThatThrownBy(() -> service.listOwn(doctor)).isInstanceOf(AccessDeniedException.class);
        verify(notes, times(1)).findByPatientId(anyString());
    }

    private static FhirServerException missing(String operation) {
        return new FhirServerException(operation, FhirServerException.Type.CLIENT_ERROR, 404, List.of(), null);
    }
}
