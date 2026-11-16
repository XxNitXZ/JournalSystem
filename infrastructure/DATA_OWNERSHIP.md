# MediNexus – Data ownership: PostgreSQL and HAPI FHIR

MediNexus stores data in two places: the local **PostgreSQL** database of
MediCore and the external, shared **HAPI FHIR R4 server** run by KTH. Each kind
of data has exactly **one authoritative owner**:

- **HAPI FHIR owns all clinical and care-organization data**: Patient,
  Practitioner, Observation, Condition, Encounter, DocumentReference (clinical
  notes), Organization and Location.
- **PostgreSQL owns local accounts**: login identity, the account holder's first
  and last name and personnummer, phone number, password hash, role and the logical IDs that link an account to its FHIR Patient or Practitioner,
  plus journal access requests (which account asks for which FHIR Patient, and the doctor's decision)
  and user-authored messages between local accounts.

FHIR clinical resources are never stored, cached or synchronized in PostgreSQL. Local message content
may contain sensitive user-authored information; it is not a copy or synchronization of FHIR records. This
document describes the code as it is; the [last section](#how-this-was-verified)
explains how it was checked.

## Overview

| Data | Owner | Where | Status |
|---|---|---|---|
| Account: e-mail (login), first and last name, personnummer, optional phone number, password hash, role | PostgreSQL | table `users` | implemented (names since V4, personnummer since V6, phone number since V9) |
| Link from an account to a FHIR Patient or Practitioner | PostgreSQL | `users.fhir_patient_id`, `users.fhir_practitioner_id` (logical IDs only) | implemented |
| Journal access requests (US-04.03) | PostgreSQL | table `journal_access_requests` (account ID, FHIR Patient logical ID or none yet, status, reviewer, times) | implemented (V7, V8) |
| Technical persistence check | PostgreSQL | table `persistence_check` (no application data) | implemented |
| Schema history | PostgreSQL (Flyway) | table `flyway_schema_history` | implemented |
| Messages between users | PostgreSQL | `messages` (sender/recipient account IDs, content, creation time) | persistence implemented (V10); sending, reading and authorization flows not yet implemented |
| Patient | HAPI FHIR | KTH server | implemented (read; register via `POST /api/patients`, US-04.01; profile via `GET /api/patients/{id}`, US-04.02; search via `POST /api/patients/search`; own record via `GET /api/patients/me`, US-04.03; gender updated via `PATCH /api/patients/{id}`, US-04.04), limited to Patients in `registered_patients` or linked to an account |
| Practitioner | HAPI FHIR | KTH server | implemented (read) |
| Observation, Condition, Encounter | HAPI FHIR | KTH server | implemented (read, create, search by patient); Encounters created and listed through MediCore since US-05.05 (`/api/patients/{id}/encounters`, `/api/patients/me/encounters`), Conditions (diagnoses) since US-05.02 (`/api/patients/{id}/conditions`, `/api/patients/me/conditions`); never copied to PostgreSQL |
| Clinical notes (DocumentReference) | HAPI FHIR | KTH server | implemented (US-05.01): created and listed through MediCore (`/api/patients/{id}/notes`, `/api/patients/me/notes`); note text and metadata only in HAPI FHIR, never copied to PostgreSQL |
| Organization, Location | HAPI FHIR | KTH server | implemented (read, create, update) |
| Login session | MediCore memory | in-memory HTTP session | implemented (not persisted) |

## PostgreSQL (implemented)

The schema is created only by Flyway (`src/main/resources/db/migration`);
Hibernate never changes it (`spring.jpa.hibernate.ddl-auto=none`).

| Table | Columns | Purpose | Migration |
|---|---|---|---|
| `persistence_check` | `id`, `description` | Technical JPA check; holds no application or patient data | V1 |
| `users` | `id`, `email` (unique), `password_hash`, `role` (`PATIENT`, `DOCTOR`, `STAFF`), `given_name`, `family_name`, `personnummer` (unique, 12 digits, nullable for older accounts), `phone_number` (optional, `+` and 6-15 digits, not unique), `fhir_patient_id`, `fhir_practitioner_id` | Local accounts, the account holder's display name, personal identity number and contact details, and FHIR ID references | V2, V3, V4, V6, V9 |
| `registered_patients` | `fhir_patient_id` (primary key), `registered_by` (account, `ON DELETE SET NULL`), `registered_at` | Logical IDs of the Patients that belong to MediCore: registered (created) in FHIR by MediCore or imported (existing Patients, reference only); no patient data | V5 |
| `journal_access_requests` | `id`, `user_id` (account, `ON DELETE CASCADE`), `fhir_patient_id` (references `registered_patients`, `ON DELETE RESTRICT`; `NULL` only while `AWAITING_RECORD`), `status` (`AWAITING_RECORD`, `PENDING`, `APPROVED`, `REJECTED`), `reviewed_by` (account, `ON DELETE SET NULL`), `created_at`, `reviewed_at` | A PATIENT account's own request for access to its journal (created only when the patient asks), the FHIR Patient with the same personnummer once registered, and the doctor's decision; at most one open (`AWAITING_RECORD`/`PENDING`) request per account (partial unique index) | V7, V8 |
| `flyway_schema_history` | (Flyway) | Applied migrations | – |
| `messages` | `id`, `sender_id`, `recipient_id`, `content`, `created_at` | Local user-authored messages; distinct participants, restrictive account foreign keys, nonblank content up to 5,000 characters, creation timestamp immutable through JPA | V10 |

There are five JPA entities, `auth/entity/User`,
`persistence/entity/PersistenceCheck`, `patient/registry/RegisteredPatient` and
`journalaccess/JournalAccessRequest` and `messaging/entity/Message`, and five repositories, `UserRepository`,
`IPersistenceCheckRepository`, `RegisteredPatientRepository` and
`JournalAccessRequestRepository` and `MessageRepository`. Message persistence has no application service or
endpoint yet; content must not be logged, and future access must be limited to authorized participants.
Existing application writes to PostgreSQL are performed by:

- `RegistrationService`: creates an account (e-mail, first and last name, personnummer, password hash, role);
  the personnummer is required for every new account, validated like a Patient's (12 digits, a real date, no check-digit validation, not in
  the future) and stored as 12 digits; a second account with the same personnummer is rejected (`409`,
  `PERSONNUMMER_TAKEN`). It is never returned by any account endpoint or logged;
- `RegistrationService` also stores the optional `phone_number` (V9), normalized (separators removed, digits
  unchanged; an optional `+` and 6-15 digits; not unique);
- `ProfileService`: the signed-in account only (identified by the session's account ID, never by request input).
  First name, last name and personnummer are **read-only** after registration (`GET /api/auth/profile` shows
  them). `PATCH /api/auth/profile` changes only `email` (with the current password, unique) and `phone_number`
  (add, change, remove); any other field is rejected (`FIELD_NOT_EDITABLE`). `POST /api/auth/password` changes
  only `password_hash` after verifying the current password;
- `AccountFhirIdentityService`: sets `fhir_patient_id` or
  `fhir_practitioner_id` with a conditional update. It is called only by the
  trusted operator command `FhirIdentityProvisioning`, never by a public
  endpoint (see [fhir/README.md](fhir/README.md#trusted-local-account-provisioning)).
- `PatientRegistry`: after a successful Patient registration in FHIR, or when DOCTOR/STAFF import an existing
  FHIR Patient (`POST /api/patients/import`, atomic `INSERT … ON CONFLICT DO NOTHING`), records its logical ID, the
  registering or importing account and the time in `registered_patients`; an import never writes to FHIR. Patient search and profile reads are limited to these
  IDs and to `users.fhir_patient_id` (see
  [fhir/README.md](fhir/README.md#patient-search-and-the-patient-registry)).
- `JournalAccessService.requestAccess`: the **only** place a journal access request is created, when the
  signed-in PATIENT asks for access (`POST /api/journal-access/me/request`): `PENDING` with the registered FHIR
  Patient that has the same personnummer, or `AWAITING_RECORD` without one, see
  [fhir/README.md](fhir/README.md#journal-access-requests-us-0403).
- `JournalAccessMatcher`: never creates a request; it attaches a newly registered (or later found) FHIR Patient
  with the same personnummer (never by name) to an existing `AWAITING_RECORD` request, which becomes `PENDING`.
- `JournalAccessService`: a DOCTOR's approval sets the request to `APPROVED` **and** sets
  `users.fhir_patient_id` in one transaction (both conditional updates); rejection sets `REJECTED`;
  reconsideration of a rejected request inserts a new `PENDING` request.

**PostgreSQL does not store** Patients' clinical data: no Patient names, birth dates, genders or
identifiers taken from FHIR, no addresses, diagnoses, observations, encounters, care organizations,
locations, FHIR resource versions, copies of FHIR resources or FHIR responses. There is no table for clinical
data and no JPA entity for any FHIR resource; `registered_patients` and `journal_access_requests` hold only
logical FHIR IDs, following the rule below. The personnummer in `users` is the **account holder's** own
identity, entered at account registration (like the account names); it is never copied from or written to
FHIR. It is personal data, so access to the database must be restricted accordingly.

## HAPI FHIR (implemented)

| Resource | MediCore operations | Client (`fhir/client`) | DTOs |
|---|---|---|---|
| Patient | read, conditional create, search, version-aware update (gender only) | `FhirPatientClient` | `patient/dto/PatientDto`, `PatientProfileDto`, `CreatePatientRequest`, `UpdatePatientRequest` |
| Practitioner | read | `FhirPractitionerClient` | `practitioner/dto/PractitionerDto` |
| Observation | read, create, search by patient | `FhirObservationClient` | `medicalrecord/dto/ObservationDto`, `CreateObservationRequest` |
| Condition | read, create, search by patient | `FhirConditionClient` | `medicalrecord/dto/ConditionDto`, `CreateConditionRequest`; API: `NewDiagnosisRequest`, `DiagnosisDto` |
| Encounter | read, create, search by patient | `FhirEncounterClient` | `medicalrecord/dto/EncounterDto`, `CreateEncounterRequest`; API: `NewEncounterRequest`, `EncounterSummaryDto` |
| DocumentReference | create, search by patient (`subject`) | `FhirDocumentReferenceClient` | API: `CreateClinicalNoteRequest`, `ClinicalNoteDto` (decoded text, no FHIR JSON) |
| Organization | read, create, update | `FhirOrganizationClient` | `organization/dto/OrganizationDto`, `OrganizationWriteRequest` |
| Location | read, create, update | `FhirLocationClient` | `location/dto/LocationDto`, `LocationWriteRequest` |

- The DTOs are immutable **read projections** and request records. They are
  never persisted and nothing caches FHIR responses.
- References **between** FHIR resources are FHIR data and are not mirrored in
  PostgreSQL: `Observation.subject`, `Condition.subject`, `Condition.encounter`, `Condition.recorder`, `Encounter.subject`,
  `Encounter.participant.individual`, `Encounter.serviceProvider`,
  `Encounter.location.location`, `DocumentReference.subject`,
  `DocumentReference.context.encounter`, `DocumentReference.author`, `Organization.partOf`,
  `Location.managingOrganization` and `Location.partOf`.
- Organization and Location IDs are **not** stored in PostgreSQL.
- The KTH server is shared and unauthenticated: only synthetic data, see
  [fhir/README.md](fhir/README.md#safety-rules-for-the-shared-kth-server).

## References from PostgreSQL to FHIR

| Column | Points to | Allowed for role |
|---|---|---|
| `users.fhir_patient_id` | `Patient/{id}` | `PATIENT` |
| `users.fhir_practitioner_id` | `Practitioner/{id}` | `DOCTOR`, `STAFF` |
| `registered_patients.fhir_patient_id` | `Patient/{id}` | – (registered by MediCore) |
| `journal_access_requests.fhir_patient_id` | `Patient/{id}` (must be in `registered_patients`; `NULL` while awaiting the record) | requests of `PATIENT` accounts |

Rules (enforced by migration V3, `AccountFhirIdentityService` and, for approvals, `JournalAccessService`):

- Only a **logical ID** (`[A-Za-z0-9.-]{1,64}`) is stored, never a URL, a
  version or any resource content.
- Each FHIR ID belongs to at most one account; an account has at most one link.
- Both columns may be `null`: an account does not need a FHIR identity.
- The resource is read once (`GET`) when the link is made, to confirm it exists
  and has that ID (for an approval also that its personnummer equals the account's). It is **not** re-checked
  later; because the KTH server is shared, a linked resource can later change or disappear.
- A `PATIENT` account gets `fhir_patient_id` either from the operator command or from a DOCTOR's approval of
  a journal access request that the patient made. Matching alone never links an account or creates a request.
- The IDs belong to the one configured KTH server. Do not change
  `FHIR_BASE_URL` and reinterpret existing links against another server (see
  [fhir/README.md](fhir/README.md#account-identity-references-single-server-lab)).

## Data that looks duplicated but is not

- **`users.email`/`users.phone_number` and FHIR contact data** (`Patient.telecom`):
  PostgreSQL is the **only** source of e-mail and phone numbers in MediCore.
  They are never written to FHIR (patient registration and updates send no
  telecom), and existing `Patient.telecom` values are neither used nor removed:
  an update writes them back unchanged. Patient profiles and My Medical Record
  show the e-mail and phone of the PATIENT account linked through
  `users.fhir_patient_id` (read on each request, never matched by name), or
  "Not available" without one.
- **`users.personnummer` and `Patient.identifier`**: the account's personnummer
  is entered by the account holder; the Patient's is entered by the clinician
  who registers the Patient and stored only in FHIR. They are compared to
  create journal access requests and again before an approval, but never
  copied into each other. A later change on the FHIR side is not mirrored.
- **`users.role` and Practitioner**: the role controls access in MediCore. It
  is not derived from FHIR and is not written to FHIR.
- **`users.given_name`/`family_name` and FHIR names** (`Patient.name`,
  `Practitioner.name`): the account names (V4) are the display name of the
  person who signs in, entered at registration. They are not read from or
  written to FHIR and are not synchronized. Accounts created before V4 have
  `NULL` names; MediPortal then shows a neutral fallback and never derives a
  name from the e-mail address. Account names cannot be changed after
  registration, and FHIR Patient names cannot be changed through MediCore
  either (only the gender can); existing differences are left as they are.
  An authorized identity-correction workflow does not exist.
- **Login session**: the in-memory HTTP session holds the authenticated
  `UserDto` (id, e-mail, role, first and last name). It is not persisted and
  contains no FHIR data or phone number. After an e-mail change the principal
  in the session is replaced with the new address; the session keeps referring
  to the same account ID.
- **MediPortal**: the frontend does not call the FHIR server and does not use
  `localStorage` or `sessionStorage`.

## Implemented and planned

| Area | Status |
|---|---|
| Accounts, roles, registration, login, session | implemented (PostgreSQL) |
| Linking accounts to FHIR Patient/Practitioner IDs | implemented: operator command; Patient links also by a DOCTOR's approval of a journal access request (US-04.03) |
| Journal access requests (US-04.03) | implemented: created only when the patient asks (`POST /api/journal-access/me/request`), automatic matching of waiting requests by personnummer, `GET /api/journal-access/me` (PATIENT), `/api/journal-access-requests/**` (DOCTOR), MediPortal patient dashboard and `/doctor/journal-requests` |
| Patient updates (US-04.04) | implemented: `PATCH /api/patients/{id}` (DOCTOR, STAFF) and "Edit Patient" in MediPortal; the gender only, in HAPI FHIR; names, personnummer, birth date and ID immutable; nothing stored in PostgreSQL |
| Account management | implemented: optional phone number at registration (V9); read-only names and personnummer; e-mail (with current password) and phone changes; password change (`/api/auth/profile`, `/api/auth/password`) |
| Own medical record (US-04.03) | implemented: `GET /api/patients/me` (PATIENT, linked accounts only) and MediPortal `/patient/record`; read directly from HAPI FHIR |
| FHIR clients for the seven resource types above | implemented; production callers: account linking, patient management, journal access and encounter management |
| Encounter management (US-05.05) | implemented: `POST/GET /api/patients/{id}/encounters` (DOCTOR, STAFF; Patients known to MediCore only), `GET /api/patients/me/encounters` (PATIENT with an approved link), `GET /api/practitioners` (Practitioners linked to DOCTOR/STAFF accounts); Encounters only in HAPI FHIR, no table, no migration; MediPortal patient profile and My Medical Record |
| Diagnoses (US-05.02) | implemented: `POST/GET /api/patients/{id}/conditions` (DOCTOR, STAFF; recording requires a linked Practitioner, which becomes the recorder; Patients known to MediCore; optional Encounter of that Patient), `GET /api/patients/me/conditions` (PATIENT with an approved link); FHIR R4 Condition only in HAPI FHIR (all of a Patient's Conditions are listed, also those of imported Patients), no table, no migration; MediPortal patient profile and My Medical Record |
| Clinical notes (US-05.01) | implemented: `POST/GET /api/patients/{id}/notes` (DOCTOR, STAFF; writing requires a linked Practitioner, which becomes the author; Patients known to MediCore and an existing Encounter of that Patient), `GET /api/patients/me/notes` (PATIENT with an approved link); FHIR R4 DocumentReference with plain UTF-8 text only in HAPI FHIR, no table, no migration; MediPortal patient profile and My Medical Record |
| Patient registration (US-04.01) | implemented: `POST /api/patients` (DOCTOR, STAFF) and MediPortal `/patients/new`; the Patient, including its personnummer, is stored only in HAPI FHIR |
| Patient profile (US-04.02) | implemented: `GET /api/patients/{id}` (DOCTOR, STAFF) and MediPortal `/patients/{id}`; read directly from HAPI FHIR on every request, never stored or cached (see the limitations in [fhir/README.md](fhir/README.md#patient-profile-us-0402)) |
| Patient search | implemented: `POST /api/patients/search` (DOCTOR, STAFF) and the search on the doctor and staff dashboards; only Patients known to MediCore (registry or account link) |
| Import existing patient | implemented: `POST /api/patients/import/search` (bounded, read-only discovery on the whole FHIR server) and `POST /api/patients/import` (DOCTOR, STAFF), MediPortal `/patients/import`; reference import into `registered_patients` only, no FHIR write, no demographics in PostgreSQL |
| Other REST endpoints or UI for clinical data (Observation, Condition, Organization, Location) | **not implemented** (`medicalrecord/repository` and `patient/repository` contain only `.gitkeep`) |
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
# JPA entities: only User, PersistenceCheck, RegisteredPatient and JournalAccessRequest
# (FhirIdentityProvisioning also matches: it only scans the User entity)
grep -rln "@Entity" src/main/java
# Repositories: UserRepository, IPersistenceCheckRepository, RegisteredPatientRepository and
# JournalAccessRequestRepository only
grep -rlnE "extends (Jpa|Crud|PagingAndSorting)Repository" src/main/java
# Schema: only persistence_check, users, registered_patients and journal_access_requests (V1–V9)
grep -rhnE "CREATE TABLE|ALTER TABLE|ADD COLUMN" src/main/resources/db/migration
# No other persistence, caching or browser storage (expected: no output)
grep -rnE "JdbcTemplate|EntityManager|@Cacheable|CacheManager" src/main/java
grep -rnE "localStorage|sessionStorage|/fhir|hapi-fhir" MediPortal/src
# FHIR clients used outside the fhir package: account linking, patient management, journal access, encounters, notes and diagnoses
grep -rlnE "Fhir[A-Za-z]+Client" src/main/java | grep -v "/fhir/"
```

Expected result of the last command: `auth/service/AccountFhirIdentityService.java`,
`journalaccess/JournalAccessMatcher.java`, `journalaccess/JournalAccessService.java`,
`medicalrecord/service/ClinicalNoteService.java`, `medicalrecord/service/DiagnosisService.java`, `medicalrecord/service/EncounterService.java`,
`patient/dto/PatientDiscoveryResponse.java` (documentation reference only), `patient/service/PatientImportService.java`,
`patient/service/PatientService.java`,
`practitioner/service/PractitionerDirectory.java` and `provisioning/FhirIdentityProvisioning.java`.
MediPortal calls MediCore, never the FHIR server. Runtime verification of a
complete patient journey and of FHIR outages is planned in NIT-137 and NIT-138.
