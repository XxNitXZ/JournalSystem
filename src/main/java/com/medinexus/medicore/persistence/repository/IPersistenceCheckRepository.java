package com.medinexus.medicore.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.medinexus.medicore.persistence.entity.PersistenceCheck;

/**
 * Technical repository used to verify Spring Data JPA initialization.
 * Spring Data JPA generates the implementation at runtime.
 */
public interface IPersistenceCheckRepository extends JpaRepository<PersistenceCheck, Long> {
}
