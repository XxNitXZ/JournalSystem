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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
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
import com.medinexus.medicore.fhir.client.FhirConditionClient;
import com.medinexus.medicore.fhir.client.FhirEncounterClient;
import com.medinexus.medicore.fhir.client.FhirPatientClient;
import com.medinexus.medicore.fhir.client.FhirPractitionerClient;
import com.medinexus.medicore.fhir.client.FhirReferences;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.fhir.mapper.EncounterMapper;
import com.medinexus.medicore.medicalrecord.dto.ClinicalNoteDto.EncounterContext;
import com.medinexus.medicore.medicalrecord.dto.ConditionDto;
import com.medinexus.medicore.medicalrecord.dto.ConditionDto.TemporalType;
import com.medinexus.medicore.medicalrecord.dto.ConditionDto.TemporalValue;
import com.medinexus.medicore.medicalrecord.dto.CreateConditionRequest;
import com.medinexus.medicore.medicalrecord.dto.CreateConditionRequest.ClinicalStatus;
import com.medinexus.medicore.medicalrecord.dto.CreateConditionRequest.VerificationStatus;
import com.medinexus.medicore.medicalrecord.dto.DiagnosisDto;
import com.medinexus.medicore.medicalrecord.dto.EncounterDto;
import com.medinexus.medicore.medicalrecord.dto.NewDiagnosisRequest;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Coding;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.PeriodValue;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.QuantityValue;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.SubjectReference;
import com.medinexus.medicore.patient.registry.PatientRegistry;
import com.medinexus.medicore.patient.service.PatientNotFoundException;

import ca.uhn.fhir.rest.client.api.IGenericClient;

/** US-05.02: diagnosis rules with mocked repositories and FHIR clients. Fictional data only. */
@ExtendWith(OutputCaptureExtension.class)
class DiagnosisServiceTest {

    private static final String PATIENT_ID = "syn-patient-1";
    private static final String OTHER_PATIENT_ID = "syn-patient-2";
    private static final String ENCOUNTER_ID = "syn-enc-1";
    private static final String PRACTITIONER_ID = "syn-practitioner-1";
    private static final String BASE = "http://fhir.test/fhir";
    private static final Instant NOW = Instant.parse("2026-11-12T09:30:12.345Z");
    private static final String CATEGORY = "http://terminology.hl7.org/CodeSystem/condition-category";

    private final UserRepository users = mock(UserRepository.class);
    private final PatientRegistry registry = mock(PatientRegistry.class);
    private final FhirPatientClient patients = mock(FhirPatientClient.class);
    private final FhirEncounterClient encounters = mock(FhirEncounterClient.class);
    private final FhirPractitionerClient practitioners = mock(FhirPractitionerClient.class);
    private final FhirConditionClient conditions = mock(FhirConditionClient.class);
    private final IGenericClient genericClient = mock(IGenericClient.class);
    private final DiagnosisService service = new DiagnosisService(users, new PatientAccessService(users), registry,
            patients, encounters, practitioners, conditions, new FhirReferences(genericClient), new EncounterMapper(),
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
        encounter.setClass_(new org.hl7.fhir.r4.model.Coding("http://terminology.hl7.org/CodeSystem/v3-ActCode", "AMB", null));
        encounter.setSubject(new Reference(subject));
        encounter.getPeriod().setStartElement(new DateTimeType("2026-11-10T14:30:00+01:00"));
        encounter.addType().setText("Consultation");
        return encounter;
    }

    private static NewDiagnosisRequest request(String diagnosis) {
        return new NewDiagnosisRequest(diagnosis, null, null, null, null, null, null);
    }

    private static Concept status(String system, String code) {
        return new Concept(null, List.of(new Coding(system, null, code, null, null)));
    }

    private static ConditionDto stored(String id, String text, String recordedDate, String encounter, String recorder) {
        return new ConditionDto(id, new SubjectReference("Patient/" + PATIENT_ID, null, null, null),
                new Concept(text, List.of()), status(ClinicalStatus.SYSTEM, "active"),
                status(VerificationStatus.SYSTEM, "confirmed"), List.of(status(CATEGORY, "problem-list-item")), null,
                null, null, recordedDate,
                encounter == null ? null : new SubjectReference(encounter, null, null, null),
                recorder == null ? null : new SubjectReference(recorder, null, null, null));
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
        when(conditions.createCondition(any())).thenReturn("cond-1");
    }

    private CreateConditionRequest sent() {
        ArgumentCaptor<CreateConditionRequest> sent = ArgumentCaptor.forClass(CreateConditionRequest.class);
        verify(conditions).createCondition(sent.capture());
        return sent.getValue();
    }

    // ---------- create ----------

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void doctorsAndStaffRecordAConfirmedCodedDiagnosisOnAVisit(Role role, CapturedOutput output) {
        UserDto clinician = signedIn(7L, role, PRACTITIONER_ID, null);

        DiagnosisDto created = service.create(clinician, PATIENT_ID, new NewDiagnosisRequest("Typ 2-diabetes",
                "44054006", "SNOMED_CT", null, null, "2026-10-01", ENCOUNTER_ID));

        CreateConditionRequest condition = sent();
        assertThat(condition.patientId()).isEqualTo(PATIENT_ID);
        assertThat(condition.code()).isEqualTo(new Concept("Typ 2-diabetes",
                List.of(new Coding("http://snomed.info/sct", null, "44054006", null, null))));
        assertThat(condition.clinicalStatus()).isEqualTo(ClinicalStatus.ACTIVE);
        assertThat(condition.verificationStatus()).isEqualTo(VerificationStatus.CONFIRMED);
        assertThat(condition.category()).singleElement()
                .satisfies(category -> assertThat(category.coding().getFirst().code()).isEqualTo("encounter-diagnosis"));
        assertThat(condition.recordedDate()).isEqualTo("2026-11-12T09:30:12Z");
        assertThat(condition.onsetDateTime()).isEqualTo("2026-10-01");
        assertThat(condition.encounterId()).isEqualTo(ENCOUNTER_ID);
        assertThat(condition.recorderId()).isEqualTo(PRACTITIONER_ID);

        assertThat(created).isEqualTo(new DiagnosisDto("cond-1", PATIENT_ID, "Typ 2-diabetes",
                new DiagnosisDto.Code("http://snomed.info/sct", "SNOMED CT", "44054006", null), "active", "confirmed",
                "encounter-diagnosis", "2026-11-12T09:30:12Z", "2026-10-01",
                new EncounterContext(ENCOUNTER_ID, "2026-11-10T14:30:00+01:00", "Consultation"), "Dr Ali Khan"));
        assertThat(output.getAll()).contains("Diagnosis recorded: accountId=7")
                .doesNotContain("diabetes", "44054006", PATIENT_ID);
    }

    @Test
    void aTextOnlyDiagnosisHasNoInventedCodeAndNoVisit() {
        UserDto doctor = signedIn(7L, Role.DOCTOR, PRACTITIONER_ID, null);
        DiagnosisDto created = service.create(doctor, PATIENT_ID, new NewDiagnosisRequest("  Misstänkt migrän ",
                null, null, "RECURRENCE", "PROVISIONAL", null, null));
        CreateConditionRequest condition = sent();
        assertThat(condition.code()).isEqualTo(new Concept("Misstänkt migrän", List.of()));
        assertThat(condition.clinicalStatus()).isEqualTo(ClinicalStatus.RECURRENCE);
        assertThat(condition.verificationStatus()).isEqualTo(VerificationStatus.PROVISIONAL);
        assertThat(condition.category().getFirst().coding().getFirst().code()).isEqualTo("problem-list-item");
        assertThat(condition.encounterId()).isNull();
        assertThat(condition.onsetDateTime()).isNull();
        assertThat(created.code()).isNull();
        assertThat(created.encounter()).isNull();
        verify(encounters, never()).readEncounter(anyString());
        // ICD-10 codes are stored in upper case.
        service.create(doctor, PATIENT_ID, new NewDiagnosisRequest("Astma", "j45", "ICD_10", null, null, null, null));
        ArgumentCaptor<CreateConditionRequest> all = ArgumentCaptor.forClass(CreateConditionRequest.class);
        verify(conditions, times(2)).createCondition(all.capture());
        assertThat(all.getValue().code().coding().getFirst().code()).isEqualTo("J45");
        assertThat(all.getValue().code().coding().getFirst().system()).isEqualTo("http://hl7.org/fhir/sid/icd-10");
    }

    @Test
    void patientsAndUnknownAccountsCannotRecordDiagnoses() {
        UserDto patient = signedIn(9L, Role.PATIENT, null, PATIENT_ID);
        assertThatThrownBy(() -> service.create(patient, PATIENT_ID, request("Astma"))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.create(new UserDto(99L, "x@example.test", Role.DOCTOR), PATIENT_ID, request("Astma")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.create(null, PATIENT_ID, request("Astma"))).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(registry, patients, encounters, practitioners, conditions);
    }

    @Test
    void invalidDiagnosesAreRejectedBeforeAnyFhirRequest() {
        UserDto doctor = signedIn(7L, Role.DOCTOR, PRACTITIONER_ID, null);
        record Case(NewDiagnosisRequest request, String code) {
        }
        for (Case c : List.of(
                new Case(request(null), "DIAGNOSIS_REQUIRED"),
                new Case(request("   "), "DIAGNOSIS_REQUIRED"),
                new Case(request("x".repeat(251)), "DIAGNOSIS_TOO_LONG"),
                new Case(request("Rad 1\nRad 2"), "DIAGNOSIS_INVALID"),
                new Case(new NewDiagnosisRequest("Astma", "J45", null, null, null, null, null), "CODE_SYSTEM_REQUIRED"),
                new Case(new NewDiagnosisRequest("Astma", null, "ICD_10", null, null, null, null), "CODE_REQUIRED"),
                new Case(new NewDiagnosisRequest("Astma", "J45", "LOCAL", null, null, null, null), "CODE_SYSTEM_INVALID"),
                new Case(new NewDiagnosisRequest("Astma", "44054007", "SNOMED_CT", null, null, null, null), "CODE_INVALID"),
                new Case(new NewDiagnosisRequest("Astma", "J4", "ICD_10", null, null, null, null), "CODE_INVALID"),
                new Case(new NewDiagnosisRequest("Astma", null, null, "active", null, null, null), "CLINICAL_STATUS_INVALID"),
                new Case(new NewDiagnosisRequest("Astma", null, null, "CURED", null, null, null), "CLINICAL_STATUS_INVALID"),
                new Case(new NewDiagnosisRequest("Astma", null, null, null, "REFUTED", null, null), "VERIFICATION_STATUS_INVALID"),
                new Case(new NewDiagnosisRequest("Astma", null, null, null, "ENTERED_IN_ERROR", null, null),
                        "VERIFICATION_STATUS_INVALID"),
                new Case(new NewDiagnosisRequest("Astma", null, null, null, null, "2026-02-30", null), "ONSET_INVALID"),
                new Case(new NewDiagnosisRequest("Astma", null, null, null, null, "2026-11", null), "ONSET_INVALID"),
                new Case(new NewDiagnosisRequest("Astma", null, null, null, null, "1850-01-01", null), "ONSET_INVALID"),
                new Case(new NewDiagnosisRequest("Astma", null, null, null, null, "2026-11-14", null), "ONSET_IN_FUTURE"),
                new Case(new NewDiagnosisRequest("Astma", null, null, null, null, null, "Encounter/1"), "ENCOUNTER_INVALID"))) {
            assertThatThrownBy(() -> service.create(doctor, PATIENT_ID, c.request()))
                    .as(c.code()).isInstanceOfSatisfying(DiagnosisRequestException.class, rejected -> {
                        assertThat(rejected.code()).isEqualTo(c.code());
                        assertThat(rejected.status()).isEqualTo(400);
                    });
        }
        // Today in the latest time zone is not in the future.
        assertThat(service.validate(new NewDiagnosisRequest("Astma", null, null, null, null, "2026-11-12", null))
                .onsetDate()).isEqualTo("2026-11-12");
        verifyNoInteractions(patients, encounters, conditions);
    }

    @Test
    void theVisitMustExistAndBelongToThePatient() {
        UserDto doctor = signedIn(7L, Role.DOCTOR, PRACTITIONER_ID, null);
        when(encounters.readEncounter("syn-enc-other")).thenReturn(encounter("syn-enc-other", "Patient/" + OTHER_PATIENT_ID));
        when(encounters.readEncounter("syn-enc-gone")).thenThrow(missing("encounter-read"));
        for (String[] c : List.of(new String[] {"syn-enc-other", "ENCOUNTER_NOT_FOR_PATIENT"},
                new String[] {"syn-enc-gone", "ENCOUNTER_NOT_FOUND"})) {
            assertThatThrownBy(() -> service.create(doctor, PATIENT_ID,
                    new NewDiagnosisRequest("Astma", null, null, null, null, null, c[0])))
                    .isInstanceOfSatisfying(DiagnosisRequestException.class, rejected -> {
                        assertThat(rejected.code()).isEqualTo(c[1]);
                        assertThat(rejected.status()).isEqualTo(422);
                    });
        }
        verify(conditions, never()).createCondition(any());
        verify(encounters, never()).createEncounter(any());
    }

    @Test
    void theRecorderIsTheCliniciansOwnLinkedPractitioner() {
        UserDto unlinked = signedIn(8L, Role.STAFF, null, null);
        assertThatThrownBy(() -> service.create(unlinked, PATIENT_ID, request("Astma")))
                .isInstanceOfSatisfying(DiagnosisRequestException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo("PRACTITIONER_NOT_LINKED"));
        UserDto gone = signedIn(9L, Role.DOCTOR, "syn-practitioner-gone", null);
        when(practitioners.readPractitioner("syn-practitioner-gone")).thenThrow(missing("practitioner-read"));
        assertThatThrownBy(() -> service.create(gone, PATIENT_ID, request("Astma")))
                .isInstanceOfSatisfying(DiagnosisRequestException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo("PRACTITIONER_NOT_FOUND"));
        verify(conditions, never()).createCondition(any());
    }

    @Test
    void thePatientMustBeKnownToMediCoreAndExist() {
        UserDto doctor = signedIn(7L, Role.DOCTOR, PRACTITIONER_ID, null);
        assertThatThrownBy(() -> service.create(doctor, "syn-unregistered", request("Astma")))
                .isInstanceOf(PatientNotFoundException.class);
        when(registry.isKnown("syn-deleted")).thenReturn(true);
        when(patients.readPatient("syn-deleted")).thenThrow(missing("patient-read"));
        assertThatThrownBy(() -> service.create(doctor, "syn-deleted", request("Astma"))).isInstanceOf(PatientNotFoundException.class);
        for (String id : new String[] {null, "Patient/x", "bad_id"}) {
            assertThatThrownBy(() -> service.create(doctor, id, request("Astma"))).isInstanceOf(IllegalArgumentException.class);
        }
        verify(conditions, never()).createCondition(any());
    }

    @Test
    void fhirFailuresPropagateAndAreNeverRetried() {
        UserDto doctor = signedIn(7L, Role.DOCTOR, PRACTITIONER_ID, null);
        FhirServerException timeout = new FhirServerException("condition-create", FhirServerException.Type.TIMEOUT,
                null, List.of(), null);
        when(conditions.createCondition(any())).thenThrow(timeout);
        assertThatThrownBy(() -> service.create(doctor, PATIENT_ID, request("Astma"))).isSameAs(timeout);
        verify(conditions, times(1)).createCondition(any());
    }

    // ---------- read ----------

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void cliniciansReadAllConditionsNewestFirstIncludingOnesNotCreatedByMediCore(Role role, CapturedOutput output) {
        UserDto clinician = signedIn(7L, role, null, null);
        ConditionDto older = stored("c-old", "Astma", "2026-01-01T08:00:00Z", "Encounter/" + ENCOUNTER_ID,
                "Practitioner/" + PRACTITIONER_ID);
        ConditionDto newer = stored("c-new", "Hypertoni", "2026-11-11", null, "Practitioner/" + PRACTITIONER_ID);
        ConditionDto error = new ConditionDto("c-error", null, new Concept("Fel", List.of()), null,
                status(VerificationStatus.SYSTEM, "entered-in-error"), List.of(), null, null, null);
        // Created elsewhere (e.g. on an imported Patient): coding only, no statuses, onset age, foreign visit.
        ConditionDto imported = new ConditionDto("c-imported", null, new Concept(null, List.of(
                new Coding("http://snomed.info/sct", null, "195967001", "Asthma", null))), null, null, List.of(), null,
                new TemporalValue(TemporalType.AGE, new ConditionDto.AgeValue(new QuantityValue(new BigDecimal("45"),
                        null, "years", "http://unitsofmeasure.org", "a"))), null, null,
                new SubjectReference("Encounter/syn-enc-of-other", null, null, null),
                new SubjectReference("Practitioner/syn-gone", null, null, null));
        when(conditions.findByPatientId(PATIENT_ID)).thenReturn(List.of(older, error, imported, newer));
        when(encounters.findByPatientId(PATIENT_ID)).thenReturn(new com.medinexus.medicore.medicalrecord.dto.EncounterSearchResult(List.of(new EncounterDto(ENCOUNTER_ID, "finished",
                null, List.of(new Concept("Consultation", List.of())), null, null, null, List.of(),
                new PeriodValue("2026-01-01T07:30:00+01:00", null), null, List.of(), List.of(), List.of(), List.of(), null)), false, 1));
        when(practitioners.readPractitioner("syn-gone")).thenThrow(missing("practitioner-read"));

        List<DiagnosisDto> listed = service.listForPatient(clinician, PATIENT_ID);

        assertThat(listed).extracting(DiagnosisDto::id).containsExactly("c-new", "c-old", "c-imported");
        assertThat(listed.get(1)).isEqualTo(new DiagnosisDto("c-old", PATIENT_ID, "Astma", null, "active", "confirmed",
                "problem-list-item", "2026-01-01T08:00:00Z", null,
                new EncounterContext(ENCOUNTER_ID, "2026-01-01T07:30:00+01:00", "Consultation"), "Dr Ali Khan"));
        assertThat(listed.get(2)).isEqualTo(new DiagnosisDto("c-imported", PATIENT_ID, "Asthma",
                new DiagnosisDto.Code("http://snomed.info/sct", "SNOMED CT", "195967001", "Asthma"), null, null, null,
                null, "Age 45 years", null, null));
        verify(practitioners, times(1)).readPractitioner(PRACTITIONER_ID);
        assertThat(output.getAll()).contains("Diagnoses listed: accountId=7, count=3").doesNotContain("Astma", PATIENT_ID);
    }

    @Test
    void emptyListsNeedNoVisitSearchAndFhirOutagesPropagate() {
        UserDto doctor = signedIn(7L, Role.DOCTOR, null, null);
        when(conditions.findByPatientId(PATIENT_ID)).thenReturn(List.of());
        assertThat(service.listForPatient(doctor, PATIENT_ID)).isEmpty();
        verifyNoInteractions(encounters);

        FhirServerException down = new FhirServerException("condition-search", FhirServerException.Type.UNAVAILABLE,
                503, List.of(), null);
        when(conditions.findByPatientId(PATIENT_ID)).thenThrow(down);
        assertThatThrownBy(() -> service.listForPatient(doctor, PATIENT_ID)).isSameAs(down);
    }

    @Test
    void visitDetailsAreOptionalWhenTheEncounterSearchFails() {
        UserDto doctor = signedIn(7L, Role.DOCTOR, null, null);
        when(conditions.findByPatientId(PATIENT_ID)).thenReturn(List.of(stored("c-1", "Astma", null,
                "Encounter/" + ENCOUNTER_ID, null)));
        when(encounters.findByPatientId(PATIENT_ID)).thenThrow(new FhirServerException("encounter-search",
                FhirServerException.Type.TIMEOUT, null, List.of(), null));
        assertThat(service.listForPatient(doctor, PATIENT_ID)).singleElement()
                .satisfies(diagnosis -> assertThat(diagnosis.encounter()).isNull());
    }

    @Test
    void cliniciansReadOnlyPatientsKnownToMediCoreAndPatientsNeverByIdentifier() {
        UserDto doctor = signedIn(7L, Role.DOCTOR, null, null);
        assertThatThrownBy(() -> service.listForPatient(doctor, "syn-unregistered")).isInstanceOf(PatientNotFoundException.class);
        UserDto patient = signedIn(9L, Role.PATIENT, null, PATIENT_ID);
        assertThatThrownBy(() -> service.listForPatient(patient, PATIENT_ID)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.listForPatient(patient, OTHER_PATIENT_ID)).isInstanceOf(AccessDeniedException.class);
        verify(conditions, never()).findByPatientId(anyString());
    }

    @Test
    void patientsReadOnlyTheirOwnApprovedRecord() {
        UserDto patient = signedIn(9L, Role.PATIENT, null, PATIENT_ID);
        when(conditions.findByPatientId(PATIENT_ID)).thenReturn(List.of(stored("c-1", "Astma", null, null, null)));
        assertThat(service.listOwn(patient)).singleElement()
                .satisfies(diagnosis -> assertThat(diagnosis.diagnosis()).isEqualTo("Astma"));

        UserDto unlinked = signedIn(10L, Role.PATIENT, null, null);
        assertThatThrownBy(() -> service.listOwn(unlinked)).isInstanceOfSatisfying(DiagnosisRequestException.class,
                rejected -> {
                    assertThat(rejected.code()).isEqualTo("RECORD_NOT_LINKED");
                    assertThat(rejected.status()).isEqualTo(404);
                });
        UserDto doctor = signedIn(7L, Role.DOCTOR, PRACTITIONER_ID, null);
        assertThatThrownBy(() -> service.listOwn(doctor)).isInstanceOf(AccessDeniedException.class);
        verify(conditions, times(1)).findByPatientId(anyString());
    }

    @Test
    void onsetsOfAllR4TypesAreReadable() {
        assertThat(DiagnosisService.onset(null)).isNull();
        assertThat(DiagnosisService.onset(new TemporalValue(TemporalType.DATE_TIME, new ConditionDto.TextValue("2020-05"))))
                .isEqualTo("2020-05");
        assertThat(DiagnosisService.onset(new TemporalValue(TemporalType.PERIOD, new ConditionDto.PeriodValue("2019", null))))
                .isEqualTo("2019");
        assertThat(DiagnosisService.onset(new TemporalValue(TemporalType.STRING, new ConditionDto.TextValue("Childhood"))))
                .isEqualTo("Childhood");
        assertThat(DiagnosisService.onset(new TemporalValue(TemporalType.RANGE, new ConditionDto.RangeValue(
                new QuantityValue(new BigDecimal("20.0"), null, "years", null, null), null)))).isEqualTo("Age 20 years – ?");
        assertThat(DiagnosisService.diagnosisText(null)).isNull();
    }

    private static FhirServerException missing(String operation) {
        return new FhirServerException(operation, FhirServerException.Type.CLIENT_ERROR, 404, List.of(), null);
    }
}
