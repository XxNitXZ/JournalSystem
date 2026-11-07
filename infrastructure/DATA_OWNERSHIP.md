# MediNexus – Data ownership: PostgreSQL and HAPI FHIR

MediNexus stores data in two places: the local **PostgreSQL** database of
MediCore and the external, shared **HAPI FHIR R4 server** run by KTH. Each kind
of data has exactly **one authoritative owner**:

- **HAPI FHIR owns all clinical and care-organization data**: Patient,
  Practitioner, Observation, Condition, Encounter, Organization and Location.
- **PostgreSQL owns local accounts**: login identity, password hash, role and the
  logical IDs that link an account to its FHIR Patient or Practitioner.

Clinical data is never stored, cached or synchronized in PostgreSQL. This
document describes the code as it is; the [last section](#how-this-was-verified)
explains how it was checked.

## Overview

| Data | Owner | Where | Status |
|---|---|---|---|
| Account: e-mail (login), password hash, role | PostgreSQL | table `users` | implemented |
| Link from an account to a FHIR Patient or Practitioner | PostgreSQL | `users.fhir_patient_id`, `users.fhir_practitioner_id` (logical IDs only) | implemented |
| Technical persistence check | PostgreSQL | table `persistence_check` (no application data) | implemented |
| Schema history | PostgreSQL (Flyway) | table `flyway_schema_history` | implemented |
| Messages between users | PostgreSQL | – | **planned**, not implemented |
| Patient, Practitioner | HAPI FHIR | KTH server | implemented (read) |
| Observation, Condition, Encounter | HAPI FHIR | KTH server | implemented (read, create, search by patient) |
| Organization, Location | HAPI FHIR | KTH server | implemented (read, create, update) |
| Login session | MediCore memory | in-memory HTTP session | implemented (not persisted) |

## PostgreSQL (implemented)

The schema is created only by Flyway (`src/main/resources/db/migration`);
Hibernate never changes it (`spring.jpa.hibernate.ddl-auto=none`).

| Table | Columns | Purpose | Migration |
|---|---|---|---|
| `persistence_check` | `id`, `description` | Technical JPA check; holds no application or patient data | V1 |
| `users` | `id`, `email` (unique), `password_hash`, `role` (`PATIENT`, `DOCTOR`, `STAFF`), `fhir_patient_id`, `fhir_practitioner_id` | Local accounts and their FHIR ID references | V2, V3 |
| `flyway_schema_history` | (Flyway) | Applied migrations | – |

There are two JPA entities, `auth/entity/User` and
`persistence/entity/PersistenceCheck`, and two repositories, `UserRepository`
and `IPersistenceCheckRepository`. Data is written to PostgreSQL only by:

- `RegistrationService`: creates an account (e-mail, password hash, role);
- `AccountFhirIdentityService`: sets `fhir_patient_id` or
  `fhir_practitioner_id` with a conditional update. It is called only by the
  trusted operator command `FhirIdentityProvisioning`, never by a public
  endpoint (see [fhir/README.md](fhir/README.md#trusted-local-account-provisioning)).

**PostgreSQL does not store** names, birth dates, addresses, diagnoses,
observations, encounters, care organizations, locations, FHIR resource versions,
copies of FHIR resources or FHIR responses. There is no table for clinical data
and no JPA entity for any FHIR resource.

## HAPI FHIR (implemented)

| Resource | MediCore operations | Client (`fhir/client`) | DTOs |
|---|---|---|---|
| Patient | read | `FhirPatientClient` | `patient/dto/PatientDto` |
| Practitioner | read | `FhirPractitionerClient` | `practitioner/dto/PractitionerDto` |
| Observation | read, create, search by patient | `FhirObservationClient` | `medicalrecord/dto/ObservationDto`, `CreateObservationRequest` |
| Condition | read, create, search by patient | `FhirConditionClient` | `medicalrecord/dto/ConditionDto`, `CreateConditionRequest` |
| Encounter | read, create, search by patient | `FhirEncounterClient` | `medicalrecord/dto/EncounterDto`, `CreateEncounterRequest` |
| Organization | read, create, update | `FhirOrganizationClient` | `organization/dto/OrganizationDto`, `OrganizationWriteRequest` |
| Location | read, create, update | `FhirLocationClient` | `location/dto/LocationDto`, `LocationWriteRequest` |

- The DTOs are immutable **read projections** and request records. They are
  never persisted and nothing caches FHIR responses.
- References **between** FHIR resources are FHIR data and are not mirrored in
  PostgreSQL: `Observation.subject`, `Condition.subject`, `Encounter.subject`,
  `Encounter.participant.individual`, `Encounter.serviceProvider`,
  `Encounter.location.location`, `Organization.partOf`,
  `Location.managingOrganization` and `Location.partOf`.
- Organization and Location IDs are **not** stored in PostgreSQL.
- The KTH server is shared and unauthenticated: only synthetic data, see
  [fhir/README.md](fhir/README.md#safety-rules-for-the-shared-kth-server).

## References from PostgreSQL to FHIR

| Column | Points to | Allowed for role |
|---|---|---|
| `users.fhir_patient_id` | `Patient/{id}` | `PATIENT` |
| `users.fhir_practitioner_id` | `Practitioner/{id}` | `DOCTOR`, `STAFF` |

Rules (enforced by migration V3 and `AccountFhirIdentityService`):

- Only a **logical ID** (`[A-Za-z0-9.-]{1,64}`) is stored, never a URL, a
  version or any resource content.
- Each FHIR ID belongs to at most one account; an account has at most one link.
- Both columns may be `null`: an account does not need a FHIR identity.
- The resource is read once (`GET`) when the link is made, to confirm it exists
  and has that ID. It is **not** re-checked later; because the KTH server is
  shared, a linked resource can later change or disappear.
- The IDs belong to the one configured KTH server. Do not change
  `FHIR_BASE_URL` and reinterpret existing links against another server (see
  [fhir/README.md](fhir/README.md#account-identity-references-single-server-lab)).

## Data that looks duplicated but is not

- **`users.email` and FHIR contact data** (for example `Patient.telecom`): the
  e-mail is MediCore's login identifier; FHIR contact data is clinical
  administration. Neither is copied into the other and they are not
  synchronized.
- **`users.role` and Practitioner**: the role controls access in MediCore. It
  is not derived from FHIR and is not written to FHIR.
- **Login session**: the in-memory HTTP session holds the authenticated
  `UserDto` (id, e-mail, role). It is not persisted and contains no FHIR data.
- **MediPortal**: the frontend does not call the FHIR server and does not use
  `localStorage` or `sessionStorage`.

## Implemented and planned

| Area | Status |
|---|---|
| Accounts, roles, registration, login, session | implemented (PostgreSQL) |
| Linking accounts to FHIR Patient/Practitioner IDs | implemented, operator command only |
| FHIR clients for the seven resource types above | implemented; no production callers except account linking |
| REST endpoints or UI for clinical data | **not implemented** (`patient` and `medicalrecord` controller/service/repository packages contain only `.gitkeep`) |
| Messaging | **not implemented** (`messaging` packages contain only `.gitkeep`; no tables) |
| Self-service account linking, search for Organization or Location, Observation/Condition linked to an Encounter | not implemented |

## Rules for future changes

- New clinical or care-organization data belongs in FHIR. Do not add
  PostgreSQL tables, JPA entities or repositories for it, and do not use the
  empty `medicalrecord/repository` or `patient/repository` packages to store
  FHIR data in PostgreSQL.
- Do not cache or copy FHIR resources or responses into PostgreSQL. Store a
  logical FHIR ID when a local record must point to a FHIR resource.
- When planned PostgreSQL data such as messaging is implemented, add its tables
  to this document with owner and purpose. References to FHIR resources must
  be logical IDs, not copies.
- Change the schema only with new Flyway migrations; never edit applied ones.

## How this was verified

This document was checked against the source code by **static inspection only**
(no database connection, no FHIR requests). From the repository root:

```bash
# JPA entities: only User and PersistenceCheck
# (FhirIdentityProvisioning also matches: it only scans the User entity)
grep -rln "@Entity" src/main/java
# Repositories: UserRepository and IPersistenceCheckRepository only
grep -rlnE "extends (Jpa|Crud|PagingAndSorting)Repository" src/main/java
# Schema: only persistence_check and users (V1–V3)
grep -rhnE "CREATE TABLE|ALTER TABLE|ADD COLUMN" src/main/resources/db/migration
# No other persistence, caching or browser storage (expected: no output)
grep -rnE "JdbcTemplate|EntityManager|@Cacheable|CacheManager" src/main/java
grep -rniE "fhir|localStorage|sessionStorage" MediPortal/src
# FHIR clients used outside the fhir package: account linking only
grep -rlnE "Fhir[A-Za-z]+Client" src/main/java | grep -v "/fhir/"
```

Expected result of the last command: `auth/service/AccountFhirIdentityService.java`
and `provisioning/FhirIdentityProvisioning.java`. Runtime verification of a
complete patient journey and of FHIR outages is planned in NIT-137 and NIT-138.
