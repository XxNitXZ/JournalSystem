# MediCore Lab 2 – Service architecture

This document describes the target microservice split of the Lab 1 monolith, what each service owns, and how
the services depend on each other. It is built up during EPIC-01:

| Section | Task | Status |
|---|---|---|
| Services, ownership, cross-service dependencies | NIT-323 | ✅ this version |
| Database schemas and Flyway split | NIT-324 | ✅ [§7](#7-database-schemas) |
| Gateway routes and OpenAPI | NIT-326 | ✅ [§8](#8-gateway-routes-and-openapi) |
| Authentication (JWT cookie) | NIT-327 | ✅ [§9](#9-authentication-jwt-cookie) |
| Error format and timeouts | NIT-328 | ✅ [§10](#10-error-format-and-timeouts) |
| Repository layout and Maven modules | NIT-788 | ✅ [§11](#11-repository-layout-and-maven-modules) |

Data ownership between PostgreSQL and HAPI FHIR is unchanged and described in
[`infrastructure/DATA_OWNERSHIP.md`](../infrastructure/DATA_OWNERSHIP.md): HAPI FHIR stays the source of truth for
all clinical data, and PostgreSQL holds only accounts, logical IDs, journal access decisions and messages.

## 1. Services

| Service | Tech | Owns | Lab 1 packages (`com.medinexus.medicore.*`) |
|---|---|---|---|
| **Identity** | Spring Boot | Accounts, roles, passwords, profile, account ↔ FHIR id links, journal access requests and decisions, login | `auth`, `journalaccess`, `config` (security parts), `persistence`*, `provisioning` (operator command) |
| **Clinical** | Spring Boot | All FHIR reads/writes: Patient, Encounter, Condition, Observation, DocumentReference, Practitioner, Organization, Location; the patient registry | `patient`, `medicalrecord`, `practitioner`, `organization`, `location`, `fhir` |
| **Messaging** | Spring Boot | Messages between patients and clinicians | `messaging` |
| **Images** (new) | Node.js | Patient images and annotated copies | – |
| **Search** (new) | Quarkus (reactive) | No data of its own; read-only FHIR queries | – |

\* `persistence_check` is a Lab 1 technical table with no application data. It is dropped in the split (NIT-324),
because every service gets a real health check instead.

Shared code (JWT verification, error DTO, FHIR client config) goes in the `common` Maven module (NIT-788).
`PasswordEncoderConfig` stays in Identity only, and `CorsConfig` is no longer needed behind a same-origin Ingress.

## 2. Endpoints

All paths are unchanged from Lab 1, so MediPortal keeps working. Every path in this table is called by MediPortal
today, except `/v3/api-docs` and `/swagger-ui`.

### Identity

| Method | Path | Roles | Lab 1 controller |
|---|---|---|---|
| POST | `/api/auth/register` | public | `RegistrationController` |
| POST | `/api/auth/login` | public | `AuthenticationController` |
| POST | `/api/auth/logout` | authenticated | Spring Security logout filter (`SecurityConfig`) |
| GET | `/api/auth/me` | authenticated | `AuthenticationController` |
| GET | `/api/auth/csrf` | public | `AuthenticationController`, **removed** with JWT auth (NIT-327) |
| GET, PATCH | `/api/auth/profile` | authenticated | `ProfileController` |
| POST | `/api/auth/password` | authenticated | `ProfileController` |
| GET | `/api/journal-access/me` | PATIENT | `JournalAccessController` |
| POST | `/api/journal-access/me/request` | PATIENT | `JournalAccessController` |
| GET | `/api/journal-access-requests` | DOCTOR | `JournalAccessController` |
| GET | `/api/journal-access-requests/pending/count` | DOCTOR | `JournalAccessController` |
| GET | `/api/journal-access-requests/{id}` | DOCTOR | `JournalAccessController` |
| POST | `/api/journal-access-requests/{id}/approve` · `/reject` · `/reopen` | DOCTOR | `JournalAccessController` |
| GET | `/api/health` | public | `HealthController` (kept in Identity because MediPortal calls it) |

### Clinical

| Method | Path | Roles | Lab 1 controller |
|---|---|---|---|
| POST | `/api/patients` | DOCTOR, STAFF | `PatientController` (register a new FHIR Patient) |
| GET | `/api/patients/{id}` | DOCTOR, STAFF | `PatientController` |
| PATCH | `/api/patients/{id}` | DOCTOR, STAFF | `PatientController` |
| GET | `/api/patients/me` | PATIENT | `PatientController` |
| POST | `/api/patients/search` | DOCTOR, STAFF | `PatientController` (registry-restricted search, query in body) |
| POST | `/api/patients/import/search` · `/api/patients/import` | DOCTOR, STAFF | `PatientImportController` |
| GET, POST | `/api/patients/{id}/encounters` | DOCTOR, STAFF | `EncounterController` |
| GET | `/api/patients/me/encounters` | PATIENT | `EncounterController` |
| GET, POST | `/api/patients/{id}/conditions` | DOCTOR, STAFF | `DiagnosisController` |
| GET | `/api/patients/me/conditions` | PATIENT | `DiagnosisController` |
| GET, POST | `/api/patients/{id}/observations` | DOCTOR, STAFF | `ObservationController` |
| GET | `/api/patients/me/observations` | PATIENT | `ObservationController` |
| GET, POST | `/api/patients/{id}/notes` | DOCTOR, STAFF | `ClinicalNoteController` (DocumentReference) |
| GET | `/api/patients/me/notes` | PATIENT | `ClinicalNoteController` |
| GET | `/api/practitioners` | DOCTOR, STAFF | `PractitionerController` |
| GET | `/api/organizations` | DOCTOR, STAFF | `OrganizationController` |

Location has no endpoint of its own. `FhirLocationClient` is only used when creating Encounters.

### Messaging

| Method | Path | Roles | Lab 1 controller |
|---|---|---|---|
| GET | `/api/messages/recipients` | PATIENT | `MessageController` |
| POST | `/api/messages` | PATIENT | `MessageController` |
| GET | `/api/messages/conversations` · `/conversations/{recipientId}` | PATIENT | `MessageController` |
| GET | `/api/messages/inbox` · `/inbox/{patientId}` | DOCTOR, STAFF | `MessageController` |
| POST | `/api/messages/inbox/{patientId}/reply` | DOCTOR, STAFF | `MessageController` |

Note: `{patientId}` and `{recipientId}` here are **account ids** (`users.id`), not FHIR ids.

### Every service

`/actuator/health` (Kubernetes probes), `/v3/api-docs`, `/swagger-ui` (springdoc). These are not exposed through the
Ingress.

## 3. Tables

| Table (Flyway) | Owner | Cross-service foreign keys today | After the split |
|---|---|---|---|
| `users` (V2, V3, V4, V6, V9) | Identity | – | unchanged |
| `journal_access_requests` (V7, V8) | Identity | `fhir_patient_id → registered_patients` (Clinical) | FK dropped; Identity checks the id with Clinical before it writes (see D2) |
| `registered_patients` (V5) | Clinical | `registered_by → users` (Identity) | FK dropped; plain account id |
| `messages` (V10) | Messaging | `sender_id`, `recipient_id → users` (Identity) | FKs dropped; plain account ids, checked through Identity on send (see D4) |
| `persistence_check` (V1) | – | – | dropped |

Foreign keys can't cross service boundaries: each service has its own schema and DB user (NIT-324), and the
database can't enforce references into another service's schema.

## 4. FHIR clients

All `fhir.client.*` classes, the mappers and `FhirClientConfig` move to **Clinical**.

| Client | FHIR resource | Used by (Lab 1) |
|---|---|---|
| `FhirPatientClient` | Patient | patient, medicalrecord, **journalaccess**, **auth** (provisioning) |
| `FhirPractitionerClient` | Practitioner | practitioner, medicalrecord, **auth** (provisioning) |
| `FhirEncounterClient` | Encounter | medicalrecord |
| `FhirConditionClient` | Condition | medicalrecord |
| `FhirObservationClient` | Observation | medicalrecord |
| `FhirDocumentReferenceClient` | DocumentReference | medicalrecord (clinical notes) |
| `FhirOrganizationClient` | Organization | organization, medicalrecord |
| `FhirLocationClient` | Location | medicalrecord |
| `FhirServerClient`, `FhirRequestExecutor`, `FhirReferences`, `FhirExceptionTranslator` | – (infrastructure) | all of the above |

The **bold** users are in Identity, so they lose direct FHIR access. D2 and D6 below replace those calls with
calls to Clinical. **Search** (Quarkus) gets its own reactive FHIR client in EPIC-04 and doesn't reuse these
classes.

## 5. Cross-service dependencies

These are every place where code in one future service uses another service's classes or tables, and how each
one is resolved. "Internal endpoint" means a `/internal/**` path that is **not** routed by the Ingress and is only
callable by another service with a service token (see [§6](#6-input-for-later-epic-01-tasks)).

| # | Where (Lab 1) | Needs | From | Solution |
|---|---|---|---|---|
| **D1** | `PatientAccessService` (used by `patient`, `medicalrecord`); `users.findById(principal)` role checks in `ClinicalNoteService`, `DiagnosisService`, `EncounterService`, `ObservationService`, `PatientService`, `PatientImportService`, `PractitionerDirectory`, `OrganizationDirectory`, `MessageService` | Caller's role, FHIR patient id, FHIR practitioner id | Identity (`users`) | **JWT claims** `role`, `fhirPatientId`, `fhirPractitionerId`. No call. `PatientAccessService` becomes a ~10-line check on claims in `common` |
| **D2** | `JournalAccessMatcher`, `JournalAccessService` (Identity) → `PatientRegistry`, `FhirPatientClient`, `PatientMapper` | Registered patients matching a personnummer; a patient's details for the doctor's review | Clinical | **Internal endpoints on Clinical:** `GET /internal/patients/registered?personnummer=…` → matching registered FHIR ids (plus whether each is free); `GET /internal/patients/{id}` → `PatientDto` |
| **D3** | `PatientService`, `PatientImportService` (Clinical) → `JournalAccessMatcher.onPatientRegistered` / import hook | Tell Identity that a patient with personnummer X was registered or imported, so an `AWAITING_RECORD` request can move to `PENDING` | Identity | **Internal endpoint on Identity:** `POST /internal/journal-access/patient-registered {fhirPatientId, personnummer}`. A failed call is logged but doesn't fail the registration, because `resolveAwaiting` already retries when the patient reads their status |
| **D4** | `MessageService` → `users` | Recipient list (DOCTOR/STAFF with names and roles); names and roles of a conversation's participants; checking a recipient exists and has the right role | Identity | **Internal endpoints on Identity:** `GET /internal/users?roles=DOCTOR,STAFF`, `GET /internal/users?ids=1,2,3` → `{id, displayName, role}`. The sender's own role comes from the JWT (D1) |
| **D5** | `PatientRegistry.isKnown/knownIds` → `users.fhir_patient_id`; `PatientService.linkedContact` → `users` e-mail and phone; `PractitionerDirectory` → `users.findClinicianPractitionerIds` | Which FHIR patients are linked to accounts; a linked patient's contact info; which practitioners have accounts | Identity | **Linked patients:** whenever Identity links an account to a FHIR Patient, that Patient goes into Clinical's `registered_patients` (approval only links registered patients already; provisioning registers through D6). `isKnown` then only checks `registered_patients`. **Contact and practitioners:** internal endpoints on Identity: `GET /internal/users/by-fhir-patient/{id}/contact`, `GET /internal/users/clinician-practitioner-ids` |
| **D6** | `AccountFhirIdentityService` + `FhirIdentityProvisioning` (operator command, Identity) → `FhirPatientClient`, `FhirPractitionerClient` | Check that a FHIR Patient or Practitioner exists before linking it to an account | Clinical | **Internal endpoints on Clinical:** `POST /internal/registry/{fhirPatientId}` (checks it exists, then registers it, which also covers D5) and `GET /internal/practitioners/{id}` (exists or 404). Identity has no FHIR dependency at all |
| **D7** | `organization`, `location` → `medicalrecord.dto.ObservationDto.*` (Concept, Identifier, …); `fhir` → `patient/medicalrecord/organization/location/practitioner` DTOs | Shared DTO types | – (all Clinical) | No action: all of these end up in Clinical |
| **D8** | `auth.service.RegistrationService` → `patient.validation.Personnummer` | Personnummer validation | – | `Personnummer` / `PatientPersonnummer` (pure validation, no dependencies) move to `common`, so Identity and Clinical both use the same rules |

**Result:** internal calls only go **Identity ↔ Clinical** (D2, D3, D5, D6) and **Messaging → Identity** (D4).
Images and Search only depend on the JWT, plus FHIR in Search's case. There are no cycles at startup: every
internal call is made per request, and a failing call returns 502/504 (NIT-328) instead of blocking a service
from starting.

## 6. Input for later EPIC-01 tasks

Found during the inventory. To be decided and documented in the named task:

- ~~NIT-327 (auth)~~: stale claims, service tokens and CSRF are handled in [§9](#9-authentication-jwt-cookie).
  `/api/auth/csrf` remains as a no-op until MediPortal stops calling it (NIT-338).
- ~~NIT-326 (routes)~~: both route findings are handled in [§8](#8-gateway-routes-and-openapi).
## 7. Database schemas

There is one PostgreSQL instance (`medinexus_db`) with **one schema and one database user per service**. The
isolation is enforced by PostgreSQL, not just agreed on: each user owns its own schema and has no rights anywhere
else.

| Schema | DB user | Tables | Flyway history |
|---|---|---|---|
| `identity` | `identity_svc` | `users`, `journal_access_requests` | `identity.flyway_schema_history` |
| `clinical` | `clinical_svc` | `registered_patients` | `clinical.flyway_schema_history` |
| `messaging` | `messaging_svc` | `messages` | `messaging.flyway_schema_history` |
| `images` (EPIC-03) | `images_svc` | image rows | Node migration |
| `public` | `medinexus` (owner) | Lab 1 monolith tables, read-only after the cut-over | Lab 1 `flyway_schema_history` |

Each user's `search_path` is its own schema, so the services use unqualified table names (`users`, not
`identity.users`). In each Spring service, `spring.flyway.schemas=<schema>` and `spring.flyway.default-schema=<schema>`
(set in EPIC-02).

**Files** (in `infrastructure/database/`):

| File | What it does | When |
|---|---|---|
| `init-schemas.sql` | Creates the 3 users (passwords passed in), their schemas and their rights. Safe to run again | Once per database, before the services first start |
| `migrations/<service>/V1__baseline.sql` | Each service's first Flyway migration: its tables exactly as after Lab 1's V1–V10, minus the cross-schema foreign keys (§3) and `persistence_check` | Moved into each service by NIT-788; run by that service's Flyway on first start |
| `migrate-lab1-data.sql` | One-off copy of all Lab 1 rows (ids kept) into the new schemas, plus linked patients into the registry (D5). One transaction; checks row counts; refuses to run twice | Once, at the EPIC-02 cut-over: after each service has started once, with the monolith stopped |

`journal_access_requests.fhir_patient_id` lost its foreign key, so it got the same format check as
`registered_patients.fhir_patient_id` instead. Identity checks that the id exists through Clinical (D2).

**Verified** in a throwaway `postgres:18` container: Lab 1 V1–V10 with sample data → `init-schemas.sql` (twice) →
baselines applied as each service user → copy. All rows were identical column by column, the sequences continued
after the copied ids, a second copy was refused, and 8 cross-service reads and writes all failed with
`permission denied`.

## 8. Gateway routes and OpenAPI

### Services inside the cluster

These names become the Kubernetes Service names in EPIC-05 (NIT-360). Services call each other by these names, e.g.
`http://identity:8080/internal/users?ids=1,2`.

| Service name | Service port → container port | Local dev port (outside Kubernetes) |
|---|---|---|
| `mediportal` | 80 → 8080 (nginx-unprivileged) | 5173 (Vite dev server) |
| `identity` | 8080 → 8080 | 8081 |
| `clinical` | 8080 → 8080 | 8082 |
| `messaging` | 8080 → 8080 | 8083 |
| `search` | 8080 → 8080 | 8084 |
| `images` | 3000 → 3000 | 3000 |
| `postgres` | 5432 → 5432 | 5432 (`medinexus-postgres` container) |

Locally, each service needs its own port because they all run on one machine. In the cluster every pod has its own
address, so all Spring and Quarkus services can use 8080.

### Ingress routes

One Ingress at `http://localhost` (kind maps host ports 80/443 to the ingress controller, NIT-365). All rules use
`pathType: Prefix`.

| # | Path prefix | → Service:port | Covers |
|---|---|---|---|
| 1 | `/api/auth` | `identity:8080` | register, login, logout, me, profile, password |
| 2 | `/api/journal-access` | `identity:8080` | `/me`, `/me/request` (PATIENT) |
| 3 | `/api/journal-access-requests` | `identity:8080` | doctor review list, count, detail, decisions |
| 4 | `/api/health` | `identity:8080` | MediPortal's health check |
| 5 | `/api/patients` | `clinical:8080` | everything under it, including `/me/*`, `/search`, `/import*` |
| 6 | `/api/practitioners` | `clinical:8080` | |
| 7 | `/api/organizations` | `clinical:8080` | |
| 8 | `/api/messages` | `messaging:8080` | |
| 9 | `/api/images` | `images:3000` | EPIC-03 |
| 10 | `/api/search` | `search:8080` | EPIC-04 |
| 11 | `/` | `mediportal:80` | the React app and its static files |

**How matching works.** Kubernetes `Prefix` matches **whole path segments**: `/api/patients` matches
`/api/patients` and `/api/patients/me/notes`, but not `/api/patientsfoo`. That is why rules 2 and 3 are separate:
`/api/journal-access` does not match `/api/journal-access-requests`. When several rules match, the **longest prefix
wins**, so `/` (rule 11) only gets requests that no `/api/...` rule matched.

**What is not routed, so it can't be reached from outside:**

| Path | Why it stays internal |
|---|---|
| `/internal/**` | Service-to-service calls (D2–D6), protected by a service token (NIT-327) |
| `/actuator/**`, `/q/health/**`, `/health` | Kubernetes probes call pods directly, not through the Ingress |
| `/v3/api-docs`, `/swagger-ui`, `/q/openapi`, `/openapi.json` | Developer docs; read them with port-forward (below) |

No rule points at them, so they fall through to rule 11 (`/`) like any unknown path, including unknown `/api/...`
paths. The MediPortal nginx then answers with its SPA page or a 404. No backend service is ever reached through
these paths from outside the cluster. (Checked with a script: all 34 paths MediPortal calls today route to the
intended service, and `/internal/*`, `/actuator/*` and `/v3/api-docs` land on `mediportal`.)

### OpenAPI per service

Every service publishes its own spec. It is the contract that MediPortal (NIT-338) and the Hurl tests (EPIC-06) rely
on, and it must match the endpoint tables in [§2](#2-endpoints).

| Service | Spec | UI | Tooling |
|---|---|---|---|
| identity, clinical, messaging | `/v3/api-docs` | `/swagger-ui.html` | springdoc (already in Lab 1) |
| search | `/q/openapi` | `/q/swagger-ui` | `quarkus-smallrye-openapi` |
| images | `/openapi.json` | `/docs` | a static `openapi.yaml` served by the app (or `swagger-jsdoc`) |

Reading a spec in the cluster:

```bash
kubectl port-forward svc/identity 8081:8080
# then open http://localhost:8081/swagger-ui.html
```

### Local development without Kubernetes (Vite proxy)

So that the browser sees one origin in dev too, MediPortal's Vite dev server forwards `/api/*` with the same
prefixes as the Ingress (configured in NIT-338). Requests go to relative paths (`/api/...`); there is no
`VITE_API_BASE_URL` or `http://localhost:8080` any more.

| Prefix | → |
|---|---|
| `/api/auth`, `/api/journal-access`, `/api/journal-access-requests`, `/api/health` | `http://localhost:8081` |
| `/api/patients`, `/api/practitioners`, `/api/organizations` | `http://localhost:8082` |
| `/api/messages` | `http://localhost:8083` |
| `/api/search` | `http://localhost:8084` |
| `/api/images` | `http://localhost:3000` |

Vite proxy keys match by plain string prefix, not by whole segment, so `/api/journal-access` would also catch
`/api/journal-access-requests`. Here that's harmless, because both go to Identity.

## 9. Authentication (JWT cookie)

Implemented in the monolith first (NIT-327), in the self-contained package `com.medinexus.medicore.security.jwt`,
which NIT-788 moved into the shared `services/common` module, so every Spring service verifies tokens the same way. Node and Quarkus
verify the same HS256 tokens with `jose` and `smallrye-jwt`.

**How it works**

1. `POST /api/auth/login` checks the password, then signs a JWT and sets it as the cookie `medicore_token`
   (`HttpOnly`, `SameSite=Strict`, `Path=/`, `Max-Age=1800`; `Secure` when `JWT_COOKIE_SECURE=true`). There is no
   HTTP session and no `JSESSIONID`.
2. On every request, Spring Security's OAuth2 resource server reads the token from the cookie, or from an
   `Authorization: Bearer` header for service calls. It checks the signature, expiry (60 s clock skew), issuer
   and claim shape, then turns the claims into the same `UserDto` principal the controllers used before. Any
   failure gives **401**.
3. `GET /api/auth/me`, `GET /api/journal-access/me` and a profile e-mail change **re-issue** the cookie from the
   account as stored now. The 30 minutes therefore slide while MediPortal is in use, and new claims, such as a
   `fhirPatientId` after journal access is approved, take effect without signing in again.
4. `POST /api/auth/logout` overwrites the cookie with an expired one.

**User token claims**

| Claim | Example | Notes |
|---|---|---|
| `iss` | `medicore` | required on every token |
| `sub` | `42` | account id (`identity.users.id`) |
| `typ` | `user` | `user` or `service` |
| `role` | `PATIENT` | `PATIENT`, `DOCTOR`, `STAFF` (`SERVICE` for service tokens) |
| `email`, `givenName`, `familyName` | | for the principal; names may be absent |
| `fhirPatientId` / `fhirPractitionerId` | `syn-patient-1` | only if the account is linked (D1) |
| `iat`, `exp` | | 30 minutes apart |

Never in a token: password hash, personnummer, phone number, clinical data. The payload is signed, not encrypted:
anyone holding the cookie can read it. `HttpOnly` keeps it away from page scripts.

**Service tokens.** `JwtTokenService.serviceToken("clinical")` signs a 60-second token with `typ=service`,
`role=SERVICE` and `sub=<service name>`. It is sent as `Authorization: Bearer …` to `/internal/**`, which accepts
**only** `ROLE_SERVICE`. User tokens get 403 there, and service tokens get 403 on every user endpoint.

**Key.** One HS256 secret, `JWT_SECRET` (at least 32 bytes), shared by all services through a Kubernetes Secret
(EPIC-05). Startup fails without it. The secret is never logged (`JwtProperties.toString()` hides it). Tests use a
fixed test-only key in `services/monolith/src/test/resources/config/application.properties`.

**CSRF.** No CSRF tokens. The browser never sends a `SameSite=Strict` cookie on a request started by another site,
and behind the Ingress the frontend and the API share one origin. `GET /api/auth/csrf` returns a dummy token until
MediPortal stops asking for it (NIT-338).

**Trade-offs (accepted for Lab 2)**

- **Logout can't revoke a token.** Logout removes the cookie from that browser, but nothing on the server remembers
  the token. A token copied before logout keeps working until it expires, at most 30 minutes. The same applies
  after a password change. Covered by `JwtCookieAuthenticationIntegrationTest.aCopiedTokenStaysValidUntilItExpires…`.
  The Lab 1 session could be invalidated; a stateless token can't without a server-side blocklist, which would
  bring back the shared state we removed. Keycloak's access tokens in Lab 3 behave the same way.
- **One shared key.** With HS256, any service that holds the key could also sign a user token. RS256 (Identity
  signs with a private key, the others verify with the public key) avoids that, but services would then need
  another way to get service tokens. Lab 3 moves signing to Keycloak (RS256), and the verification code barely
  changes.
- **A changed role or deleted account** is noticed on the next `GET /api/auth/me`, which re-reads the database and
  returns 401 for a deleted account. Other endpoints trust the token until it expires.

## 10. Error format and timeouts

**One error body everywhere** (`common.exception.ApiError`):

```json
{"status":409,"error":"Conflict","message":"The request conflicts with the current state.","code":"EMAIL_TAKEN"}
```

- `message` is a fixed text chosen by status, **never** an exception message or user input, so an error can't
  leak a name or personnummer.
- `code` is the machine-readable reason MediPortal reads (`VERSION_CONFLICT`, `PERSONNUMMER_TAKEN`, …). It is left
  out when there is none.
- **Who produces it:**
  - Each controller's own `@ExceptionHandler`s keep their status codes and codes, and return `ApiError`.
  - `GlobalExceptionHandler` catches everything else: malformed input 400, wrong method 405, wrong content type 415,
    unknown 500.
  - The security filters write the same JSON for 401 and 403.
- Logs name only the exception type and where it was thrown.

**Status codes for failures of other systems**

| Cause | Status |
|---|---|
| FHIR server or another MediCore service timed out or is unreachable | **503** |
| FHIR server or another MediCore service answered with something invalid or an error | **502** |
| FHIR server rejected the request (4xx), on clinical write endpoints | **422** |

**Timeouts**

| Outgoing call | Connect | Read | Where |
|---|---|---|---|
| HAPI FHIR | 10 s | 30 s | `medinexus.fhir.*-timeout` (unchanged from Lab 1) |
| Another MediCore service (`/internal/**`) | 3 s | 10 s | `common.http.ServiceClients` |

`ServiceClients.forService("http://identity:8080")` gives a `RestClient` with these timeouts. It also adds a fresh
service token to every request. There are no retries, because a retried write could be applied twice.

## 11. Repository layout and Maven modules

```
pom.xml                       parent: Spring Boot and dependency versions, module list (packaging: pom)
services/
  common/                     medicore-common (jar + test-jar): code every Spring service shares
  monolith/                   MediCore: the Lab 1 app, unchanged; removed at the EPIC-02 cut-over
  identity/ clinical/ messaging/   added in EPIC-02 (NIT-329, NIT-332, NIT-335)
MediPortal/                   React frontend (npm, not part of the Maven build)
infrastructure/  docs/
```

**What is in `common`** (package names unchanged, so no imports changed):

| Package | Contents |
|---|---|
| `security.jwt` | JWT signing and verification, cookie, service tokens ([§9](#9-authentication-jwt-cookie)) |
| `common.exception` | `ApiError`, `GlobalExceptionHandler` ([§10](#10-error-format-and-timeouts)) |
| `common.http` | `ServiceClients` |
| `auth.entity.Role`, `auth.dto.UserDto` | the principal every service gets from the token (D1) |
| `patient.validation.Personnummer` | personnummer rules shared by Identity and Clinical (D8) |

The common **test-jar** carries `TestTokens` (real test-signed tokens) and `ApiErrorMatchers`, so every service's
tests check tokens and error bodies the same way.

**Commands** (from the repository root):

| Goal | Command |
|---|---|
| Build and test everything | `./mvnw clean verify` |
| Build one service plus what it needs | `./mvnw -pl services/monolith -am package` |
| Run a service | `./mvnw -pl services/monolith -am spring-boot:run` |
| One test class | `./mvnw test -Dtest=AuthenticationControllerTest -Dsurefire.failIfNoSpecifiedTests=false` |
| Docker image | `docker build -f infrastructure/medicore/Dockerfile -t medinexus-medicore:local .` |

`-am` ("also make") builds the modules a service depends on in the same run, so nothing has to be installed to
`~/.m2` first. The parent sets `spring-boot.run.skip=true`; only runnable services set it to `false`, so
`spring-boot:run` never tries to start the parent or `common`. With `-Dtest=…`, Surefire needs
`-Dsurefire.failIfNoSpecifiedTests=false` because the other modules have no test of that name.
