package com.medinexus.medicore.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Minimal technical entity used only to verify the JPA / Hibernate setup.
 * It contains no application or patient data.
 *
 * <p>The table {@code persistence_check} is not created automatically
 * ({@code spring.jpa.hibernate.ddl-auto=none}).
 */
@Entity
@Table(name = "persistence_check")
public class PersistenceCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "description", nullable = false)
    private String description;

    /** Required by JPA. */
    protected PersistenceCheck() {
    }

    public PersistenceCheck(String description) {
        this.description = description;
    }

    public Long getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }
}
