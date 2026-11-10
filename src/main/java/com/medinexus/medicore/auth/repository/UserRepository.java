package com.medinexus.medicore.auth.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    List<User> findByRoleIn(List<Role> roles);

    /** True if an account is linked to this FHIR Patient (operator provisioning or an approved journal request). */
    boolean existsByFhirPatientId(String fhirPatientId);

    /** The account linked to this FHIR Patient, if any. */
    Optional<User> findByFhirPatientId(String fhirPatientId);

    boolean existsByPersonnummer(String personnummer);

    Optional<User> findByPersonnummer(String personnummer);

    /** FHIR Patient IDs linked to accounts. */
    @Query("SELECT u.fhirPatientId FROM User u WHERE u.fhirPatientId IS NOT NULL")
    List<String> findLinkedFhirPatientIds();

    /** Changes only the login e-mail of one account; returns the number of updated rows (0 or 1). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE User u SET u.email = :email WHERE u.id = :accountId")
    int updateEmail(@Param("accountId") Long accountId, @Param("email") String email);

    /** Changes only the phone number (null removes it) of one account; returns the number of updated rows. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE User u SET u.phoneNumber = :phoneNumber WHERE u.id = :accountId")
    int updatePhoneNumber(@Param("accountId") Long accountId, @Param("phoneNumber") String phoneNumber);

    /** Changes only the password hash of one account; returns the number of updated rows (0 or 1). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE User u SET u.passwordHash = :passwordHash WHERE u.id = :accountId")
    int updatePasswordHash(@Param("accountId") Long accountId, @Param("passwordHash") String passwordHash);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("""
            UPDATE User u SET u.fhirPatientId = :fhirId
            WHERE u.id = :accountId AND u.role = com.medinexus.medicore.auth.entity.Role.PATIENT
              AND u.fhirPatientId IS NULL AND u.fhirPractitionerId IS NULL
            """)
    int assignPatientIfUnlinked(@Param("accountId") Long accountId, @Param("fhirId") String fhirId);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("""
            UPDATE User u SET u.fhirPractitionerId = :fhirId
            WHERE u.id = :accountId AND u.role = :expectedRole
              AND u.role IN (com.medinexus.medicore.auth.entity.Role.DOCTOR,
                             com.medinexus.medicore.auth.entity.Role.STAFF)
              AND u.fhirPatientId IS NULL AND u.fhirPractitionerId IS NULL
            """)
    int assignPractitionerIfUnlinked(@Param("accountId") Long accountId,
            @Param("expectedRole") Role expectedRole, @Param("fhirId") String fhirId);
}
