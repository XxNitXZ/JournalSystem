package com.medinexus.medicore.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;

import jakarta.persistence.EntityManager;

/** Real PostgreSQL constraints, including direct SQL that bypasses the entity. */
@SpringBootTest
@Transactional
class UserFhirIdentityConstraintsIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository repository;

    @Autowired
    private EntityManager entityManager;

    @ParameterizedTest
    @EnumSource(Role.class)
    void permitsMultipleUnlinkedAccountsForEveryRole(Role role) {
        insert(role.name(), null, null);
        insert(role.name(), null, null);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void persistsAndReloadsTheCompatibleReference(Role role) {
        User user = new User();
        user.setEmail(UUID.randomUUID() + "@example.test");
        user.setPasswordHash("synthetic-hash");
        user.setRole(role);
        String id = UUID.randomUUID().toString();
        if (role == Role.PATIENT) user.setFhirPatientId(id);
        else user.setFhirPractitionerId(id);
        Long accountId = repository.saveAndFlush(user).getId();
        entityManager.clear();

        User loaded = repository.findById(accountId).orElseThrow();
        assertThat(loaded.getRole()).isEqualTo(role);
        assertThat(loaded.getFhirPatientId()).isEqualTo(role == Role.PATIENT ? id : null);
        assertThat(loaded.getFhirPractitionerId()).isEqualTo(role == Role.PATIENT ? null : id);
    }

    @Test
    void rejectsDuplicatePatientOwnership() {
        String id = UUID.randomUUID().toString();
        insert("PATIENT", id, null);
        rejects(() -> insert("PATIENT", id, null), "23505", "uq_users_fhir_patient_id");
    }

    @ParameterizedTest
    @CsvSource({"DOCTOR, DOCTOR", "STAFF, STAFF", "DOCTOR, STAFF", "STAFF, DOCTOR"})
    void rejectsDuplicatePractitionerOwnershipAcrossProfessionalRoles(String first, String second) {
        String id = UUID.randomUUID().toString();
        insert(first, null, id);
        rejects(() -> insert(second, null, id), "23505", "uq_users_fhir_practitioner_id");
    }

    @ParameterizedTest
    @CsvSource({"DOCTOR, ck_users_fhir_patient_role", "STAFF, ck_users_fhir_patient_role",
            "PATIENT, ck_users_fhir_practitioner_role"})
    void rejectsIncompatibleReferenceType(String role, String constraint) {
        rejects(() -> insert(role, role.equals("PATIENT") ? null : "synthetic-1",
                role.equals("PATIENT") ? "synthetic-1" : null), "23514", constraint);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void rejectsBothReferences(Role role) {
        rejects(() -> insert(role.name(), "synthetic-patient", "synthetic-practitioner"), "23514",
                role == Role.PATIENT ? "ck_users_fhir_practitioner_role" : "ck_users_fhir_patient_role");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " id", "id ", "Patient/1", "Practitioner/1", "https://example.test/1",
            "id?x=1", "id#fragment", "id_name", "ä", "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklm"})
    void rejectsInvalidPatientIds(String id) {
        rejects(() -> insert("PATIENT", id, null), "23514", "ck_users_fhir_patient_id_format");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " id", "id ", "Patient/1", "Practitioner/1", "https://example.test/1",
            "id?x=1", "id#fragment", "id_name", "ä", "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklm"})
    void rejectsInvalidPractitionerIds(String id) {
        rejects(() -> insert("DOCTOR", null, id), "23514", "ck_users_fhir_practitioner_id_format");
    }

    @Test
    void permitsIdBoundariesAndSameIdInDifferentResourceTypes() {
        insert("PATIENT", "x", null);
        insert("DOCTOR", null, "x");
        insert("PATIENT", "A0-.".repeat(16), null);
        insert("STAFF", null, "A0-.".repeat(16));
    }

    @Test
    void rejectsIncompatibleRoleChangeOnExistingReference() {
        String email = insert("PATIENT", UUID.randomUUID().toString(), null);
        rejects(() -> jdbc.update("UPDATE users SET role = 'DOCTOR' WHERE email = ?", email),
                "23514", "ck_users_fhir_patient_role");
    }

    private String insert(String role, String patientId, String practitionerId) {
        String email = UUID.randomUUID() + "@example.test";
        jdbc.update("""
                INSERT INTO users (email, password_hash, role, fhir_patient_id, fhir_practitioner_id)
                VALUES (?, ?, ?, ?, ?)
                """, email, "synthetic-hash", role, patientId, practitionerId);
        return email;
    }

    private void rejects(Runnable write, String sqlState, String constraint) {
        assertThatThrownBy(write::run).isInstanceOfSatisfying(DataIntegrityViolationException.class, error -> {
            assertThat(error.getMostSpecificCause()).isInstanceOf(SQLException.class);
            SQLException cause = (SQLException) error.getMostSpecificCause();
            assertThat(cause.getSQLState()).isEqualTo(sqlState);
            assertThat(cause.getMessage()).contains(constraint);
        });
    }
}
