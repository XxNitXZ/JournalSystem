package com.medinexus.medicore.fhir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.medinexus.medicore.fhir.client.FhirConditionClient;
import com.medinexus.medicore.fhir.client.FhirEncounterClient;
import com.medinexus.medicore.fhir.client.FhirLocationClient;
import com.medinexus.medicore.fhir.client.FhirObservationClient;
import com.medinexus.medicore.fhir.client.FhirOrganizationClient;
import com.medinexus.medicore.fhir.client.FhirPatientClient;
import com.medinexus.medicore.fhir.client.FhirPractitionerClient;
import com.medinexus.medicore.fhir.client.FhirRequestExecutor;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.fhir.config.FhirClientConfig;
import com.medinexus.medicore.fhir.mapper.ConditionMapper;
import com.medinexus.medicore.fhir.mapper.EncounterMapper;
import com.medinexus.medicore.fhir.mapper.LocationMapper;
import com.medinexus.medicore.fhir.mapper.ObservationMapper;
import com.medinexus.medicore.fhir.mapper.OrganizationMapper;
import com.medinexus.medicore.fhir.mapper.PatientMapper;
import com.medinexus.medicore.fhir.mapper.PractitionerMapper;
import com.medinexus.medicore.fhir.support.LocalFhirServer;
import com.medinexus.medicore.location.dto.LocationDto;
import com.medinexus.medicore.location.dto.LocationWriteRequest;
import com.medinexus.medicore.medicalrecord.dto.ConditionDto;
import com.medinexus.medicore.medicalrecord.dto.CreateConditionRequest;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest.EncounterClass;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest.EncounterLocation;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest.LocationStatus;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest.Participant;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest.ParticipantType;
import com.medinexus.medicore.medicalrecord.dto.CreateObservationRequest;
import com.medinexus.medicore.medicalrecord.dto.EncounterDto;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Coding;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.SubjectReference;
import com.medinexus.medicore.organization.dto.OrganizationDto;
import com.medinexus.medicore.organization.dto.OrganizationWriteRequest;
import com.medinexus.medicore.patient.dto.PatientDto;
import com.medinexus.medicore.practitioner.dto.PractitionerDto;

/**
 * NIT-137, FHIR part: a complete synthetic patient journey through the existing FHIR clients and mappers
 * against a local, stateful FHIR server ({@link LocalFhirServer}). No database and no external FHIR server.
 *
 * <p>Patient and Practitioner are seeded on the local server because MediCore cannot create them. The link
 * from a local account to its FHIR Patient ID (PostgreSQL, {@code AccountFhirIdentityService}) is outside
 * this database-free test; the journey starts from the logical Patient ID such an account would hold.
 */
class FhirPatientJourneyIntegrationTest {

    private static final String PATIENT_ID = "synthetic-patient-1";
    private static final String OTHER_PATIENT_ID = "synthetic-patient-2";
    private static final String PRACTITIONER_ID = "synthetic-practitioner-1";

    private LocalFhirServer fhir;

    @BeforeEach
    void start() throws IOException {
        fhir = LocalFhirServer.start();
        fhir.seed(patient(PATIENT_ID, "SYN-1"));
        fhir.seed(patient(OTHER_PATIENT_ID, "SYN-2"));
        Practitioner practitioner = new Practitioner();
        practitioner.setId(PRACTITIONER_ID);
        practitioner.setActive(true);
        practitioner.addIdentifier().setSystem("urn:synthetic:practitioner").setValue("SYN-DOC-1");
        practitioner.addName().setFamily("Synthetic").addGiven("Doctor");
        fhir.seed(practitioner);
    }

    @AfterEach
    void stop() {
        fhir.close();
    }

    private static Patient patient(String id, String identifier) {
        Patient patient = new Patient();
        patient.setId(id);
        patient.addIdentifier().setSystem("urn:synthetic:patient").setValue(identifier);
        patient.addName().setFamily("Synthetic").addGiven("Patient");
        patient.setGender(AdministrativeGender.UNKNOWN);
        patient.setBirthDateElement(new DateType("1980-01"));
        return patient;
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(FhirClientConfig.class, FhirRequestExecutor.class,
                FhirPatientClient.class, PatientMapper.class, FhirPractitionerClient.class, PractitionerMapper.class,
                FhirOrganizationClient.class, OrganizationMapper.class, FhirLocationClient.class, LocationMapper.class,
                FhirEncounterClient.class, EncounterMapper.class, FhirObservationClient.class, ObservationMapper.class,
                FhirConditionClient.class, ConditionMapper.class)
                .withPropertyValues("medinexus.fhir.base-url=" + fhir.baseUrl(), "medinexus.fhir.connect-timeout=2s",
                        "medinexus.fhir.socket-timeout=3s", "medinexus.fhir.connection-request-timeout=2s");
    }

    private static Concept concept(String system, String code, String display) {
        return new Concept(display, List.of(new Coding(system, null, code, display, null)));
    }

    /** The logical ID of a relative reference of the expected type, e.g. "Organization/local-1" -> "local-1". */
    private static String idOf(SubjectReference reference, String type) {
        assertThat(reference).isNotNull();
        assertThat(reference.reference()).startsWith(type + "/");
        return reference.reference().substring(type.length() + 1);
    }

    @Test
    void completeSyntheticPatientJourneyKeepsEveryReferenceConsistent() {
        context().run(ctx -> {
            assertThat(fhir.baseUrl()).startsWith("http://127.0.0.1:");

            // 1. The Patient (as identified by a linked account's logical ID) and the Practitioner exist in FHIR.
            PatientDto patient = ctx.getBean(PatientMapper.class).toDto(ctx.getBean(FhirPatientClient.class).readPatient(PATIENT_ID));
            assertThat(patient.id()).isEqualTo(PATIENT_ID);
            assertThat(patient.identifiers()).singleElement().satisfies(identifier -> assertThat(identifier.value()).isEqualTo("SYN-1"));
            PractitionerDto practitioner = ctx.getBean(PractitionerMapper.class)
                    .toDto(ctx.getBean(FhirPractitionerClient.class).readPractitioner(PRACTITIONER_ID));
            assertThat(practitioner.id()).isEqualTo(PRACTITIONER_ID);
            assertThat(practitioner.active()).isTrue();

            // 2. The care organization and the ward it manages.
            var organizations = ctx.getBean(FhirOrganizationClient.class);
            var locations = ctx.getBean(FhirLocationClient.class);
            String clinicId = organizations.createOrganization(new OrganizationWriteRequest(null, true,
                    List.of(concept("http://terminology.hl7.org/CodeSystem/organization-type", "prov", "Healthcare Provider")),
                    "Synthetic Clinic", null, null, null, null));
            OrganizationDto clinic = ctx.getBean(OrganizationMapper.class).toDto(organizations.readOrganization(clinicId));
            assertThat(clinic.name()).isEqualTo("Synthetic Clinic");
            assertThat(clinic.versionId()).isEqualTo("1");
            String wardId = locations.createLocation(new LocationWriteRequest(null, LocationWriteRequest.Status.ACTIVE,
                    "Synthetic Ward", null, null, LocationWriteRequest.Mode.INSTANCE, null, null, null,
                    concept("http://terminology.hl7.org/CodeSystem/location-physical-type", "wa", "Ward"),
                    new LocationDto.Position(new BigDecimal("18.0686000"), new BigDecimal("59.3293000"), null),
                    clinicId, null));
            LocationDto ward = ctx.getBean(LocationMapper.class).toDto(locations.readLocation(wardId));
            assertThat(ward.name()).isEqualTo("Synthetic Ward");
            assertThat(idOf(ward.managingOrganization(), "Organization")).isEqualTo(clinicId);

            // 3. The visit: Patient, Practitioner, Organization and Location in one Encounter.
            var encounters = ctx.getBean(FhirEncounterClient.class);
            String encounterId = encounters.createEncounter(new CreateEncounterRequest(patient.id(),
                    CreateEncounterRequest.Status.FINISHED, EncounterClass.AMB,
                    List.of(concept("urn:synthetic:encounter-type", "checkup", "Check-up")), null, null,
                    "2026-06-15T09:00:00+02:00", "2026-06-15T09:30:00+02:00", List.of(),
                    List.of(new Participant(practitioner.id(), List.of(ParticipantType.ATND))), clinicId,
                    List.of(new EncounterLocation(wardId, LocationStatus.COMPLETED))));

            // 4. Clinical findings for the same Patient, and one Observation for another Patient.
            var observations = ctx.getBean(FhirObservationClient.class);
            var conditions = ctx.getBean(FhirConditionClient.class);
            Concept heartRate = concept("urn:synthetic:observation", "heart-rate", "Heart rate");
            String observationId = observations.createObservation(new CreateObservationRequest(patient.id(),
                    CreateObservationRequest.Status.FINAL, heartRate, new CreateObservationRequest.QuantityValue(
                            new BigDecimal("72"), "/min", "http://unitsofmeasure.org", "/min", null), null));
            String conditionId = conditions.createCondition(new CreateConditionRequest(patient.id(),
                    concept("urn:synthetic:condition", "hypertension", "Synthetic hypertension"),
                    CreateConditionRequest.ClinicalStatus.ACTIVE, CreateConditionRequest.VerificationStatus.CONFIRMED,
                    List.of(), null, "2026-06-15", null));
            String otherObservationId = observations.createObservation(new CreateObservationRequest(OTHER_PATIENT_ID,
                    CreateObservationRequest.Status.FINAL, heartRate, new CreateObservationRequest.QuantityValue(
                            new BigDecimal("80"), "/min", "http://unitsofmeasure.org", "/min", null), null));

            // 5. Read the clinical resources back.
            EncounterDto encounter = ctx.getBean(EncounterMapper.class).toDto(encounters.readEncounter(encounterId));
            ObservationDto observation = ctx.getBean(ObservationMapper.class).toDto(observations.readObservation(observationId));
            ConditionDto condition = ctx.getBean(ConditionMapper.class).toDto(conditions.readCondition(conditionId));
            assertThat(encounter.id()).isEqualTo(encounterId);
            assertThat(encounter.status()).isEqualTo("finished");
            assertThat(observation.status()).isEqualTo("final");
            assertThat(condition.code().text()).isEqualTo("Synthetic hypertension");

            // 6. Every clinical resource refers to the same Patient.
            assertThat(idOf(encounter.subject(), "Patient")).isEqualTo(PATIENT_ID);
            assertThat(idOf(observation.subject(), "Patient")).isEqualTo(PATIENT_ID);
            assertThat(idOf(condition.subject(), "Patient")).isEqualTo(PATIENT_ID);

            // 7. Search by Patient ID returns exactly this Patient's resources, identical to the reads.
            assertThat(encounters.findByPatientId(PATIENT_ID)).containsExactly(encounter);
            assertThat(observations.findByPatientId(PATIENT_ID)).containsExactly(observation);
            assertThat(conditions.findByPatientId(PATIENT_ID)).containsExactly(condition);
            assertThat(observations.findByPatientId(OTHER_PATIENT_ID)).singleElement()
                    .satisfies(other -> assertThat(other.id()).isEqualTo(otherObservationId));
            assertThat(encounters.findByPatientId(OTHER_PATIENT_ID)).isEmpty();
            assertThat(conditions.findByPatientId(OTHER_PATIENT_ID)).isEmpty();

            // 8. Follow the Encounter's references with the owning clients: they resolve to the same resources.
            assertThat(encounter.participants()).singleElement().satisfies(participant -> {
                String practitionerId = idOf(participant.individual(), "Practitioner");
                assertThat(ctx.getBean(PractitionerMapper.class).toDto(ctx.getBean(FhirPractitionerClient.class)
                        .readPractitioner(practitionerId))).isEqualTo(practitioner);
                assertThat(participant.types()).singleElement().satisfies(type ->
                        assertThat(type.coding().getFirst().code()).isEqualTo("ATND"));
            });
            assertThat(ctx.getBean(PatientMapper.class).toDto(ctx.getBean(FhirPatientClient.class)
                    .readPatient(idOf(encounter.subject(), "Patient")))).isEqualTo(patient);
            OrganizationDto provider = ctx.getBean(OrganizationMapper.class)
                    .toDto(organizations.readOrganization(idOf(encounter.serviceProvider(), "Organization")));
            assertThat(provider).isEqualTo(clinic);
            assertThat(encounter.locations()).singleElement().satisfies(entry -> {
                assertThat(entry.status()).isEqualTo("completed");
                LocationDto place = ctx.getBean(LocationMapper.class).toDto(locations.readLocation(idOf(entry.location(), "Location")));
                assertThat(place).isEqualTo(ward);
                // The Encounter's ward is managed by the Encounter's service provider.
                assertThat(place.managingOrganization().reference()).isEqualTo(encounter.serviceProvider().reference());
            });
        });

        // Only reads and creates were sent; Patient and Practitioner were never written.
        List<String> requests = fhir.requests();
        assertThat(requests).allMatch(request -> request.startsWith("GET /fhir/") || request.startsWith("POST /fhir/"));
        assertThat(requests.stream().filter(request -> request.startsWith("POST ")).toList()).containsExactly(
                "POST /fhir/Organization", "POST /fhir/Location", "POST /fhir/Encounter", "POST /fhir/Observation",
                "POST /fhir/Condition", "POST /fhir/Observation");
        assertThat(fhir.count("Patient")).isEqualTo(2);
        assertThat(fhir.count("Practitioner")).isEqualTo(1);
        // 2 initial reads, 2 + 2 Organization/Location create and read, 4 clinical creates, 3 clinical reads,
        // 6 searches (one page each) and 4 reads that follow the Encounter's references.
        assertThat(requests).hasSize(23);
        assertThat(requests.stream().filter(request -> request.contains("?")).toList()).hasSize(6)
                .allMatch(request -> request.matches("GET /fhir/(Encounter|Observation|Condition)\\?.*subject=Patient(/|%2F)"
                        + "synthetic-patient-[12].*"));
    }

    @Test
    void referencesAreNotResolvedAtCreationSoAMissingTargetOnlyFailsWhenRead() {
        context().run(ctx -> {
            String encounterId = ctx.getBean(FhirEncounterClient.class).createEncounter(new CreateEncounterRequest(PATIENT_ID,
                    CreateEncounterRequest.Status.PLANNED, EncounterClass.AMB, null, null, null, null, null, null,
                    List.of(new Participant("missing-practitioner", null)), "missing-org",
                    List.of(new EncounterLocation("missing-location", null))));
            EncounterDto encounter = ctx.getBean(EncounterMapper.class)
                    .toDto(ctx.getBean(FhirEncounterClient.class).readEncounter(encounterId));
            assertThat(encounter.serviceProvider().reference()).isEqualTo("Organization/missing-org");

            assertThatThrownBy(() -> ctx.getBean(FhirOrganizationClient.class)
                    .readOrganization(idOf(encounter.serviceProvider(), "Organization")))
                    .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                        assertThat(failure.operation()).isEqualTo("organization-read");
                        assertThat(failure.httpStatus()).hasValue(404);
                    });
            assertThatThrownBy(() -> ctx.getBean(FhirLocationClient.class)
                    .readLocation(idOf(encounter.locations().getFirst().location(), "Location")))
                    .isInstanceOfSatisfying(FhirServerException.class, failure -> assertThat(failure.httpStatus()).hasValue(404));
            assertThatThrownBy(() -> ctx.getBean(FhirPractitionerClient.class)
                    .readPractitioner(idOf(encounter.participants().getFirst().individual(), "Practitioner")))
                    .isInstanceOfSatisfying(FhirServerException.class, failure -> assertThat(failure.httpStatus()).hasValue(404));
        });
        // Creating the Encounter sent exactly one request; no lookups were made before it.
        assertThat(fhir.requests()).containsExactly("POST /fhir/Encounter", "GET /fhir/Encounter/local-1",
                "GET /fhir/Organization/missing-org", "GET /fhir/Location/missing-location",
                "GET /fhir/Practitioner/missing-practitioner");
    }

    @Test
    void patientWithoutClinicalRecordsHasEmptySearchesAndAnUnknownPatientIsNotFound() {
        context().run(ctx -> {
            assertThat(ctx.getBean(FhirEncounterClient.class).findByPatientId(PATIENT_ID)).isEmpty();
            assertThat(ctx.getBean(FhirObservationClient.class).findByPatientId(PATIENT_ID)).isEmpty();
            assertThat(ctx.getBean(FhirConditionClient.class).findByPatientId(PATIENT_ID)).isEmpty();
            assertThatThrownBy(() -> ctx.getBean(FhirPatientClient.class).readPatient("unknown-patient"))
                    .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                        assertThat(failure.operation()).isEqualTo("patient-read");
                        assertThat(failure.type()).isEqualTo(FhirServerException.Type.CLIENT_ERROR);
                        assertThat(failure.httpStatus()).hasValue(404);
                    });
        });
        assertThat(fhir.requests()).hasSize(4).allMatch(request -> request.startsWith("GET /fhir/"));
    }
}
