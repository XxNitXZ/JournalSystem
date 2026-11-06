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

US-03.01 establishes the connection to the FHIR server:

- a configured HAPI FHIR R4 client (`fhir/config/FhirClientConfig`),
- one **read-only** operation: `FhirServerClient.fetchCapabilityStatement()`
  (`GET [base]/metadata`), which returns what the server supports,
- timeouts, error categories and safe logging (see below),
- an opt-in test against the real server.

MediCore does **not** yet read, search, create, update or delete Patient,
Practitioner, Observation, Condition or Encounter resources. The operations listed
under [Observed server capabilities](#observed-server-capabilities) are what the
**server** declares, not features of MediCore. They are reference information for
later user stories.

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

These values describe the server at the date above and may change when KTH
updates or reconfigures it. Re-run the connectivity test to check the parts
MediCore relies on.

## Errors and safe logging

`FhirServerClient` translates every HAPI client failure into
`FhirServerException` with one of these categories:

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

Each failure is logged once as a single WARN line, for example:

```text
FHIR request failed: operation=capabilities, type=CLIENT_ERROR, httpStatus=404, issues=[error:not-found], cause=ResourceNotFoundException, durationMs=120
```

Successful requests are logged at DEBUG with the operation name and duration.
Never log HAPI exception messages or stack traces, request URLs or response
bodies, and do not register HAPI's `LoggingInterceptor`: these can contain
patient data.

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
- Automated tests that need a FHIR server use a **local test server**
  (`FhirServerClientTest`). Tests against KTH stay **opt-in and read-only**.

## Why there is no write test

US-03.01 is about connectivity. Reading the CapabilityStatement already proves
that MediCore reaches the server, that it speaks FHIR R4 and that the required
resource types and interactions are available. A write test was deliberately not
added because:

- MediCore has no create, read or delete operations for clinical resources yet;
  adding them would anticipate later user stories.
- Data written to the shared, unauthenticated server is visible to everyone.
- A `DELETE` does not remove a resource completely: its history remains on the
  server. Removing it entirely would require `$expunge`, an administrative
  operation MediNexus must not use.

The behaviour with **synthetic data** is covered locally instead:
`FhirServerClientTest` runs the client against a local test server that returns
synthetic CapabilityStatements, OperationOutcomes, errors and timeouts. If a
round-trip against KTH is required later, it needs separate approval, clearly
synthetic data, server-assigned IDs and must remain opt-in.
