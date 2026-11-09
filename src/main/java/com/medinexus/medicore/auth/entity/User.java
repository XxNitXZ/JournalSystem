package com.medinexus.medicore.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "given_name", length = 100)
    private String givenName;

    @Column(name = "family_name", length = 100)
    private String familyName;

    /** Normalized 12-digit personnummer; null for accounts created before it was required. */
    @Column(name = "personnummer", length = 12)
    private String personnummer;

    /** Optional normalized telephone number (V9); the only phone number MediCore stores. */
    @Column(name = "phone_number", length = 16)
    private String phoneNumber;

    @Column(name = "fhir_patient_id")
    private String fhirPatientId;

    @Column(name = "fhir_practitioner_id")
    private String fhirPractitionerId;

    public User() {
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    /** First name of the account holder; null for accounts created before names were stored. */
    public String getGivenName() {
        return givenName;
    }

    public void setGivenName(String givenName) {
        this.givenName = givenName;
    }

    /** Last name of the account holder; null for accounts created before names were stored. */
    public String getFamilyName() {
        return familyName;
    }

    public void setFamilyName(String familyName) {
        this.familyName = familyName;
    }

    public String getPersonnummer() {
        return personnummer;
    }

    public void setPersonnummer(String personnummer) {
        this.personnummer = personnummer;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getFhirPatientId() {
        return fhirPatientId;
    }

    public void setFhirPatientId(String fhirPatientId) {
        this.fhirPatientId = fhirPatientId;
    }

    public String getFhirPractitionerId() {
        return fhirPractitionerId;
    }

    public void setFhirPractitionerId(String fhirPractitionerId) {
        this.fhirPractitionerId = fhirPractitionerId;
    }
}
