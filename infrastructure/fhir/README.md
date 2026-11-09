# MediNexus – External FHIR server (KTH)

MediCore connects to an **external FHIR server** run by KTH. MediNexus does not
host its own FHIR server and no FHIR container runs in Docker.

| | |
|---|---|
| Base URL | `https://hapi-fhir.app.cloud.cbh.kth.se/fhir` |
| Swagger UI | `https://hapi-fhir.app.cloud.cbh.kth.se/fhir/swagger-ui/` |
| FHIR version | **R4 (4.0.1)** |
| Client library | HAPI FHIR client 7.4.3 (`hapi-fhir-client`, `hapi-fhir-structures-r4`) |
| Authentication | none (the server declares no security) |

## Purpose and current scope

MediCore talks to the FHIR server through one shared, configured HAPI FHIR R4
client (`fhir/config/FhirClientConfig`). Every request runs through
`FhirRequestExecutor`, which applies the timeouts, error categories and safe
logging described below. The only REST endpoints and UI for FHIR data are
**patient registration** ([US-04.01](#patient-registration-us-0401)), the
**patient profile** ([US-04.02](#patient-profile-us-0402)) and **patient
search** ([search](#patient-search-and-the-patient-registry)); all others
belong to later stories. FHIR owns all clinical data; PostgreSQL
holds only accounts and FHIR ID references (see
[../DATA_OWNERSHIP.md](../DATA_OWNERSHIP.md)).

### Implemented resource support

| Resource | Client (`fhir/client`) | Operations | Mapper (`fhir/mapper`) / DTOs |
|---|---|---|---|
| Server | `FhirServerClient` | `fetchCapabilityStatement()`: `GET [base]/metadata` | HAPI `CapabilityStatement` |
| Patient | `FhirPatientClient` | `readPatient(id)`: `GET [base]/Patient/{id}`; `createPatient(CreatePatientRequest)`: conditional `POST [base]/Patient` (see [Patient registration](#patient-registration-us-0401)); `searchPatients(PatientSearchCriteria, knownIds)`: `POST [base]/Patient/_search` restricted by `_id` (see [Patient search](#patient-search-and-the-patient-registry)) | `PatientMapper` ↔ `patient/dto/PatientDto`, `CreatePatientRequest` |
| Practitioner | `FhirPractitionerClient` | `readPractitioner(id)`: `GET [base]/Practitioner/{id}` | `PractitionerMapper` → `practitioner/dto/PractitionerDto` |
| Observation | `FhirObservationClient` | `readObservation(id)`, `createObservation(CreateObservationRequest)`, `findByPatientId(patientId)` | `ObservationMapper` ↔ `medicalrecord/dto/ObservationDto`, `CreateObservationRequest` |
| Condition | `FhirConditionClient` | `readCondition(id)`, `createCondition(CreateConditionRequest)`, `findByPatientId(patientId)` | `ConditionMapper` ↔ `medicalrecord/dto/ConditionDto`, `CreateConditionRequest` |
| Encounter | `FhirEncounterClient` | `readEncounter(id)`, `createEncounter(CreateEncounterRequest)`, `findByPatientId(patientId)` | `EncounterMapper` ↔ `medicalrecord/dto/EncounterDto`, `CreateEncounterRequest` (see [Encounter](#encounter)) |
| Organization | `FhirOrganizationClient` | `readOrganization(id)`, `createOrganization(OrganizationWriteRequest)`, `updateOrganization(id, expectedVersionId, OrganizationWriteRequest)` | `OrganizationMapper` ↔ `organization/dto/OrganizationDto`, `OrganizationWriteRequest` (see [Organization](#organization)) |
| Location | `FhirLocationClient` | `readLocation(id)`, `createLocation(LocationWriteRequest)`, `updateLocation(id, expectedVersionId, LocationWriteRequest)` | `LocationMapper` ↔ `location/dto/LocationDto`, `LocationWriteRequest` (see [Location](#location)) |

Current callers: `FhirPatientClient` and `FhirPractitionerClient` are used by
`AccountFhirIdentityService` (account linking, see below), and
`FhirPatientClient` by `PatientService` (patient registration and profile). The Server,
Observation, Condition, Encounter, Organization and Location clients have no
production callers yet; they are complete, tested building blocks for later
epics.

**Not implemented:** update of any resource other than Organization and
Location; patch or delete of any resource; search for Patient, Practitioner,
Organization or Location; resolving or checking the existence of referenced
resources; REST controllers and UI other than patient registration and the
patient profile.

### Conventions shared by all clients

- **Logical IDs only.** Every ID (resource, Patient, Practitioner, Organization,
  Location, expected version) must match
  `[A-Za-z0-9.-]{1,64}`; otherwise `IllegalArgumentException` is thrown and no
  request is sent. Callers never supply reference URLs; references such as
  `Patient/{id}` are built by the mappers.
- **Read** returns the HAPI resource; the matching mapper converts it to a DTO.
  A missing resource is a translated HTTP 404 (`CLIENT_ERROR`).
- **Create** (`POST [base]/{Type}`) validates the whole request in the mapper
  before any HTTP request, sends no resource ID and returns the server-assigned
  logical ID. A response without `201 Created`, the expected resource type and a
  valid ID is `INVALID_RESPONSE`. A failed create may follow a committed write:
  do not retry blindly.
- **Search by patient** (`findByPatientId`) sends `GET [base]/{Type}?subject=Patient/{id}&_count=100`
  and returns DTOs. Every entry must be the expected resource type with
  `search.mode` match (if present), a valid ID and a subject that is exactly
  `Patient/{id}` or `{base}/Patient/{id}` (declared type, if any, Patient). Next
  links are followed only if they are a single link that stays on the configured
  server and base path (no other host or port, user info, fragment,
  `..`/`%`-encoded segments or backslashes) and has not been visited before. At
  most 10 pages and 1,000 results. Any violation, or an error on any page, fails
  the **whole** search: there are no partial results.
- DTOs are immutable Java records and **read projections**, not complete FHIR
  resources; optional fields stay `null` or empty, references are preserved
  verbatim, and dates keep their lexical precision.

The operations listed under [Observed server capabilities](#observed-server-capabilities)
are what the **server** declares, not features of MediCore.

### Encounter

Implemented in US-03.04 (NIT-130 to NIT-132) with the same architecture as
Observation and Condition; the service provider and locations were added in
US-03.05 (NIT-135).

| Operation | Request | Result |
|---|---|---|
| `readEncounter(id)` | `GET [base]/Encounter/{id}` | HAPI `Encounter`; `EncounterMapper.toDto` → `EncounterDto` |
| `createEncounter(CreateEncounterRequest)` | `POST [base]/Encounter` | server-assigned logical ID |
| `findByPatientId(patientId)` | `GET [base]/Encounter?subject=Patient/{id}&_count=100` (+ validated next pages) | `List<EncounterDto>` |

**References created by `CreateEncounterRequest`** (logical IDs only):

| Request field | FHIR R4 element | Value |
|---|---|---|
| `patientId` (required) | `Encounter.subject` | `Patient/{patientId}` |
| `participants[].practitionerId` | `Encounter.participant[].individual` | `Practitioner/{practitionerId}` |
| `participants[].types` | `Encounter.participant[].type` | one coding per role, system `http://terminology.hl7.org/CodeSystem/v3-ParticipationType` |
| `serviceProviderId` (optional) | `Encounter.serviceProvider` | `Organization/{serviceProviderId}` |
| `locations[].locationId` | `Encounter.location[].location` | `Location/{locationId}`, in request order |
| `locations[].status` (optional) | `Encounter.location[].status` | `planned`, `active`, `reserved` or `completed` |

For example, a service provider and one active location are sent as:

```json
"location":[{"location":{"reference":"Location/synthetic-ward"},"status":"active"}],
"serviceProvider":{"reference":"Organization/synthetic-clinic"}
```

The original ten-argument `CreateEncounterRequest` constructor remains
available and creates an Encounter without service provider and locations.

Other create fields: `status` (required; the nine R4 codes `planned`,
`arrived`, `triaged`, `in-progress`, `onleave`, `finished`, `cancelled`,
`entered-in-error`, `unknown`), `encounterClass` (required; v3 ActEncounterCode
`AMB`, `EMER`, `FLD`, `HH`, `IMP`, `ACUTE`, `NONAC`, `OBSENC`, `PRENC`, `SS`,
`VR`), `type`, `serviceType`, `priority`, `reasonCode` (codings need an absolute
system URI and a code), and `periodStart`/`periodEnd` (FHIR `dateTime` with its
precision preserved). Validation before any request:

- participant roles: `ADM`, `ATND`, `CALLBCK`, `CON`, `DIS`, `ESC`, `PART`,
  `PPRF`, `REF`, `SPRF`; null entries or roles, the same Practitioner twice and
  the same role twice within one participant are rejected (put several roles in
  one participant);
- the period follows FHIR `per-1`: it is rejected only if the start is after the
  end under every interpretation (dates without a timezone may use any offset
  from -14:00 to +14:00; a year or month covers its whole range);
- `serviceProviderId` and every `locationId` must be logical IDs (no reference
  URLs such as `Organization/1` or `https://...`); null location entries, a
  missing `locationId` and the same Location twice are rejected. Location
  `period` and `physicalType` are not set.

**`EncounterDto`** contains `id`, `status`, `encounterClass`, `types`,
`serviceType`, `priority`, `subject`, `participants` (types, period,
individual), `period`, `length`, `reasonCodes`, `reasonReferences`, `diagnoses`,
`locations` and `serviceProvider`. All references use the existing
`SubjectReference` type and are preserved verbatim: a participant may be a
Practitioner, PractitionerRole or RelatedPerson and is never turned into a
Practitioner ID. `status` and `class` are required when mapping; a diagnosis
needs its condition and a location entry its location.

**Organization and Location references:** `locations[].location` and
`serviceProvider` are projected verbatim as references. MediCore sets them from
logical IDs when creating an Encounter but never resolves them and does not
check that the Organization or Location exists (no extra FHIR request or
database query); the server may reject a reference it cannot resolve
(`CLIENT_ERROR`). To read a referenced resource, pass its logical ID to
`FhirOrganizationClient` or `FhirLocationClient`; the clients do not depend on
each other. `FhirEncounterRoundTripTest` covers this against a local server.
`statusHistory`, `classHistory`, `episodeOfCare`, `basedOn`, `appointment`,
`account`, `hospitalization` and `partOf` are not projected.

**US-03.05 scope (complete):** Organization read/create/update (NIT-133),
Location read/create/update with managing Organization and parent Location
(NIT-134), and Encounter service provider and locations (NIT-135). Not part
of US-03.05: search for Organization or Location, patch/delete, updating
Encounters, and checking that referenced resources exist.

### Organization

Implemented in US-03.05 (NIT-133). Organization is the first resource MediCore
can **update**.

| Operation | Request | Result |
|---|---|---|
| `readOrganization(id)` | `GET [base]/Organization/{id}` | HAPI `Organization`; `OrganizationMapper.toDto` → `OrganizationDto` |
| `createOrganization(OrganizationWriteRequest)` | `POST [base]/Organization` | server-assigned logical ID |
| `updateOrganization(id, expectedVersionId, OrganizationWriteRequest)` | `GET [base]/Organization/{id}`, then `PUT [base]/Organization/{id}` with `If-Match: "{expectedVersionId}"` | the new version ID |

**`OrganizationDto`** (package `organization/dto`) contains `id`, `versionId`
(the server's current `meta.versionId`, needed for updates), `identifiers`
(system, value), `active` (`null` when absent), `types`, `name`, `aliases`,
`telecoms` (system, value, use, rank), `addresses` (use, type, text, lines,
city, district, state, postal code, country) and `partOf` (reference preserved
verbatim). `contact` (contact persons), `endpoint` and extensions are not
projected. Reading an Organization without a name and without an identifier
(FHIR `org-1`) is rejected.

**`OrganizationWriteRequest`** is used for both create and update. It is the
**complete desired state of the managed fields** `identifier`, `active`, `type`,
`name`, `alias`, `telecom`, `address` and `partOf`: `null` or an empty list
removes that element on update. `partOfOrganizationId` is a logical ID and
becomes `Organization/{id}`. Validation before any request:

- `org-1`: a non-blank `name` or at least one identifier;
- identifiers need a non-blank value; a system, if given, must be an absolute
  URI; the same identifier twice is rejected; aliases must be non-blank and
  unique; type codings need an absolute system URI and a code;
- telecom needs both `system` and `value` (`cpt-2`); `rank` must be at least 1;
- an address needs text, a line or another part; no blank strings;
- `org-2`/`org-3`: the request enums have no `home` use for telecom or address.

**Update (read-modify-write with optimistic concurrency).** All in one
`organization-update` operation, never retried automatically:

1. The ID, expected version and request are validated; an invalid value or
   `partOf` pointing to the Organization itself sends nothing.
2. `GET` the current resource. It must be the requested Organization with a valid
   `meta.versionId`; otherwise `INVALID_RESPONSE` and no `PUT`.
3. If the current version is not `expectedVersionId`, the update stops with
   HTTP **412** (`CLIENT_ERROR`, cause `PreconditionFailedException`) and no
   `PUT`. A resource with resource-level `modifierExtension` or `implicitRules`
   is never rewritten (`INVALID_RESPONSE`, no `PUT`).
4. Only the managed fields of a copy of the current resource are replaced.
   Everything else is preserved: `contact`, `endpoint`, extensions, contained
   resources, language, non-generated narrative and `meta` tags, profiles,
   security labels and source. An entry that is unchanged in every projected
   value keeps its hidden subfields (for example `Identifier.type`/`period`,
   `ContactPoint.period`, `Address.period`, element extensions). A generated
   narrative is removed, because it would be stale. `meta.versionId` and
   `meta.lastUpdated` are not sent.
5. `PUT` with `If-Match: "{expectedVersionId}"`, so the server rejects a change
   made by someone else in the meantime (409 or 412, both `CLIENT_ERROR`).
6. The response must name the same Organization (from `Content-Location`, or
   the returned resource when that header is absent; both must agree) with a
   valid new version that differs from the expected one, and must not claim a
   creation; otherwise `INVALID_RESPONSE`.

After 409 or 412, read the Organization again and decide again. After any
other failure of the `PUT`, the update may already be committed: read before
trying again. Cycles in `partOf` longer than a self-reference are not detected.

### Location

Implemented in US-03.05 (NIT-134) with the same architecture and the same
update strategy as [Organization](#organization).

| Operation | Request | Result |
|---|---|---|
| `readLocation(id)` | `GET [base]/Location/{id}` | HAPI `Location`; `LocationMapper.toDto` → `LocationDto` |
| `createLocation(LocationWriteRequest)` | `POST [base]/Location` | server-assigned logical ID |
| `updateLocation(id, expectedVersionId, LocationWriteRequest)` | `GET [base]/Location/{id}`, then `PUT [base]/Location/{id}` with `If-Match: "{expectedVersionId}"` | the new version ID |

**References** (logical IDs only, built by `LocationMapper`):

| Request field | FHIR R4 element | Value |
|---|---|---|
| `managingOrganizationId` | `Location.managingOrganization` | `Organization/{id}` |
| `partOfLocationId` | `Location.partOf` (parent Location) | `Location/{id}` |

When reading, both references are preserved verbatim (relative, absolute or
identifier-only) and never resolved. MediCore does not check that the
referenced Organization or Location exists; the server may reject a reference
it cannot resolve (`CLIENT_ERROR`).

**`LocationDto`** (package `location/dto`) contains `id`, `versionId`,
`identifiers`, `status`, `operationalStatus` (read-only coding), `name`,
`aliases`, `description`, `mode`, `types`, `telecoms`, `address` (one address),
`physicalType`, `position` (longitude, latitude, altitude as exact `BigDecimal`
values) and the references `managingOrganization` and `partOf`. Telecom and
address use the record types of `OrganizationDto`. `hoursOfOperation`,
`availabilityExceptions`, `endpoint` and extensions are not projected. A read
position without longitude or latitude is rejected; stored coordinates are
projected exactly, without range checks.

**`LocationWriteRequest`** is used for both create and update and is the
**complete desired state of the managed fields** `identifier`, `status`, `name`,
`alias`, `description`, `mode`, `type`, `telecom`, `address`, `physicalType`,
`position`, `managingOrganization` and `partOf`. `operationalStatus` is not
written. FHIR R4 requires no Location element, so no field is mandatory and an
empty Location is valid. Validation before any request:

- `status` (`active`, `suspended`, `inactive`) and `mode` (`instance`, `kind`)
  are enums; telecom and address `use` include `home` (unlike Organization);
- identifiers, aliases, types and `physicalType`, telecom (`cpt-2`) and address
  follow the same rules as for Organization; `name` and `description` must not
  be blank;
- `position`: longitude and latitude are both required, longitude within
  -180..180 and latitude within -90..90 (WGS84, inclusive); altitude is
  optional. Decimals are sent exactly as given, including their scale (for
  example `59.3293000`);
- `managingOrganizationId` and `partOfLocationId` must be logical IDs; on
  update, `partOfLocationId` must not be the Location itself (an Organization
  with the same logical ID is not a self-reference).

**Update:** the same read-modify-write flow as for Organization, in one
`location-update` operation: validation first, `GET`, local 412 for a different
version, merge of the managed fields only, `PUT` with `If-Match`, validation of
the new version, no automatic retries. Preserved unmanaged elements include
`operationalStatus`, `hoursOfOperation`, `availabilityExceptions`, `endpoint`,
extensions, contained resources, `meta` tags/profiles/security/source and
non-generated narrative. Unchanged entries and single elements (address,
physical type, position) keep their hidden subfields, and a reference whose
target is unchanged keeps its `display`. A Location with resource-level
`implicitRules` or `modifierExtension`, or a `modifierExtension` on `position`,
is never rewritten (`INVALID_RESPONSE`, no `PUT`). Cycles in `partOf` longer
than a self-reference are not detected.

### Patient registration (US-04.01)

`POST /api/patients` (`PatientController` → `PatientService` →
`FhirPatientClient.createPatient`) registers a new Patient in HAPI FHIR. It is
allowed only for **DOCTOR** and **STAFF** accounts: a URL rule in
`SecurityConfig` checks the session role, and `PatientService` checks the
account's current role in PostgreSQL again. CSRF protection applies.
MediPortal offers the form at `/patients/new`.

| Request field (`CreatePatientRequest`) | FHIR element | Rule |
|---|---|---|
| `personnummer` (required) | `identifier` (`use=official`, `system=http://electronichealth.se/identifier/personnummer`) | exactly 12 digits `YYYYMMDDXXXX` whose first eight are a real date (coordination numbers are rejected); the Luhn check digit is **not** validated; MediPortal also accepts `YYYYMMDD-XXXX` and sends 12 digits |
| `birthDate` (optional since US-04.03) | `birthDate` (day precision) | derived from the personnummer when omitted (MediPortal no longer asks for it); if given, it must equal the date in the personnummer; never in the future (Europe/Stockholm) |
| `family` (required) | `name[0].family` (`use=official`) | nonblank after stripping, at most 100 characters, no control characters |
| `given` (1–10, required) | `name[0].given` | each as `family` |
| `gender` (required) | `gender` | `MALE`, `FEMALE`, `OTHER` or `UNKNOWN` |
| (fixed) | `active` | `true` |

No ID, meta or narrative is sent. Validation happens before any request;
error messages never contain the submitted values.

**Duplicates** are prevented by a FHIR conditional create:
`If-None-Exist: identifier=http://electronichealth.se/identifier/personnummer|{personnummer}`.
If the server finds one matching Patient it creates nothing and answers 200;
several matches give 412. Both are reported as `DuplicatePatientException` →
HTTP 409, without the existing Patient's ID. Because the KTH server is shared,
a synthetic personnummer that another group already used is also a duplicate.

| Outcome | HTTP |
|---|---|
| Created | 201, `Location: /api/patients/{id}`, body `PatientDto` |
| Invalid data or unreadable JSON | 400 |
| Not signed in / wrong role / missing CSRF token | 401 / 403 / 403 |
| Personnummer already registered | 409 |
| Rejected by the FHIR server (4xx) | 422 |
| Invalid FHIR response or FHIR 5xx | 502 |
| FHIR timeout or unavailable | 503 |

Error responses have no body. After 502 or 503 the registration may have been
completed; there are no automatic retries, and repeating the registration is
safe because a completed one is then reported as 409.

**Logging:** `FhirRequestExecutor` logs failures with the operation name
`patient-create` only; `PatientService` logs the outcome with the account ID.
The personnummer, names, birth date and the FHIR Patient ID are never logged
(verified by `FhirPatientCreateTest`, `FhirUnavailableServerTest`,
`PatientServiceTest`, `PatientControllerTest` and
`PatientRegistrationIntegrationTest`). The personnummer is stored only in
`Patient.identifier` in HAPI FHIR, never in PostgreSQL.

Tests use fictional personnummer only (see
[Fictional patient identities](#fictional-patient-identities)) or
deliberately invalid numbers, against local servers. (An earlier version of
this document said that birth numbers 980–999 are reserved for test
personnummer; that was not verified and is wrong: most official test numbers
use other birth numbers.)
`LocalFhirServer` supports `If-None-Exist` with an `identifier=system|value`
condition. Whether the KTH server enforces `If-None-Exist` has **not** been
verified, because that would require a write request.

Registration never links the Patient to a PATIENT account and never creates a
journal access request. If the PATIENT account with the same personnummer has
already asked for access and is waiting for its record, that request is
attached to the new Patient and becomes `PENDING` for a doctor to review (see
[Journal access requests](#journal-access-requests-us-0403)); the operator
command (see [Trusted local account provisioning](#trusted-local-account-provisioning))
still works as before.

**Known limitation (account registration, Epic 2):** `POST /api/auth/register`
is public and lets the caller choose the role, including DOCTOR and STAFF. The
role check above is therefore only as strong as account registration. Changing
account registration is outside US-04.01.

### Patient profile (US-04.02)

`GET /api/patients/{id}` (`PatientController` → `PatientService.getPatient` →
`FhirPatientClient.readPatient` → `PatientMapper.toDto`) returns one Patient's
`PatientDto`, read **directly from HAPI FHIR** on every request. Nothing is
stored or cached in PostgreSQL or MediCore, and responses carry
`Cache-Control: no-store`. MediPortal shows it at `/patients/{id}`
(`PatientProfilePage`) for DOCTOR and STAFF; profiles are opened from the
[patient search](#patient-search-and-the-patient-registry) or from the link
after a registration. The FHIR ID is used only in the route and is not shown.

Access, in this order:

1. URL rule in `SecurityConfig`: `GET /api/patients/*` for DOCTOR and STAFF
   only (one path segment; `GET /api/patients` and any list or search stay
   denied);
2. the account's **current** role in PostgreSQL must be DOCTOR or STAFF
   (`PatientService`); a stale session role is not enough;
3. the ID must be a logical FHIR ID (`[A-Za-z0-9.-]{1,64}`), otherwise 400;
4. `PatientAccessService.checkPatientAccess` (reused ownership check);
5. the Patient must be **known to MediCore** (`PatientRegistry`: registered by
   MediCore or linked to an account), otherwise 404 without any FHIR request;
6. exactly one `GET [base]/Patient/{id}`; the returned resource must have the
   requested ID, otherwise 502.

PATIENT accounts get 403 here, even for their own linked Patient; they read
their own record through `GET /api/patients/me` (US-04.03, see
[Journal access requests](#journal-access-requests-us-0403)).

Since US-04.03 the response is a `PatientProfileDto`: the `PatientDto` fields
plus `email`, the e-mail of the PATIENT account linked to this Patient
(`users.fhir_patient_id`), read from PostgreSQL on each request, or `null`
without a linked account. MediPortal shows "Not available" for `null`.

| Outcome | HTTP |
|---|---|
| Found | 200, `PatientProfileDto` |
| Invalid ID | 400 |
| Not signed in / not DOCTOR or STAFF | 401 / 403 |
| FHIR answers 404 or 410 | 404 |
| Other FHIR 4xx | 422 |
| Invalid FHIR response (including a different Patient), FHIR 5xx | 502 |
| FHIR timeout or unavailable | 503 |

Error responses have no body. Logs contain the account ID and the outcome
(`Patient profile viewed` / `Patient profile not found`), never the Patient ID
or patient data; `FhirRequestExecutor` logs FHIR failures as `patient-read`.
Tests: `PatientServiceTest`, `PatientControllerTest`,
`PatientProfileIntegrationTest` (PostgreSQL `medinexus_test`, local FHIR
server, synthetic Patients only); `patient-read` is part of
`FhirUnavailableServerTest`.

**Limitations: this is not production-grade patient access control.**

- **Self-registration:** anyone can register a DOCTOR or STAFF account (see the
  known limitation above) and then read any Patient's profile, including the
  personnummer.
- **Shared server:** the KTH server is shared and unauthenticated. Profiles and
  searches are limited to Patients known to MediCore, so other users' Patients
  are not shown. Anyone can still change or delete MediCore's Patients on the
  server itself; MediCore cannot prevent that. Use synthetic data only.
- **No access log:** there is no audit log of which Patient was viewed by whom,
  as Swedish patient data legislation (patientdatalagen) would require in
  production.

### Patient updates (US-04.04)

`PATCH /api/patients/{id}` (`PatientController` → `PatientService.updatePatient`
→ `FhirPatientClient.updatePatient`) lets **DOCTOR** and **STAFF** change a
registered Patient's **gender** in HAPI FHIR, nothing else. MediPortal offers
it as "Edit Patient" on the profile (`/patients/{id}`), where name,
personnummer and birth date are shown read-only. PATIENT accounts cannot edit
(403), not even their own record.

**Immutable:** first and last name (`Patient.name`), personnummer
(`Patient.identifier`), birth date and the FHIR ID. The body may contain only
`gender` (`FEMALE`, `MALE`, `OTHER`, `UNKNOWN`); any other property, such as
`givenName`, `familyName`, `personnummer`, `birthDate`, `id`, `email` or
`phoneNumber`, is rejected with 400 before anything is read. The service
compares the names, identifiers and birth date before writing and with the
server's answer; a difference is never reported as a success. There is no
identity-correction workflow.

Order: URL rule (`PATCH /api/patients/*` for DOCTOR/STAFF) and CSRF; the
account's current role in PostgreSQL; the ID format (400); the values (400,
same rules as registration, before any FHIR request); the version (`If-Match`,
428 `VERSION_REQUIRED` without it); MediCore's registry (404 without any FHIR
request for Patients MediCore does not know); one read (404/410 → 404); then
one update.

**Only the gender changes.** The Patient is read from FHIR, changed in place
and written back whole (`PUT [base]/Patient/{id}`), so its meta, names,
identifiers, birth date, telecom, addresses, extensions and all other elements
stay as they were; no new Patient is created.

**Contact details are not FHIR data in MediCore.** Patient profiles
(`GET /api/patients/{id}`) and My Medical Record (`GET /api/patients/me`)
return `email` and `phoneNumber` of the PATIENT account linked through
`users.fhir_patient_id`, read from PostgreSQL on each request, or `null`
("Not available") without a linked account. `Patient.telecom` is never read
for them and never written; existing telecom values are preserved by updates.
Patient registration asks for no contact details and needs no account.

**Concurrency:** the profile returns the Patient's FHIR `version` (not shown in
the UI). MediPortal sends it back as `If-Match`; MediCore refuses the update
with 409 `VERSION_CONFLICT` if the Patient has a newer version, and sends the
update to FHIR with `If-Match: W/"version"`, so a change between MediCore's
read and write is rejected by the server (409/412 → 409 `VERSION_CONFLICT`).
Nothing is overwritten silently. FHIR timeouts and outages are 503, invalid
responses 502, other rejections by the FHIR server 422; after a 503 the
outcome is unknown and MediPortal says so.

Nothing is stored in PostgreSQL: the account of a linked PATIENT is not
changed, the account link
(`users.fhir_patient_id`) and journal access requests are unaffected, and no
request is created. Logs contain the account ID only (`Patient updated`,
`patient-update` for FHIR failures). Whether the KTH server honours
`If-Match` has not been verified, because that would require a write
request; the local test server does, like HAPI.

Tests: `FhirPatientUpdateTest`, `PatientDemographicsUpdateTest`,
`UpdatePatientRequestTest`, `PatientServiceTest`, `PatientControllerTest` and
`PatientUpdateIntegrationTest` (PostgreSQL `medinexus_test`, local FHIR
server, fictional data).

### Patient search and the patient registry

MediCore cannot tell its own Patients apart from other Patients on the shared,
unauthenticated KTH server by looking at them: nothing in a Patient marks who
created it, the personnummer system is shared by everyone, and any tag could be
added or removed by anyone. MediCore therefore keeps a local **registry**:

- `registered_patients` (Flyway V5) stores the logical FHIR ID, the registering
  account (`ON DELETE SET NULL`) and the time, written by `PatientRegistry`
  right after a successful registration. No names, personnummer or other
  patient data;
- Patients linked to an account by the operator command
  (`users.fhir_patient_id`) also count as known, so existing identity links
  keep working without a registry entry.

`POST /api/patients/search` (`{"query": "..."}`, DOCTOR and STAFF, CSRF; URL
rule plus the current database role) searches **only** these Patients:

| Query | FHIR search |
|---|---|
| 12 digits, optionally `YYYYMMDD-NNNN`, valid personnummer | `identifier=http://electronichealth.se/identifier/personnummer|{number}`; only exact matches are returned |
| 1–5 name parts (letters, hyphens, apostrophes; at least 2 letters) | one `name` parameter per part; all must match (HAPI: start of a family name, given name or text, ignoring case and accents) |

- Every request is `POST [base]/Patient/_search` with a form body, so neither
  names nor personnummer appear in any URL (MediPortal → MediCore → FHIR). The
  KTH server was verified (read-only, with a nonexistent `_id`) to answer such
  a request with a `searchset` and a self link without parameters.
- The known IDs are sent as `_id=a,b,c` in batches of 50, so other Patients are
  never requested. Every returned Patient must be one of the requested IDs and
  appear once, otherwise the whole search fails (`INVALID_RESPONSE`, 502).
- Up to 10 pages of 100 per batch are followed (next links must stay within
  the FHIR base); more than 1,000 matches give 422 `{"code":"TOO_MANY_RESULTS"}`.
- All matches are returned, sorted by family name, given names and birth date;
  Patients with identical names are separate results. MediPortal shows name,
  personnummer and birth date with an "Open profile" action per result and
  never opens a profile automatically. FHIR IDs are not displayed.
- Errors: 400 invalid query, 401/403, 422 too many results, 502/503 FHIR
  failures; logs contain the account ID and the number of results only.

**Registration and the registry.** If the FHIR create succeeds but the registry
entry cannot be written, MediCore answers 500 `{"code":"REGISTRY_FAILED"}` and
logs the FHIR ID once at ERROR level so that an operator can add it; the
Patient exists in FHIR but is not searchable, and registering it again would be
a duplicate (409). MediPortal tells the user not to register again.

**Patients registered before V5** have no registry entry and are no longer
found or shown unless they are linked to an account. MediCore cannot identify
them on the shared server. An operator can add a known ID with
`INSERT INTO registered_patients (fhir_patient_id) VALUES ('...')`; a dedicated
operator command does not exist yet.

### Fictional patient identities

Any personnummer with a **real date of birth** can be registered, with any
nonblank first and last names; there is no allowlist and **no Luhn check-digit
validation** (the last four digits are not checked, so freely chosen fictional
numbers work). The rules are (`Personnummer`, mirrored in MediPortal):

- exactly 12 digits `YYYYMMDDXXXX` (`YYYYMMDD-XXXX` is accepted and
  normalized to 12 digits; nothing else is changed or truncated), and the first
  eight digits are a real calendar date; coordination numbers (day + 60) are
  therefore rejected;
- the birth date is derived from the personnummer (or, if sent, must equal
  it) and must not be in the future (Europe/Stockholm);
- the same rules apply to the personnummer of every new account
  (`POST /api/auth/register`), which must also be unique among accounts;
- names are nonblank after trimming, at most 100 characters each, without
  control characters;
- duplicate detection (`If-None-Exist`), the patient registry, search and
  profiles work as described above; personnummer and names are never logged.

Matching between accounts and Patients (journal access requests) compares the
normalized 12 digits exactly; the check digit plays no part. The database only
requires 12 digits (`ck_users_personnummer_format`, V6) and uniqueness.

**Use fictional identities only.** A number with a real date may belong to
a real person, and MediCore cannot check that (it has no access to the
population register). This matters especially on the shared, unauthenticated
KTH server, where everything stored is visible to and changeable by anyone.
Skatteverket publishes test personnummer that will never be assigned to real
people (open data "Testpersonnummer",
<https://www.skatteverket.se/omoss/digitalasamarbeten/omvaraoppnadata/testpersonnummersomoppendata.4.5b35a6251761e6914202df9.html>);
using them is recommended but no longer enforced. Automated tests use numbers
from that list, numbers with birth years in the 19th century (which no living
person can have, because personnummer were introduced in 1947; `TestPersonnummer`
gives them a deliberately wrong check digit) or deliberately invalid numbers,
and run only against local mock FHIR servers
(`FictionalIdentityRegistrationIntegrationTest`, `PersonnummerTest`,
`PatientServiceTest`).

### Journal access requests (US-04.03)

A PATIENT account gets access to its medical record only when the **patient
asks for it** and a **doctor approves**. A request is created only by the
patient's explicit action, `POST /api/journal-access/me/request` (the "Request
Journal Access" button on the patient dashboard); account registration,
Patient registration, loading the dashboard, matching and patient searches
never create one. Matching uses the account's personnummer
(`users.personnummer`, Flyway V6) and the personnummer of Patients registered
through MediCore, **exact personnummer only**, never names, and never links an
account by itself.

**Asking for access** (PATIENT only, CSRF; no request body is read, so no user
ID, personnummer or FHIR ID can be supplied by the client):

| Situation | Result |
|---|---|
| Account already linked | `200 {"status":"APPROVED"}`, nothing created |
| An open request exists (repeated or concurrent clicks) | `200` with its status, nothing created; a waiting request is matched again |
| A registered Patient has the account's personnummer | new request `PENDING` (`200 {"status":"PENDING"}`) |
| No such Patient yet, or the FHIR search failed | new request `AWAITING_RECORD` without a FHIR Patient; an outage is not treated as a non-match, the request is matched again later |
| The latest request was rejected | `409 REQUEST_REJECTED`, nothing created (a doctor can reconsider) |
| The matching Patient is linked to another account | `409 LINK_CONFLICT`, nothing created |
| Account without personnummer (created before V6) | `409 PERSONNUMMER_MISSING` |

The search (`JournalAccessMatcher.findRegisteredPatient`) is
`POST Patient/_search` with `identifier=` and `_id=` restricted to
`registered_patients`, as in the patient search; other Patients on the shared
server are never requested. If several free registered Patients match, nothing
is attached automatically.

**Waiting requests are matched later, without another click**
(`JournalAccessMatcher`, which never creates requests):

- when a DOCTOR or STAFF registers a Patient (`POST /api/patients`), a request
  `AWAITING_RECORD` of the PATIENT account with the same personnummer is
  attached to it and becomes `PENDING` (PostgreSQL only, no extra FHIR
  request; a failure is logged and does not fail the registration);
- when the PATIENT's status is read (`GET /api/journal-access/me`), a waiting
  request is searched again (this also recovers from a FHIR outage).

Without a waiting request nothing happens in either case. Attaching is a
conditional update (`AWAITING_RECORD` → `PENDING`), so retries are idempotent.

Flyway V8 (after V7) allows `fhir_patient_id` to be `NULL` only for
`AWAITING_RECORD`, requires it for `PENDING`, `APPROVED` and `REJECTED`, keeps
the review rule (open requests unreviewed, decided ones with a review time),
and replaces the V7 index with a partial unique index on `user_id` for open
requests (`AWAITING_RECORD`, `PENDING`): at most one open request per account,
also while it has no FHIR Patient (a unique index over a nullable column would
not stop duplicates, because NULLs are distinct). Existing rows, including
requests created automatically before V8, are kept unchanged; V8 stops with an
error instead of changing history if an account had several `PENDING`
requests. DOCTOR and STAFF accounts can never ask.

**Review** (`JournalAccessService`, DOCTOR only: URL rules in `SecurityConfig`
plus the current role in PostgreSQL; CSRF for every decision):

| Endpoint | Purpose |
|---|---|
| `GET /api/journal-access-requests` | all requests, `PENDING` first, then `AWAITING_RECORD`, then decided: account name, personnummer, e-mail, status, times (no FHIR ID) |
| `GET /api/journal-access-requests/pending/count` | `{"pending": n}`, only actionable `PENDING` requests, for the dashboard and navigation badge |
| `GET /api/journal-access-requests/{id}` | the account next to the FHIR Patient (one `GET Patient/{id}`; none for `AWAITING_RECORD`): names, personnummer, gender, birth date, `recordStatus` (`AVAILABLE`, `NOT_FOUND`, `UNAVAILABLE`, `NOT_REGISTERED`) and `personnummerMatches` |
| `POST /api/journal-access-requests/{id}/approve` | approve and link |
| `POST /api/journal-access-requests/{id}/reject` | reject; the account is not linked |
| `POST /api/journal-access-requests/{id}/reopen` | reconsider a rejected request: a new `PENDING` request, the rejected one stays as history |

**Approval** checks the request (`PENDING`), the account (PATIENT, not linked
elsewhere, no Practitioner link), that no other account is linked to the
Patient, reads the Patient **once** (outside any database transaction) and
requires its personnummer to equal the account's. Then, in **one database
transaction**, the request is set to `APPROVED` with the reviewer and time
(only while still `PENDING`) and `users.fhir_patient_id` is set (only while
still `NULL`); the unique constraint on `fhir_patient_id` stops a second
account. If any step fails, nothing changes. A request `AWAITING_RECORD` can be
neither approved nor rejected (`409 AWAITING_RECORD`). Conflicts are 409 with a code:
`AWAITING_RECORD`, `REQUEST_NOT_PENDING`, `REQUEST_NOT_REJECTED`, `ALREADY_PENDING`,
`ACCOUNT_NOT_ELIGIBLE`, `LINK_CONFLICT`, `FHIR_PATIENT_MISSING`,
`PERSONNUMMER_MISMATCH`. FHIR failures are 503/502; an unknown request is 404,
a malformed ID 400.

**PATIENT side:** `GET /api/journal-access/me` returns `NONE` (not asked yet),
`AWAITING_RECORD`, `PENDING`, `APPROVED` (linked) or `REJECTED` (latest request
rejected); it never creates a request.
`GET /api/patients/me` returns the linked Patient as a `PatientProfileDto`
with the account's own e-mail, read directly from FHIR; the record is always
taken from `users.fhir_patient_id`, never from the request. Without a link it
is 404 `{"code":"RECORD_NOT_LINKED"}`; a Patient that no longer exists in FHIR
gives 404 `{"code":"RECORD_NOT_FOUND"}`. DOCTOR and STAFF get 403 on both
endpoints; PATIENT accounts still get 403 on `GET /api/patients/{id}`.
MediPortal shows the status on the patient dashboard and the record at
`/patient/record`; doctors review at `/doctor/journal-requests`.

**Logging:** request IDs, account IDs and reviewer IDs only; never
personnummer, names or FHIR Patient IDs.

**Tests:** `JournalAccessMatcherTest`, `JournalAccessServiceTest`,
`JournalAccessControllerTest`, `PatientServiceTest`, `PatientControllerTest`
and `JournalAccessIntegrationTest` (PostgreSQL `medinexus_test`, local FHIR
server, fictional 19th-century personnummer only: nothing created by
registrations, dashboards or searches; explicit requests with and without a
registered record; later matching; FHIR outage; double and concurrent clicks;
rejection and reconsideration; foreign and name-only matches; personnummer
mismatch, deleted Patient, link conflicts, concurrent approvals; V8
constraints; roles and CSRF).

**Limitations.** The account's personnummer is self-declared at registration;
there is no identity verification (BankID or similar), so a person who knows
someone else's personnummer could register with it before that person does.
The doctor's review is the safeguard, and the review page shows both sides for
that purpose. Account registration also still lets anyone create a DOCTOR
account (see the known limitation under
[Patient registration](#patient-registration-us-0401)). Accounts created before
V6 have no personnummer and are never matched; they can still be linked by the
operator command. The shared KTH server can change a Patient after approval;
the link is not re-verified later.


### Verified FHIR patient journey (NIT-137)

`FhirPatientJourneyIntegrationTest` runs one synthetic patient journey through
the existing clients and mappers against `fhir/support/LocalFhirServer`, a
stateful local test server on `127.0.0.1` (create, read and search by
`subject`). It needs **no database and no external FHIR server**:

1. read a Patient and a Practitioner (seeded on the test server, because
   MediCore cannot create them);
2. create and read an Organization and a Location it manages;
3. create an Encounter with that Patient, Practitioner, Organization and
   Location;
4. create an Observation and a Condition for the same Patient (and an
   Observation for another Patient);
5. read the clinical resources and search each type by Patient ID: each search
   returns exactly that Patient's resources, identical to the reads;
6. follow the Encounter's references with the Patient, Practitioner,
   Organization and Location clients; the Location is managed by the
   Encounter's service provider.

Only `GET` and `POST` requests are sent, and Patient and Practitioner are never
written. The test also shows that references are not resolved when an
Encounter is created: a missing Organization, Location or Practitioner only
fails (404) when it is read.

The local account and its link are covered separately by
`integration/AccountFhirPatientJourneyIntegrationTest` (PostgreSQL
`medinexus_test` and the same local FHIR server): a registered PATIENT account
and a DOCTOR account are linked through `AccountFhirIdentityService` (one `GET`
each; only the logical IDs are stored), and the stored Patient ID retrieves
exactly that Patient and its Encounter, Observation and Condition, with the
DOCTOR account's Practitioner as Encounter participant.

**Not covered:**

- REST endpoints or UI for clinical data, which do not exist yet;
- links from Observation or Condition to the Encounter: MediCore cannot set
  `Observation.encounter` or `Condition.encounter`, so they are related to the
  Encounter only through the same Patient;
- the KTH server: the test never contacts it.

## Configuration

### Account identity references (single-server lab)

Local accounts may remain unlinked. A PATIENT account may hold only a
`fhir_patient_id`; DOCTOR and STAFF accounts may hold only a
`fhir_practitioner_id`. PostgreSQL migration V3 enforces logical IDs of 1–64
ASCII letters, digits, dots or hyphens, and unique ownership within each
resource type (including across DOCTOR and STAFF). It does not validate remote
resource existence or establish associations. Clinical records remain in FHIR.

These references belong to the single fixed KTH server configured for this lab.
**Do not change `FHIR_BASE_URL` and silently reinterpret existing references
against another server.** A server change requires an explicit review and
mapping strategy before linked accounts are used.

V3 validates existing rows without clearing or repairing references. Before
deployment, check Flyway history for version conflicts and audit existing
non-null Patient IDs for duplicates, invalid format and incompatible roles.
Resolve conflicts explicitly; do not edit applied migrations or silently
rewrite records. Multiple accounts with null references remain valid.

### Client settings

All settings are in `src/main/resources/application.properties`:

| Property | Default | Meaning |
|---|---|---|
| `medinexus.fhir.base-url` | `${FHIR_BASE_URL:https://hapi-fhir.app.cloud.cbh.kth.se/fhir}` | FHIR server base URL |
| `medinexus.fhir.connect-timeout` | `10s` | Maximum time to establish the TCP connection |
| `medinexus.fhir.socket-timeout` | `30s` | Maximum **inactivity** while reading a response (not a total limit) |
| `medinexus.fhir.connection-request-timeout` | `10s` | Maximum wait for a free connection from the client's pool |

- To use another server (for example a local test server), set the environment
  variable **`FHIR_BASE_URL`** for the MediCore process or IntelliJ run
  configuration, for example `http://localhost:8080/fhir`. The URL is public,
  not a secret.
- The base URL must be an absolute `http://` or `https://` URL with a host and
  without query or fragment; a trailing `/` is removed. All timeouts must be
  positive. Invalid values stop MediCore at startup with a message naming the
  property.
- Creating the client does **not** contact the server (server validation mode
  `NEVER`), so MediCore starts even when the FHIR server is unreachable. The
  server is only contacted when an operation is called.

## Checking connectivity

### Trusted local account provisioning

`com.medinexus.provisioning.FhirIdentityProvisioning` is a separate, one-shot
operator command outside the normal application's component scan. It starts no
web server and exposes no endpoint. Normal MediCore startup never provisions
accounts, even if provisioning settings are present. There is no new admin role;
DOCTOR/STAFF login does not grant provisioning permission. Only an operator with
trusted local process/database access should run this command.

The command delegates exactly once to `AccountFhirIdentityService.assign`.
The account's persisted role selects Patient or Practitioner. Existing matching
links are idempotent; different links, duplicate ownership, missing resources and
identity mismatches are rejected by the existing service. Remote access is GET
only; the sole intended write is the local account reference. No clinical data is
copied into PostgreSQL.

Prerequisites:

1. Independently confirm the synthetic account and existing synthetic FHIR
   resource belong together. Knowing an ID or finding a resource is not proof.
2. Confirm the database and fixed KTH base URL; never reinterpret existing links
   against another server. Review migrations before running: normal Flyway startup
   applies any pending migrations, though provisioning adds no migrations.
3. Configure `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` for the local database as
   described in `infrastructure/database/README.md`. Spring does not read `.env`
   automatically. Do not put credentials in command-line arguments.
4. Build the executable JAR: `./mvnw -DskipTests package` (run tests separately).

Example for macOS/Linux, from the repository root (Bash). The hidden prompts keep
IDs out of terminal output and shell history. Do not enable shell tracing.

```bash
read -r -s -p 'Existing synthetic local account ID: ' MEDINEXUS_FHIR_PROVISIONING_ACCOUNT_ID
printf '\n'
read -r -s -p 'Confirmed synthetic logical FHIR ID: ' MEDINEXUS_FHIR_PROVISIONING_FHIR_ID
printf '\n'
export MEDINEXUS_FHIR_PROVISIONING_ACCOUNT_ID MEDINEXUS_FHIR_PROVISIONING_FHIR_ID
export FHIR_BASE_URL=https://hapi-fhir.app.cloud.cbh.kth.se/fhir

java -Dloader.main=com.medinexus.provisioning.FhirIdentityProvisioning \
  -cp target/MediCore-0.0.1-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  --medinexus.fhir.provisioning.enabled=true
provision_status=$?
printf 'Provisioning exit status: %s\n' "$provision_status"
```

The entry point accepts process environment/system properties or command-line
properties named `medinexus.fhir.provisioning.enabled`, `.account-id`, and
`.fhir-id`. The dotted prefix applies to all three names. The activation value
must be exactly `true`. It is absent/disabled by default; input validation happens
before starting Spring or connecting to either server. Supplying inputs in public
HTTP requests has no effect.

Process outcomes:

| Exit | Output/meaning |
|---|---|
| 0 | `Provisioning completed successfully.` Context closed after one assignment attempt. |
| 1 | Generic provisioning failure; no underlying messages or identifiers printed. |
| 2 | Disabled or invalid configuration; no context or assignment started. |

The command suppresses framework logs and failure reports so SQL/FHIR details
are not printed. A failure during shutdown could occur after an assignment has
committed; verify state before retrying. Do not assume every nonzero exit means
the database is unchanged.

Safe read-only verification using local `psql` (adjust host/database if needed):

```bash
PGPASSWORD="$DB_PASSWORD" psql -X --host=127.0.0.1 \
  --username="$DB_USERNAME" --dbname=medinexus_db <<'SQL'
\getenv account_id MEDINEXUS_FHIR_PROVISIONING_ACCOUNT_ID
\getenv fhir_id MEDINEXUS_FHIR_PROVISIONING_FHIR_ID
BEGIN READ ONLY;
SELECT role,
       CASE WHEN role = 'PATIENT' THEN fhir_patient_id = :'fhir_id'
            ELSE fhir_practitioner_id = :'fhir_id' END AS association_matches
FROM users WHERE id = :'account_id'::bigint;
COMMIT;
SQL
unset MEDINEXUS_FHIR_PROVISIONING_ACCOUNT_ID MEDINEXUS_FHIR_PROVISIONING_FHIR_ID
```

Expect one row with the intended role and `association_matches = t`; no row means
the local account was not found, and null/false is not a confirmed match. This
prints no account IDs, FHIR IDs, names or clinical data. Keep credentials private
and clear them from the shell when finished. Do not use an UPDATE to bypass a
failed association. The ordinary `java -jar ...` command still starts MediCore,
not this provisioning entry point.

### Opt-in connectivity test

`src/test/java/com/medinexus/medicore/fhir/client/KthFhirServerConnectivityIT.java`
uses the real `FhirServerClient` and Spring FHIR configuration (no database) and
sends exactly **one read-only request**, `GET [base]/metadata`. It checks that:

- the server answers with a CapabilityStatement for FHIR **4.0.1**,
- Patient, Practitioner, Observation, Condition and Encounter declare `read`
  and `search-type`.

It does not check resource counts or the server's software version, which can
change.

The test never runs in a normal `mvnw test`: its name ends in `IT`, which Maven
Surefire does not pick up by default, and it is skipped unless a system property
is set. Run it explicitly from the project root (needs internet access, no
database):

```powershell
# Windows (PowerShell) - keep the quotes
.\mvnw.cmd test "-Dtest=KthFhirServerConnectivityIT" "-Dmedinexus.fhir.connectivity-test=true"
```

```bash
# macOS
sh mvnw test -Dtest=KthFhirServerConnectivityIT -Dmedinexus.fhir.connectivity-test=true
```

Expected: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`. Without the
`-Dmedinexus.fhir.connectivity-test=true` part it is reported as skipped. It
uses `FHIR_BASE_URL` if set.

### Other opt-in read-only KTH tests

Each class below follows the same rules: skipped unless its system property is
`true`, excluded from normal `mvnw test` by the `IT` suffix, **GET requests
only** (an interceptor fails the test on any other method or on a URL outside
the configured server), no writes, and no clinical content printed. Use only
IDs of **confirmed synthetic** resources.

| Test class | Enable with | ID property |
|---|---|---|
| `KthFhirPatientReadIT` | `medinexus.fhir.patient-read-test=true` | `medinexus.fhir.synthetic-patient-id` (required) |
| `KthFhirPractitionerReadIT` | `medinexus.fhir.practitioner-read-test=true` | `medinexus.fhir.synthetic-practitioner-id` (required) |
| `KthFhirObservationReadIT` | `medinexus.fhir.observation-read-test=true` | `medinexus.fhir.observation-id` (has a default) |
| `KthFhirObservationSearchIT` | `medinexus.fhir.observation-search-test=true` | `medinexus.fhir.observation-search-patient-id` |
| `KthFhirConditionReadIT` | `medinexus.fhir.condition-read-test=true` | `medinexus.fhir.condition-id` (has a default) |
| `KthFhirConditionSearchIT` | `medinexus.fhir.condition-search-test=true` | `medinexus.fhir.condition-search-patient-id` (has a default) |
| `KthFhirEncounterReadIT` | `medinexus.fhir.encounter-read-test=true` | `medinexus.fhir.encounter-id` (required, no default) |
| `KthFhirEncounterSearchIT` | `medinexus.fhir.encounter-search-test=true` | `medinexus.fhir.encounter-search-patient-id` (required, no default) |
| `KthFhirOrganizationReadIT` | `medinexus.fhir.organization-read-test=true` | `medinexus.fhir.organization-id` (required, no default) |
| `KthFhirLocationReadIT` | `medinexus.fhir.location-read-test=true` | `medinexus.fhir.location-id` (required, no default) |

Example (Windows PowerShell; keep the quotes):

```powershell
.\mvnw.cmd test "-Dtest=KthFhirEncounterSearchIT" "-Dmedinexus.fhir.encounter-search-test=true" "-Dmedinexus.fhir.encounter-search-patient-id=<confirmed synthetic Patient ID>"
```

`KthFhirEncounterSearchIT` expects at least one Encounter for the given Patient
and fails closed if any live Encounter cannot be mapped or references another
patient.

### Inspecting /metadata manually

The CapabilityStatement is about 1.8 MB. Only read it, for example:

```powershell
# Windows PowerShell
$cs = Invoke-RestMethod -Uri https://hapi-fhir.app.cloud.cbh.kth.se/fhir/metadata -Headers @{ Accept = 'application/fhir+json' }
$cs.fhirVersion
$cs.rest[0].resource | Where-Object { $_.type -in 'Patient','Practitioner','Observation','Condition','Encounter' } |
  Select-Object type, @{ n = 'interactions'; e = { ($_.interaction.code | Sort-Object) -join ', ' } }
```

```bash
# macOS
curl -s -H 'Accept: application/fhir+json' https://hapi-fhir.app.cloud.cbh.kth.se/fhir/metadata | grep -oE '"(fhirVersion|version)" *: *"[^"]*"'
```

The Swagger UI shows the same information in the browser.

## Observed server capabilities

Verified on **2026-11-06** with `FhirServerClient.fetchCapabilityStatement()`,
the opt-in connectivity test and the manual commands above. All requests were
`GET [base]/metadata`.

| Item | Observed value |
|---|---|
| HTTP result | `200`, `application/fhir+json`, about 1–2 s |
| CapabilityStatement | `status=active`, `kind=instance` |
| FHIR version | `4.0.1` (R4) |
| Server software | HAPI FHIR Server 7.4.0 |
| Resource types declared | 146 |
| Formats | JSON, XML, Turtle (`application/fhir+json`, `application/fhir+xml`, ...) |
| Security | none declared |
| Server-wide interactions | `transaction`, `history-system` |

Declared support for the core resource types:

| Resource | Interactions | Search parameters | Operations |
|---|---|---|---|
| Patient | `read`, `vread`, `search-type`, `create`, `update`, `patch`, `delete`, `history-instance`, `history-type` | 35 | `$everything`, `$validate`, `$meta`, `$meta-add`, `$meta-delete`, `$expunge` |
| Practitioner | same as Patient | 29 | `$validate`, `$meta`, `$meta-add`, `$meta-delete`, `$expunge` |
| Observation | same as Patient | 50 | `$lastn`, `$validate`, `$meta`, `$meta-add`, `$meta-delete`, `$expunge` |
| Condition | same as Patient | 33 | `$validate`, `$meta`, `$meta-add`, `$meta-delete`, `$expunge` |
| Encounter | same as Patient | 35 | `$everything`, `$validate`, `$meta`, `$meta-add`, `$meta-delete`, `$expunge` |

All five also declare versioned updates, conditional create and conditional
update. `updateCreate` is `false`: a client cannot choose the ID of a new
resource with `PUT`; new resources get a server-assigned ID via `POST`.

Organization and Location (checked with a read-only `GET [base]/metadata` for
US-03.05) declare the same interactions as Patient, `versioning`
`versioned-update`, `updateCreate` `false`, conditional create and update, and
the patch formats JSON Patch, FHIRPath Patch (`application/fhir+json`) and
their XML variants. A declared interaction does not prove that a write will
succeed: how the server treats `If-Match` was verified against a local server
only, never by writing to KTH.

These values describe the server at the date above and may change when KTH
updates or reconfigures it. Re-run the connectivity test to check the parts
MediCore relies on.

## Errors and safe logging

Every client runs its requests through `FhirRequestExecutor`, which translates
every HAPI client failure into `FhirServerException` with one of these
categories:

| Category | Meaning |
|---|---|
| `TIMEOUT` | Read, connect or connection-pool timeout |
| `UNAVAILABLE` | Server not reachable (refused, reset, unknown host) or HTTP 502, 503, 504 |
| `INVALID_RESPONSE` | Response could not be parsed, was not FHIR or had the wrong resource type |
| `CLIENT_ERROR` | HTTP 4xx |
| `SERVER_ERROR` | Other HTTP 5xx |

The exception carries only safe metadata: operation name, category, HTTP status
(if any), OperationOutcome issues as `severity:code` and the root-cause class
name. It never contains server diagnostics, response bodies, request URLs, query
parameters or the original HAPI exception.

Operation names are fixed (`capabilities`, `patient-read`, `practitioner-read`,
`observation-read`/`-create`/`-search`, `condition-read`/`-create`/`-search`,
`encounter-read`/`-create`/`-search`,
`organization-read`/`-create`/`-update`, `location-read`/`-create`/`-update`);
IDs and search values never appear in
them. Invalid input (`IllegalArgumentException`) is rejected before any request
and is not logged by the executor.

Each failure is logged once as a single WARN line, for example:

```text
FHIR request failed: operation=encounter-search, type=INVALID_RESPONSE, httpStatus=-, issues=[], cause=DataFormatException, durationMs=120
```

Successful requests are logged at DEBUG with the operation name and duration.
Never log HAPI exception messages or stack traces, request URLs or response
bodies, and do not register HAPI's `LoggingInterceptor`: these can contain
patient data.

## When the FHIR server is unavailable (NIT-138)

MediCore starts and runs without the FHIR server: creating the client sends no
request (`FhirClientConfigTest`), and only a call to a FHIR client operation
contacts the server. There is no cache, no fallback data and no circuit
breaker: every call goes to the server, fails on its own, and the next call
tries again (a recovered server works immediately).

Verified for **every** operation of the Patient, Practitioner, Observation,
Condition, Encounter, Organization and Location clients by
`FhirUnavailableServerTest` (local servers on `127.0.0.1`, synthetic data):

| Situation | Category | HTTP status | Requests sent |
|---|---|---|---|
| Connection refused (nothing listens on the port) | `UNAVAILABLE` (`ConnectException`) | – | none received |
| Host cannot be resolved | `UNAVAILABLE` (`UnknownHostException`) | – | none |
| Server accepts but does not answer within `socket-timeout` | `TIMEOUT` (`SocketTimeoutException`) | – | exactly 1 (an update stops in its `GET`; no `PUT`) |
| Server answers 503 | `UNAVAILABLE` | 503 | exactly 1 |
| Resource missing (404) or gone (410) | `CLIENT_ERROR` | 404 / 410 | exactly 1; an update stops in its `GET` |
| Patient without records (search) | no error: empty list | – | 1 |
| Connect or connection-pool timeout | `TIMEOUT` | – | translation and logging verified; a real network connect timeout only in the opt-in `FhirConnectTimeoutIT` |

In every case the exception and the single WARN line contain only the
operation name, category, status, coded issues and cause class: no IDs, names,
URLs, host, port or server diagnostics.

**Timeouts** (see [Client settings](#client-settings)): connect 10 s,
connection pool 10 s and socket 30 s. The socket timeout limits **inactivity**,
not the total duration, so a slowly streaming response can take longer. The
error reports the root cause: a real connect timeout
(`ConnectTimeoutException` caused by "connect timed out") and a read timeout
both appear as `cause=SocketTimeoutException`; only a connection-pool timeout
has its own cause type.

**No automatic retries.** Every request is sent **at most once**; neither
MediCore nor the HTTP client resends it.

- `FhirClientConfig` disables Apache HttpClient's automatic retries (by
  default it would resend an idempotent `GET` up to three times when the server
  closes the connection without a response, and log each retry at INFO with the
  server's scheme, host and port). HAPI's timeouts, connection pool and other
  client settings are unchanged.
- Verified for every `GET` (reads, searches, the read phase of updates) and
  every `POST`/`PUT`: on a timeout, and on a connection closed without a
  response (`UNAVAILABLE`, `cause=NoHttpResponseException`), the server
  receives exactly one request and nothing but the single safe WARN line is
  logged.
- A failed write **may already have been committed**; read before trying again,
  and never resend it automatically. A caller that wants to retry a read must do
  so explicitly.

To check a real connect timeout on your network (one `GET` to the
non-routable TEST-NET address `192.0.2.1:9`, never to KTH):

```powershell
.\mvnw.cmd test "-Dtest=FhirConnectTimeoutIT" "-Dmedinexus.fhir.connect-timeout-test=true"
```

It expects `TIMEOUT` within a few seconds; a network that rejects the address
immediately reports `UNAVAILABLE` instead, which is why it is not part of the
normal build.

### US-03.06 verification status

| Acceptance criterion | Status |
|---|---|
| FHIR versus PostgreSQL ownership documented | done: [../DATA_OWNERSHIP.md](../DATA_OWNERSHIP.md) (static code inspection) |
| Synthetic FHIR patient journey (Patient, Practitioner, Encounter, Observation, Condition, Organization, Location) | verified without a database: `FhirPatientJourneyIntegrationTest` |
| Unavailable server, refused connection, read timeout, 404/410, 503 | verified: `FhirUnavailableServerTest` |
| Safe exception translation and logging | verified for all FHIR client operations |
| Failed requests (writes and reads) are not retried automatically | verified; the HTTP client's own retries are disabled |
| Connect timeout on a real network | translation verified; real network check opt-in only |
| A local account linked to its FHIR Patient ID within the journey | verified: `AccountFhirPatientJourneyIntegrationTest` (PostgreSQL `medinexus_test`) |
| A failed identity assignment (FHIR resource missing, FHIR unavailable, account already linked) leaves e-mail, role, password hash and FHIR references unchanged | verified: `AccountFhirPatientJourneyIntegrationTest`, `FhirOutageCoreFunctionalityIntegrationTest`; during an outage exactly one connection attempt is made |
| Registration, login, `/api/auth/me`, `/api/health` and logout keep working while FHIR is unavailable | verified over real HTTP: `FhirOutageCoreFunctionalityIntegrationTest`; they make no FHIR request at all |
| No credentials or sensitive data in logs and messages | verified: no e-mail, password, password hash, FHIR ID, FHIR server address or database password in the captured logs and exception messages |

The two PostgreSQL tests (package `integration`) run only when `DB_URL` points
to `jdbc:postgresql://localhost:5432/medinexus_test` (or `127.0.0.1`);
otherwise they are skipped and never connect. They create accounts with
random `@example.test` e-mails and delete exactly those accounts afterwards.

## Safety rules for the shared KTH server

- The server is **shared by all course participants** and requires **no
  authentication**. Everything stored there is visible to, and can be changed
  or deleted by, anyone.
- **Never send real patient data** or anything that identifies a real person.
- **No write requests** (create, update, patch, delete, transaction) without
  explicit approval. Writes must use clearly synthetic data.
- **Do not rely on existing data.** The server exposes administrative
  operations such as `$expunge` and `$reindex` without authentication, so
  resources can change or disappear at any time.
- Automated tests that need a FHIR server use a **local test server** on
  `127.0.0.1` with synthetic data (`Fhir*ClientTest`, `Fhir*CreateTest`,
  `Fhir*SearchTest`, `Fhir*UpdateTest`, `FhirClinicalRoundTripTest`,
  `FhirEncounterRoundTripTest`, `FhirPatientJourneyIntegrationTest` with
  `LocalFhirServer`). Tests against KTH stay **opt-in and read-only**.

## Why there are no write tests against KTH

MediCore can create Patient (conditional create), Observation, Condition,
Encounter, Organization and Location resources and update Organization and
Location resources, but every create and update test runs against a **local**
HTTP server only. The round-trip tests
(`FhirClinicalRoundTripTest`, `FhirEncounterRoundTripTest`) and
`FhirOrganizationUpdateTest`/`FhirLocationUpdateTest` use a local stateful server
that stores created resources and serves them to read and search requests; for
Organization and Location it also keeps versions and enforces `If-Match`. There
are no write tests against KTH because:

- data written to the shared, unauthenticated server is visible to everyone;
- a `DELETE` does not remove a resource completely: its history remains on the
  server. Removing it entirely would require `$expunge`, an administrative
  operation MediNexus must not use.

If a round-trip against KTH is required later, it needs separate approval,
clearly synthetic data, server-assigned IDs and must remain opt-in.
