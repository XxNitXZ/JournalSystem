# MediNexus – Local PostgreSQL

Each developer runs their own PostgreSQL database in Docker. The database runs
on their own machine. There is no shared database server and no Docker Compose.

Two start scripts with **identical configuration** are provided:

| Platform                    | Script               |
|-----------------------------|----------------------|
| Windows 11 (PowerShell)     | `start-postgres.ps1` |
| macOS (zsh / bash)          | `start-postgres.sh`  |

Both scripts are safe to run repeatedly. They create what is missing, start what
is stopped and reuse what is already running. They **never delete or recreate**
containers or volumes.

The database holds local accounts and their FHIR ID references only; clinical
data stays in FHIR. See [../DATA_OWNERSHIP.md](../DATA_OWNERSHIP.md).

## Configuration

| Setting          | Value                         |
|------------------|-------------------------------|
| Docker image     | `postgres:18`                 |
| Container name   | `medinexus-postgres`          |
| Docker volume    | `medinexus-postgres-data`     |
| Volume mount     | `/var/lib/postgresql`         |
| Database         | `medinexus_db`                |
| Username         | `medinexus`                   |
| Host             | `127.0.0.1` (localhost only)  |
| Port             | `5432` (host) → `5432` (container) |
| Authentication   | `scram-sha-256` (password required, no `trust`) |

JDBC URL for later use: `jdbc:postgresql://127.0.0.1:5432/medinexus_db`

> PostgreSQL 18 images store the data in a version-specific subdirectory
> (`/var/lib/postgresql/18/docker`). The volume is therefore mounted at
> `/var/lib/postgresql`, not at the older `/var/lib/postgresql/data`.

## Prerequisites

### Windows 11

- Docker Desktop for Windows is installed and running with **Linux containers**
  (the default).
- Windows PowerShell 5.1 (built in) or PowerShell 7.
- PowerShell must be allowed to run local scripts. If it says
  *"running scripts is disabled on this system"*, choose one of these:
  ```powershell
  # Allow local scripts for your user only (one-time setting):
  Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned

  # …or bypass the policy for this one run only:
  powershell -ExecutionPolicy Bypass -File .\infrastructure\database\start-postgres.ps1
  ```

### macOS

- Docker Desktop for Mac is installed and running.
- `bash` (the built-in `/bin/bash` 3.2 is enough). The script runs with bash
  even if your login shell is zsh.

## Start PostgreSQL

Run the script from the project root (`MediNexus/`).

### Windows

```powershell
.\infrastructure\database\start-postgres.ps1
```

### macOS

```bash
bash infrastructure/database/start-postgres.sh
```

What the script does:

1. Checks that Docker Desktop is running.
2. Creates the Docker network `medinexus-network` if it does not exist. MediCore
   reaches the database through it; see
   [../README.md](../README.md#prerequisites-and-network-setup).
3. Checks whether the container `medinexus-postgres` exists.
   - **Exists:** checks its image, volume mount and port binding. If any of
     them differ, the script stops, explains the conflict and changes nothing.
     If they match, it attaches the container to `medinexus-network` if it is
     not attached yet (`docker network connect`, no restart, other connections
     kept), then starts the container if it is stopped or reuses it if it is
     already running.
   - **Does not exist:** checks that host port 5432 is free and asks for a new
     database password. It creates the volume only if it is missing, then
     creates the container on `medinexus-network`.
4. Waits for PostgreSQL with `pg_isready`, up to 30 attempts 2 seconds apart.
5. Prints the connection details.

The first run downloads the `postgres:18` image, so it can take a minute.

## Stop, start and restart

| Action  | Command (same on Windows and macOS)                           |
|---------|---------------------------------------------------------------|
| Stop    | `docker stop medinexus-postgres`                              |
| Start   | Run the start script again (recommended), or `docker start medinexus-postgres` |
| Restart | `docker restart medinexus-postgres`                           |
| Status  | `docker ps --filter name=medinexus-postgres`                  |
| Logs    | `docker logs medinexus-postgres`                              |
| psql    | `docker exec -it medinexus-postgres psql -U medinexus -d medinexus_db` |

Stopping or restarting the container **does not delete any data**.

The container has no automatic restart policy. After a reboot or a Docker
Desktop restart, run the start script again.

## Persistent data (Docker volume)

All database files live in the named Docker volume `medinexus-postgres-data`.
The volume:

- keeps the data when you stop, start or restart the container,
- keeps the data when you restart Docker Desktop or reboot,
- is local to your machine and is **not** stored in the Git repository.

If the container is removed but the volume still exists, the script creates a
new container on top of the existing volume. The existing data is reused, and so
is its **original password**. A password entered at that point is ignored.

## Password handling

- **No password is stored in the repository.** None is hardcoded in the scripts,
  and none appears in this README.
- The password is requested **only once**, when the container is first created.
  The prompt hides your typing: Windows uses `Read-Host -AsSecureString` and
  macOS uses `read -s`. Empty or whitespace-only passwords are rejected, and you
  must type the password twice.
- The password is never placed on the command line. The scripts pass it to
  `docker run` through that one process's environment, using
  `--env POSTGRES_PASSWORD` with no value. That keeps it out of shell history
  and process listings.
- PostgreSQL uses `scram-sha-256` password authentication for both TCP and
  local socket connections. `trust` authentication is not enabled.
- The port is bound to `127.0.0.1` only, so the database is not reachable from
  other machines on your network.
- Choose a **local development password** that you don't use anywhere else.

> **Security note: Docker metadata exposes environment variables.**
> Docker stores the container's environment variables, including
> `POSTGRES_PASSWORD`, in the container's configuration. Anyone who can use
> Docker on your machine can read that password, for example with
> `docker inspect medinexus-postgres` or `docker exec medinexus-postgres env`.
> On Windows that means members of the `docker-users` group; on macOS it means
> your user account. This is acceptable for a local development database, but
> it is another reason not to reuse a real password.

### Changing the password

Changing the password in PostgreSQL is enough. The container does not need to be
recreated:

```bash
docker exec -it medinexus-postgres psql -U medinexus -d medinexus_db
# then, at the psql prompt:
\password medinexus
```

The old value in `docker inspect` is only used when the database is first
initialized, so after you change the password that stored value is out of date.

## One database per developer

- Each developer runs the start script on their **own machine** and gets a
  separate container, volume and database.
- Each developer picks their **own password**. Passwords are never shared
  through Git or chat.
- Container name, database name, username and port are the same everywhere.
  Configuration added later (for example the Spring Boot datasource) can then
  use the same host, port, database and username for both developers. Only the
  password is supplied per developer.
- Data is **not synchronized** between developers. The shared schema comes
  from Flyway migrations in the repository (see
  [Database migrations](#database-migrations-flyway)).

## Connecting MediCore (Spring Boot)

MediCore reads its database connection from three environment variables:

| Variable      | Value                                              |
|---------------|----------------------------------------------------|
| `DB_URL`      | `jdbc:postgresql://127.0.0.1:5432/medinexus_db`    |
| `DB_USERNAME` | `medinexus`                                        |
| `DB_PASSWORD` | the password you chose when you first ran the start script |

`application.properties` only contains the placeholders `${DB_URL}`,
`${DB_USERNAME}` and `${DB_PASSWORD}`, with no default values. If a variable is
missing, startup stops with a short report that names the missing variables:

```text
APPLICATION FAILED TO START
Description:
The database connection is not configured. Missing environment variable(s): DB_PASSWORD
```

Set the variables **only in the terminal session or IntelliJ run
configuration** that starts MediCore.
- Don't put them in system or user environment variables
  (`setx`, `[Environment]::SetEnvironmentVariable`, `~/.zshrc`).
- Don't put them in any file inside the repository.

### Windows (PowerShell)

Run these in the PowerShell window you start MediCore from, in the project
root. The values only exist in that window:

```powershell
$env:DB_URL      = 'jdbc:postgresql://127.0.0.1:5432/medinexus_db'
$env:DB_USERNAME = 'medinexus'
$secure = Read-Host 'Database password' -AsSecureString     # input is hidden
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $secure).Password
Remove-Variable secure

.\mvnw.cmd spring-boot:run
```

Remove the variables when you are done, or just close the window:

```powershell
Remove-Item Env:DB_URL, Env:DB_USERNAME, Env:DB_PASSWORD
```

### macOS (zsh / bash)

Run these in the terminal you start MediCore from, in the project root:

```bash
export DB_URL='jdbc:postgresql://127.0.0.1:5432/medinexus_db'
export DB_USERNAME='medinexus'

# zsh (default macOS shell) - input is hidden:
read -rs 'DB_PASSWORD?Database password: '; echo
# bash alternative:
# read -rs -p 'Database password: ' DB_PASSWORD; echo

export DB_PASSWORD
sh mvnw spring-boot:run
```

`mvnw` is not marked as executable in Git, so use `sh mvnw ...`. Running
`chmod +x mvnw` also works, but Git then reports a file-mode change.

Remove the variables when you are done, or just close the terminal:

```bash
unset DB_URL DB_USERNAME DB_PASSWORD
```

### IntelliJ IDEA (Windows and macOS)

1. Open **Run → Edit Configurations…** and select the `MediNexusApplication`
   run configuration.
2. In **Environment variables**, click the list icon and add the three
   variables `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`. In the one-line field
   they are separated by `;` on both Windows and macOS.
3. Leave **Store as project file** unchecked.

The run configuration is then saved in `.idea/workspace.xml` on your machine
only. The whole `.idea/` directory is listed in `.gitignore`, so it is never
committed. IntelliJ keeps the value in plain text in that local file, so treat
your machine's user account as the protection for it.

For tests started from IntelliJ, add the same variables in either of these
places:
- **Run → Edit Configurations… → Edit configuration templates… → JUnit**, for
  new JUnit run configurations.
- **Settings → Build, Execution, Deployment → Build Tools → Maven → Runner →
  Environment variables**, for Maven goals run inside IntelliJ.

### Checking the connection

With MediCore running, open a second terminal and run:

```powershell
# Windows
Invoke-RestMethod http://localhost:8080/actuator/health
```

```bash
# macOS
curl -s http://localhost:8080/actuator/health
```

`{"status":"UP"}` means the database health check succeeded. If the database
is unreachable or the password is wrong, the endpoint returns
`{"status":"DOWN"}` with HTTP 503.

To see the individual checks without changing any configuration, start MediCore
with this extra argument:

```bash
./mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--management.endpoint.health.show-components=always"   # Windows
sh mvnw spring-boot:run "-Dspring-boot.run.arguments=--management.endpoint.health.show-components=always"       # macOS
```

The response then contains `"db":{"status":"UP"}`.

### Tests

`MediNexusApplicationTests` and `PersistenceCheckRepositoryIntegrationTest`
start the full application context, which includes the datasource and
Hibernate. Hibernate connects to PostgreSQL during startup to detect the
database version, so these tests need:
- the PostgreSQL container running,
- the three variables set (as above) with your **real** password,
- a database that Flyway can migrate: either a fresh one, or an existing one
  that has been [baselined once](#existing-database-one-time-baseline),

before you run `mvnw test`. Without the variables they fail with the
"Missing environment variable(s)" report. Web-only tests such as
`HealthControllerTest` don't need a database.

## Database migrations (Flyway)

### What Flyway does

Flyway manages the database **schema**: tables, columns, indexes and
constraints. Every schema change is an SQL file in the repository. Flyway
records which files it has already applied, so every developer's database ends
up with the same schema.

Hibernate (Spring Data JPA) only **reads and writes data**. It never changes the
schema.

### Migration files

Migrations live in `src/main/resources/db/migration/` and are named:

```text
V<version>__<description>.sql
V1__create_persistence_check.sql
V2__create_users.sql      (example of a later migration)
```

- `V`, then the version number, then **two** underscores, then a description
  with underscores instead of spaces.
- Flyway applies them in version order, each exactly once.
- Flyway records each applied file in the table `flyway_schema_history`,
  together with a checksum of its content.

### What happens on startup

When MediCore starts, and also in `MediNexusApplicationTests` and
`PersistenceCheckRepositoryIntegrationTest`:

1. Flyway connects with the normal datasource (`DB_URL`, `DB_USERNAME`,
   `DB_PASSWORD`). There is no separate Flyway configuration or password.
2. It checks the applied migrations against the files: no file may be changed
   or missing.
3. It applies any new migrations, in order.
4. Only then does Hibernate start.

If any of these steps fails, MediCore does not start.

Flyway **never** runs `clean`, which would drop all objects
(`spring.flyway.clean-disabled=true`). It also never baselines automatically
(`spring.flyway.baseline-on-migrate=false`). If it finds tables but no
`flyway_schema_history`, it refuses to start rather than guess.

### Fresh (empty) database

Nothing to do. On the first start, Flyway creates `flyway_schema_history` and
runs `V1__create_persistence_check.sql`. The log shows lines similar to:

```text
Creating Schema History table "public"."flyway_schema_history" ...
Migrating schema "public" to version "1 - create persistence check"
Successfully applied 1 migration to schema "public", now at version v1
```

> Do **not** run `create-persistence-check.sql` by hand on a new database. That
> script is from before Flyway was introduced and is kept only for reference. If
> you run it, Flyway finds a non-empty database without history and refuses to
> start.

### Existing database: one-time baseline

If you created `persistence_check` by hand with `create-persistence-check.sql`
(before Flyway was introduced), MediCore stops at startup with:

```text
Found non-empty schema(s) "public" but no schema history table.
```

Your table already matches V1, so you tell Flyway once that the database is
already at version 1. This is called a **baseline**. Do this only once per
database.

#### Step 1 - Check the database (read-only)

```bash
docker exec -it medinexus-postgres psql -U medinexus -d medinexus_db -c "SELECT current_database(), current_user, current_schema();" -c "\dt public.*" -c "\d public.persistence_check" -c "SELECT to_regclass('public.flyway_schema_history') AS flyway_history;"
```

`psql` asks for your password once. Continue **only** if the output shows:

| Check | Expected |
|---|---|
| Database / user / schema | `medinexus_db`, `medinexus`, `public` |
| `\dt public.*` | exactly one table: `persistence_check` |
| `\d public.persistence_check` | `id bigint not null generated by default as identity`, `description character varying(255) not null`, primary key `persistence_check_pkey` on `id` |
| `flyway_history` | empty, meaning no Flyway history exists yet |

If anything differs (other tables, other columns, or `flyway_history` already
shows `flyway_schema_history`), **stop** and don't baseline. Ask the team first.

#### Step 2 - Baseline

Set `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` in the terminal, as described in
[Connecting MediCore](#connecting-medicore-spring-boot). Then run this once from
the project root:

```powershell
# Windows (PowerShell) - keep the quotes
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--spring.flyway.baseline-on-migrate=true --spring.flyway.baseline-version=1 --spring.main.web-application-type=none"
```

```bash
# macOS
sh mvnw spring-boot:run -Dspring-boot.run.arguments="--spring.flyway.baseline-on-migrate=true --spring.flyway.baseline-version=1 --spring.main.web-application-type=none"
```

- The three arguments apply **to this run only**. Nothing is saved.
  `--spring.main.web-application-type=none` makes MediCore exit by itself after
  startup instead of starting the web server.
- What this changes in the database:
  - It creates the table `public.flyway_schema_history` and its index
    `flyway_schema_history_s_idx`.
  - It inserts **one** row into it: version `1`, type `BASELINE`, description
    `<< Flyway Baseline >>`.
  - V1 is **not** executed.
  - `persistence_check` and its rows are **not** touched.
- The log shows `Successfully baselined schema with version: 1`, then
  `Started MediNexusApplication`, then the process ends.

#### Step 3 - Check the result

```bash
docker exec -it medinexus-postgres psql -U medinexus -d medinexus_db -c "SELECT installed_rank, version, description, type, success FROM flyway_schema_history ORDER BY installed_rank;"
```

Expected: a single row `1 | 1 | << Flyway Baseline >> | BASELINE | t`.

From now on, start MediCore normally. The log shows
`Schema "public" is up to date. No migration necessary.`

### Adding a migration

1. Create a new file with the next free version, for example
   `src/main/resources/db/migration/V2__create_users.sql`. Check the existing
   files first, and coordinate version numbers with the team so two branches
   don't both use `V2`.
2. Write plain PostgreSQL DDL. Write the matching JPA entity, or change it, to
   fit the new schema.
3. Start MediCore or run the tests. Flyway applies the new file.
4. Commit the migration together with the code that needs it.

Rules:
- **Never edit or rename a migration that has been applied or committed.**
  Flyway detects the changed checksum and refuses to start. Fix mistakes with a
  new migration.
- Never delete migration files.
- Don't change the schema by hand with `psql`. Every change goes through a
  migration.

### Why Hibernate's `ddl-auto` stays `none`

`spring.jpa.hibernate.ddl-auto=none` means Hibernate never creates, updates or
drops tables.
- If Hibernate also changed the schema, Hibernate and Flyway could disagree,
  and the migrations would no longer describe the real database.
- `update` cannot handle renames and never removes anything.
- `create` and `create-drop` delete data.

The schema therefore has exactly one source: the migration files.

## Persistence verification

`PersistenceCheckRepositoryIntegrationTest` checks that MediCore can really
write to and read from your PostgreSQL database through Spring Data JPA. It uses
the technical entity `PersistenceCheck` and the table `persistence_check`. It
contains no application or patient data.

The test:
1. inserts one row with a random description (`persistence-check-<uuid>`) and
   flushes it to PostgreSQL,
2. clears Hibernate's persistence context, so the next read cannot come from
   Hibernate's cache,
3. reads the row back by its generated ID and compares the description,
4. reads it again after the transaction has committed,
5. deletes **only that row**, even if an assertion failed.

### 1. Schema

The table is created by the Flyway migration `V1__create_persistence_check.sql`
(see [Database migrations](#database-migrations-flyway)):
- **Fresh database:** nothing to do.
- **Database where you created the table by hand:**
  [baseline it once](#existing-database-one-time-baseline).

To check that the table exists:

```bash
docker exec -it medinexus-postgres psql -U medinexus -d medinexus_db -c "\d persistence_check"
```

### 2. Run the integration test

Set `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` in the terminal first, as
described in [Connecting MediCore](#connecting-medicore-spring-boot). Then run
the test on its own:

```powershell
# Windows (PowerShell) - keep the quotes
.\mvnw.cmd test "-Dtest=PersistenceCheckRepositoryIntegrationTest"
```

```bash
# macOS
sh mvnw test -Dtest=PersistenceCheckRepositoryIntegrationTest
```

You can also run the whole suite with `.\mvnw.cmd test` (Windows) or
`sh mvnw test` (macOS). The integration test is part of it.

To see the SQL that the test sends to PostgreSQL, add
`"-Dlogging.level.org.hibernate.SQL=debug"` to the command. This only affects
that run; the configuration is unchanged.

In IntelliJ, add the three variables to the JUnit run configuration (see
[IntelliJ IDEA](#intellij-idea-windows-and-macos)) and run the test class.

### Expected result

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

With SQL logging enabled, the output contains an `insert into persistence_check`,
a `select ... from persistence_check` and a `delete from persistence_check`.

After the test, the table is empty again, unless you added rows yourself:

```bash
docker exec -it medinexus-postgres psql -U medinexus -d medinexus_db -c "SELECT count(*) FROM persistence_check;"
```

### If the test fails

| Message in the test output | Cause |
|---|---|
| `Missing environment variable(s): ...` | The `DB_*` variables are not set in this terminal. |
| `Unable to determine Dialect without JDBC metadata` and `SQLState: 28P01` | Wrong password, see [Troubleshooting](#health-shows-down--password-authentication-failed-for-user-medinexus). |
| `Connection to 127.0.0.1:5432 refused` | The container is not running. Run the start script. |
| `Found non-empty schema(s) "public" but no schema history table` | Existing database without Flyway history. See [one-time baseline](#existing-database-one-time-baseline). |
| `Validate failed: Migrations have failed validation` / `checksum mismatch` | An applied migration file was edited. Restore the original file and make the change in a new migration. |
| `relation "persistence_check" does not exist` (`SQLState: 42P01`) | The database was baselined without the table. Check it with [step 1 of the baseline](#step-1---check-the-database-read-only). |

## Troubleshooting

### "Missing environment variable(s): DB_..." when starting MediCore

The listed variables are not set in the terminal or run configuration that
started MediCore. See [Connecting MediCore](#connecting-medicore-spring-boot).
A variable set in one terminal window is not visible in another window or in
IntelliJ.

### Health shows `DOWN` / "password authentication failed for user medinexus"

If MediCore does not start at all and the log shows
`Unable to determine Dialect without JDBC metadata` together with
`SQLState: 28P01`, the cause is the same: Hibernate could not log in to
PostgreSQL during startup. The checks are:

- Check that the container is running: `docker ps --filter name=medinexus-postgres`
  (or run the start script).
- Check that `DB_PASSWORD` is the password chosen on first initialization. A
  password typed later into the start script is ignored if the volume already
  existed.
- If you forgot the password, see
  [Changing the password](#changing-the-password) or
  [Forgotten password](#forgotten-password--starting-from-scratch).

### "Docker Desktop is not running or not reachable"

- Start Docker Desktop and wait until it shows that the engine is running.
- Check it with `docker info`.
- **Windows:** if `docker` is not recognized, reopen the terminal after
  installing Docker Desktop. Also check that Docker Desktop is set to **Linux
  containers**.
- **macOS:** if `docker` is not found, open Docker Desktop once so it can
  install its command-line tools.

### "Host port 5432 is already in use"

Something else is already listening on port 5432. Usually that is a locally
installed PostgreSQL or another PostgreSQL container.

Find out what is using the port:

```powershell
# Windows
Get-NetTCPConnection -LocalPort 5432 -State Listen |
  Select-Object LocalAddress, OwningProcess, @{n='Process';e={(Get-Process -Id $_.OwningProcess).ProcessName}}
docker ps --filter publish=5432
```

```bash
# macOS
lsof -nP -iTCP:5432 -sTCP:LISTEN
sudo lsof -nP -iTCP:5432 -sTCP:LISTEN   # also shows processes of other users
docker ps --filter publish=5432
```

Then stop it:

- **Windows (installed PostgreSQL service):** open *Services* (`services.msc`)
  and stop the `postgresql-x64-<version>` service, or run
  `Stop-Service postgresql-x64-<version>` in an elevated PowerShell.
- **macOS (Homebrew):** `brew services stop postgresql@<version>`
- **macOS (Postgres.app):** stop the server from the Postgres.app menu.
- **Another container:** `docker stop <container-name>`

Port 5432 is intentionally the same for every developer. Agree on a change with
the team before using a different port.

### "A container named 'medinexus-postgres' already exists but its configuration differs"

This means an older or manually created container with the same name exists. The
script will not touch it. Inspect it first:

```bash
docker inspect medinexus-postgres
```

If you want to keep it, rename it so that the script can create a correctly
configured one:

```bash
docker rename medinexus-postgres medinexus-postgres-old
```

### "PostgreSQL did not become ready" or "container is no longer running"

Read the logs:

```bash
docker logs medinexus-postgres
```

A common cause is an existing volume that contains data from a different
PostgreSQL major version, for example a volume created with `postgres:16`.
PostgreSQL 18 cannot open that data directly.

### Forgotten password / starting from scratch

> ⚠️ **This permanently deletes your local database.** The scripts never do
> this. Run these commands yourself only if you really want an empty database.

```bash
docker stop medinexus-postgres
docker rm medinexus-postgres
docker volume rm medinexus-postgres-data
```

Then run the start script again. It will ask for a new password.
