package com.medinexus.medicore.auth.repository;

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
