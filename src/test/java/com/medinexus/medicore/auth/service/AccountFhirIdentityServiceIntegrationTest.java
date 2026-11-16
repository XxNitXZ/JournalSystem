package com.medinexus.medicore.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.fhir.client.FhirPatientClient;
import com.medinexus.medicore.fhir.client.FhirPractitionerClient;

/** Real PostgreSQL updates and transaction boundaries; external FHIR reads are mocked. */
@SpringBootTest
class AccountFhirIdentityServiceIntegrationTest {
    @Autowired
    private AccountFhirIdentityService service;
    @Autowired
    private UserRepository repository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @MockitoBean
    private FhirPatientClient patients;
    @MockitoBean
    private FhirPractitionerClient practitioners;

    private final List<Long> created = new ArrayList<>();

    @AfterEach
    void cleanup() {
        created.forEach(repository::deleteById);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void persistsAssociationsAndSuspendsCallerTransactionForFhirRead(Role role) {
        Long accountId = create(role);
        String id = UUID.randomUUID().toString();
        when(patients.readPatient(id)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Patient resource = new Patient(); resource.setId(id); return resource;
        });
        when(practitioners.readPractitioner(id)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Practitioner resource = new Practitioner(); resource.setId(id); return resource;
        });
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> service.assign(accountId, id));
        service.assign(accountId, id);
        User saved = repository.findById(accountId).orElseThrow();
        assertThat(saved.getRole()).isEqualTo(role);
        assertThat(saved.getPasswordHash()).isEqualTo("synthetic-hash");
        assertThat(saved.getFhirPatientId()).isEqualTo(role == Role.PATIENT ? id : null);
        assertThat(saved.getFhirPractitionerId()).isEqualTo(role == Role.PATIENT ? null : id);
    }

    @Test
    void concurrentAssignmentsCannotReplaceEachOther() throws Exception {
        Long accountId = create(Role.PATIENT);
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        mockPatientBarrier();
        List<Boolean> results = race(() -> assign(accountId, first), () -> assign(accountId, second));
        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(repository.findById(accountId).orElseThrow().getFhirPatientId()).isIn(first, second);
    }

    @Test
    void concurrentSameIdentityAssignmentIsIdempotent() throws Exception {
        Long accountId = create(Role.PATIENT);
        String id = UUID.randomUUID().toString();
        mockPatientBarrier();
        assertThat(race(() -> assign(accountId, id), () -> assign(accountId, id))).containsOnly(true);
        assertThat(repository.findById(accountId).orElseThrow().getFhirPatientId()).isEqualTo(id);
    }

    @Test
    void databaseUniquenessPreventsConcurrentDuplicateOwnership() throws Exception {
        Long firstAccount = create(Role.PATIENT);
        Long secondAccount = create(Role.PATIENT);
        String id = UUID.randomUUID().toString();
        mockPatientBarrier();
        assertThat(race(() -> assign(firstAccount, id), () -> assign(secondAccount, id)))
                .containsExactlyInAnyOrder(true, false);
        long owners = created.stream().map(repository::findById).map(java.util.Optional::orElseThrow)
                .filter(user -> id.equals(user.getFhirPatientId())).count();
        assertThat(owners).isEqualTo(1);
    }

    @Test
    void roleChangedDuringFhirReadPreventsAssignment() {
        Long accountId = create(Role.PATIENT);
        String id = UUID.randomUUID().toString();
        when(patients.readPatient(id)).thenAnswer(invocation -> {
            User current = repository.findById(accountId).orElseThrow();
            current.setRole(Role.DOCTOR);
            repository.saveAndFlush(current);
            Patient resource = new Patient(); resource.setId(id); return resource;
        });
        assertThatThrownBy(() -> service.assign(accountId, id)).isInstanceOf(AccountFhirIdentityException.class);
        User current = repository.findById(accountId).orElseThrow();
        assertThat(current.getRole()).isEqualTo(Role.DOCTOR);
        assertThat(current.getFhirPatientId()).isNull();
        assertThat(current.getFhirPractitionerId()).isNull();
    }

    @Test
    void practitionerCannotBeOwnedByBothDoctorAndStaff() {
        Long doctor = create(Role.DOCTOR);
        Long staff = create(Role.STAFF);
        String id = UUID.randomUUID().toString();
        Practitioner resource = new Practitioner(); resource.setId(id);
        when(practitioners.readPractitioner(id)).thenReturn(resource);
        service.assign(doctor, id);
        assertThatThrownBy(() -> service.assign(staff, id)).isInstanceOf(AccountFhirIdentityException.class);
        assertThat(repository.findById(staff).orElseThrow().getFhirPractitionerId()).isNull();
    }

    private Long create(Role role) {
        User user = new User();
        user.setEmail(UUID.randomUUID() + "@example.test");
        user.setPasswordHash("synthetic-hash");
        user.setRole(role);
        Long id = repository.saveAndFlush(user).getId();
        created.add(id);
        return id;
    }

    private void mockPatientBarrier() {
        CyclicBarrier barrier = new CyclicBarrier(2);
        when(patients.readPatient(anyString())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            barrier.await(10, TimeUnit.SECONDS);
            Patient resource = new Patient(); resource.setId(invocation.getArgument(0, String.class)); return resource;
        });
    }

    private boolean assign(Long accountId, String id) {
        try {
            service.assign(accountId, id);
            return true;
        } catch (AccountFhirIdentityException exception) {
            return false;
        }
    }

    private List<Boolean> race(Callable<Boolean> first, Callable<Boolean> second) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(first);
            var two = executor.submit(second);
            return List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
        }
    }
}
