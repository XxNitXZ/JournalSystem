package com.medinexus.medicore.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.auth.service.AccountFhirIdentityException;
import com.medinexus.medicore.auth.service.AccountFhirIdentityService;
import com.medinexus.medicore.auth.service.RegistrationService;
import com.medinexus.medicore.fhir.client.FhirConditionClient;
import com.medinexus.medicore.fhir.client.FhirEncounterClient;
import com.medinexus.medicore.fhir.client.FhirLocationClient;
import com.medinexus.medicore.fhir.client.FhirObservationClient;
import com.medinexus.medicore.fhir.client.FhirOrganizationClient;
import com.medinexus.medicore.fhir.client.FhirPatientClient;
import com.medinexus.medicore.fhir.client.FhirPractitionerClient;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.fhir.mapper.PatientMapper;
import com.medinexus.medicore.fhir.support.LocalFhirServer;
import com.medinexus.medicore.location.dto.LocationWriteRequest;
import com.medinexus.medicore.medicalrecord.dto.CreateConditionRequest;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest;
import com.medinexus.medicore.medicalrecord.dto.CreateObservationRequest;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Coding;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.medinexus.medicore.organization.dto.OrganizationWriteRequest;

/**
 * US-03.06 (NIT-137, account part): a synthetic local account is linked to its FHIR Patient through
 * {@link AccountFhirIdentityService}, and the stored logical ID reaches exactly that Patient's resources
 * through the existing FHIR clients. Real PostgreSQL ({@code medinexus_test} only) and a local FHIR test
 * server; no external FHIR server.
 *
 * <p>Runs only when {@code DB_URL} names the {@code medinexus_test} database, so it can never migrate or
 * write to another database. Each test creates its own accounts (random e-mail) and FHIR IDs and deletes
 * exactly those accounts afterwards.
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/medinexus_test")
class AccountFhirPatientJourneyIntegrationTest {

    private static LocalFhirServer fhir;

    @DynamicPropertySource
    static void fhirServer(DynamicPropertyRegistry registry) throws IOException {
        if (fhir == null) fhir = LocalFhirServer.start();
        registry.add("medinexus.fhir.base-url", fhir::baseUrl);
    }

    @AfterAll
    static void stopFhirServer() {
        if (fhir != null) fhir.close();
        fhir = null;
    }

    @Value("${spring.datasource.url}")
    private String datasourceUrl;
    @Autowired
    private RegistrationService registration;
    @Autowired
    private AccountFhirIdentityService identities;
    @Autowired
    private UserRepository users;
    @Autowired
    private FhirPatientClient patients;
    @Autowired
    private PatientMapper patientMapper;
    @Autowired
    private FhirPractitionerClient practitioners;
    @Autowired
    private FhirOrganizationClient organizations;
    @Autowired
    private FhirLocationClient locations;
    @Autowired
    private FhirEncounterClient encounters;
    @Autowired
    private FhirObservationClient observations;
    @Autowired
    private FhirConditionClient conditions;

    private final List<Long> createdAccounts = new ArrayList<>();
    private final String password = "Synthetic-Passw0rd-" + UUID.randomUUID();

    @BeforeEach
    void onlyTheTestDatabase() {
        assertThat(datasourceUrl).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/medinexus_test");
    }

    @AfterEach
    void deleteCreatedAccounts() {
        createdAccounts.forEach(users::deleteById);
    }

    private Long register(Role role) {
        Long id = registration.register("nit-us0306-" + UUID.randomUUID() + "@example.test", password, role).id();
        createdAccounts.add(id);
        return id;
    }

    private static String seedPatient() {
        Patient patient = new Patient();
        patient.setId("syn-pat-" + UUID.randomUUID().toString().substring(0, 18));
        patient.addIdentifier().setSystem("urn:synthetic:patient").setValue("SYN-" + patient.getIdElement().getIdPart());
        patient.addName().setFamily("Synthetic").addGiven("Patient");
        fhir.seed(patient);
        return patient.getIdElement().getIdPart();
    }

    private static String seedPractitioner() {
        Practitioner practitioner = new Practitioner();
        practitioner.setId("syn-doc-" + UUID.randomUUID().toString().substring(0, 18));
        practitioner.addName().setFamily("Synthetic").addGiven("Doctor");
        fhir.seed(practitioner);
        return practitioner.getIdElement().getIdPart();
    }

    private static Concept concept(String code) {
        return new Concept("Synthetic " + code, List.of(new Coding("urn:synthetic:code", null, code, null, null)));
    }

    /** The persisted account fields that a FHIR identity operation must never change unexpectedly. */
    private record AccountState(String email, Role role, String passwordHash, String fhirPatientId,
            String fhirPractitionerId) {
        static AccountState of(User user) {
            return new AccountState(user.getEmail(), user.getRole(), user.getPasswordHash(), user.getFhirPatientId(),
                    user.getFhirPractitionerId());
        }
    }

    private AccountState state(Long accountId) {
        return AccountState.of(users.findById(accountId).orElseThrow());
    }

    private List<String> newRequests(int before) {
        List<String> all = fhir.requests();
        return all.subList(before, all.size());
    }

    @Test
    void linkedPatientIdReachesExactlyThatPatientsFhirRecords() {
        String patientId = seedPatient();
        String otherPatientId = seedPatient();
        String practitionerId = seedPractitioner();
        Long patientAccount = register(Role.PATIENT);
        Long doctorAccount = register(Role.DOCTOR);
        AccountState before = state(patientAccount);

        // 1. Link the accounts: one GET each, then only the FHIR ID reference is stored in PostgreSQL.
        int start = fhir.requests().size();
        identities.assign(patientAccount, patientId);
        identities.assign(doctorAccount, practitionerId);
        assertThat(newRequests(start)).containsExactly("GET /fhir/Patient/" + patientId,
                "GET /fhir/Practitioner/" + practitionerId);
        assertThat(state(patientAccount)).isEqualTo(new AccountState(before.email(), Role.PATIENT, before.passwordHash(),
                patientId, null));
        assertThat(state(doctorAccount).fhirPractitionerId()).isEqualTo(practitionerId);
        assertThat(state(doctorAccount).fhirPatientId()).isNull();

        // 2. Clinical records are created in FHIR from the IDs the accounts hold.
        String linkedPatient = users.findById(patientAccount).orElseThrow().getFhirPatientId();
        String linkedPractitioner = users.findById(doctorAccount).orElseThrow().getFhirPractitionerId();
        String clinicId = organizations.createOrganization(
                new OrganizationWriteRequest(null, true, null, "Synthetic Clinic", null, null, null, null));
        String wardId = locations.createLocation(new LocationWriteRequest(null, LocationWriteRequest.Status.ACTIVE,
                "Synthetic Ward", null, null, null, null, null, null, null, null, clinicId, null));
        String encounterId = encounters.createEncounter(new CreateEncounterRequest(linkedPatient,
                CreateEncounterRequest.Status.FINISHED, CreateEncounterRequest.EncounterClass.AMB, null, null, null,
                "2026-06-15T09:00:00+02:00", null, null,
                List.of(new CreateEncounterRequest.Participant(linkedPractitioner,
                        List.of(CreateEncounterRequest.ParticipantType.ATND))),
                clinicId, List.of(new CreateEncounterRequest.EncounterLocation(wardId, CreateEncounterRequest.LocationStatus.COMPLETED))));
        String observationId = observations.createObservation(new CreateObservationRequest(linkedPatient,
                CreateObservationRequest.Status.FINAL, concept("heart-rate"), new CreateObservationRequest.QuantityValue(
                        new BigDecimal("72"), "/min", "http://unitsofmeasure.org", "/min", null), null));
        String conditionId = conditions.createCondition(new CreateConditionRequest(linkedPatient, concept("hypertension"),
                CreateConditionRequest.ClinicalStatus.ACTIVE, CreateConditionRequest.VerificationStatus.CONFIRMED,
                List.of(), null, "2026-06-15", null));
        observations.createObservation(new CreateObservationRequest(otherPatientId, CreateObservationRequest.Status.FINAL,
                concept("heart-rate"), new CreateObservationRequest.QuantityValue(new BigDecimal("80"), "/min",
                        "http://unitsofmeasure.org", "/min", null), null));

        // 3. The account's linked ID retrieves the right Patient and exactly that Patient's resources.
        String accountPatientId = users.findById(patientAccount).orElseThrow().getFhirPatientId();
        var patient = patientMapper.toDto(patients.readPatient(accountPatientId));
        assertThat(patient.id()).isEqualTo(patientId);
        assertThat(patient.identifiers()).singleElement().satisfies(identifier ->
                assertThat(identifier.value()).isEqualTo("SYN-" + patientId));
        assertThat(encounters.findByPatientId(accountPatientId)).singleElement().satisfies(encounter -> {
            assertThat(encounter.id()).isEqualTo(encounterId);
            assertThat(encounter.subject().reference()).isEqualTo("Patient/" + patientId);
            assertThat(encounter.participants().getFirst().individual().reference()).isEqualTo("Practitioner/" + practitionerId);
            assertThat(encounter.serviceProvider().reference()).isEqualTo("Organization/" + clinicId);
            assertThat(encounter.locations().getFirst().location().reference()).isEqualTo("Location/" + wardId);
        });
        assertThat(observations.findByPatientId(accountPatientId)).singleElement().satisfies(observation -> {
            assertThat(observation.id()).isEqualTo(observationId);
            assertThat(observation.subject().reference()).isEqualTo("Patient/" + patientId);
        });
        assertThat(conditions.findByPatientId(accountPatientId)).singleElement().satisfies(condition -> {
            assertThat(condition.id()).isEqualTo(conditionId);
            assertThat(condition.subject().reference()).isEqualTo("Patient/" + patientId);
        });
        assertThat(practitioners.readPractitioner(users.findById(doctorAccount).orElseThrow().getFhirPractitionerId())
                .getIdElement().getIdPart()).isEqualTo(practitionerId);

        // 4. PostgreSQL still holds only the account and its reference; no write ever went to Patient/Practitioner.
        assertThat(state(patientAccount)).isEqualTo(new AccountState(before.email(), Role.PATIENT, before.passwordHash(),
                patientId, null));
        assertThat(newRequests(start)).noneMatch(request -> request.startsWith("POST /fhir/Patient")
                || request.startsWith("POST /fhir/Practitioner")).allMatch(request -> request.startsWith("GET ")
                || request.startsWith("POST "));
    }

    @Test
    void failedAssignmentsChangeNothingAndLeakNothing(CapturedOutput output) {
        Long patientAccount = register(Role.PATIENT);
        Long staffAccount = register(Role.STAFF);
        AccountState patientBefore = state(patientAccount);
        AccountState staffBefore = state(staffAccount);
        String missingPatient = "syn-missing-" + UUID.randomUUID().toString().substring(0, 18);
        String missingPractitioner = "syn-missing-" + UUID.randomUUID().toString().substring(0, 18);
        int start = fhir.requests().size();

        // The FHIR resource does not exist: 404 is propagated, the account is unchanged.
        assertThatThrownBy(() -> identities.assign(patientAccount, missingPatient))
                .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                    assertThat(failure.operation()).isEqualTo("patient-read");
                    assertThat(failure.httpStatus()).hasValue(404);
                });
        assertThatThrownBy(() -> identities.assign(staffAccount, missingPractitioner))
                .isInstanceOfSatisfying(FhirServerException.class, failure -> assertThat(failure.httpStatus()).hasValue(404));
        assertThat(state(patientAccount)).isEqualTo(patientBefore);
        assertThat(state(staffAccount)).isEqualTo(staffBefore);
        assertThat(newRequests(start)).containsExactly("GET /fhir/Patient/" + missingPatient,
                "GET /fhir/Practitioner/" + missingPractitioner);

        // A linked account cannot be re-linked: rejected before any FHIR request, nothing changes.
        String patientId = seedPatient();
        identities.assign(patientAccount, patientId);
        AccountState linked = state(patientAccount);
        int beforeRelink = fhir.requests().size();
        assertThatThrownBy(() -> identities.assign(patientAccount, seedPatient()))
                .isInstanceOf(AccountFhirIdentityException.class)
                .hasMessage("Account association conflicts with the current account state");
        assertThat(fhir.requests()).hasSize(beforeRelink);
        assertThat(state(patientAccount)).isEqualTo(linked);

        // Neither logs nor messages contain the e-mail, password, hash, FHIR IDs, database password or server address.
        List<String> secrets = new ArrayList<>(List.of(patientBefore.email(), staffBefore.email(), password,
                patientBefore.passwordHash(), missingPatient, missingPractitioner, fhir.baseUrl(),
                fhir.baseUrl().substring("http://".length(), fhir.baseUrl().lastIndexOf('/'))));
        String databasePassword = System.getenv("DB_PASSWORD");
        if (databasePassword != null && !databasePassword.isBlank()) secrets.add(databasePassword);
        String all = output.getAll();
        for (int i = 0; i < secrets.size(); i++) {
            assertThat(all.contains(secrets.get(i))).as("log contains sensitive value #%d", i).isFalse();
        }
        assertThat(all).contains("FHIR request failed: operation=patient-read, type=CLIENT_ERROR, httpStatus=404");
    }
}
