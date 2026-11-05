# MediNexus – Running the stack in Docker

MediNexus runs as three Docker containers on each developer's machine. They are
started with plain Docker CLI scripts (no Docker Compose), one per component,
for Windows (PowerShell) and macOS (bash).

| Component | Container              | Image                        | Start script (Windows / macOS) |
|-----------|------------------------|------------------------------|--------------------------------|
| Database  | `medinexus-postgres`   | `postgres:18`                | `database/start-postgres.ps1` / `.sh` |
| Backend   | `medinexus-medicore`   | `medinexus-medicore:local`   | `medicore/start-medicore.ps1` / `.sh` |
| Frontend  | `medinexus-mediportal` | `medinexus-mediportal:local` | `mediportal/start-mediportal.ps1` / `.sh` |

PostgreSQL details (password, volume, Flyway baseline, troubleshooting) are in
[database/README.md](database/README.md).

## Architecture

```text
 Your browser
   │  http://localhost:5173  (page)           http://localhost:8080  (API calls)
   ▼                                          ▼
 127.0.0.1:5173 ──► medinexus-mediportal      127.0.0.1:8080 ──► medinexus-medicore
                    Nginx, port 8080                             Spring Boot, port 8080
                    (static React build)                          │
                                                                  │ jdbc:postgresql://medinexus-postgres:5432/medinexus_db
                                                                  ▼  (Docker network: medinexus-network)
 127.0.0.1:5432 ──────────────────────────────────────────────► medinexus-postgres
                                                                  PostgreSQL 18, port 5432
                                                                  │
                                                                  ▼
                                                     Docker volume: medinexus-postgres-data
```

- The **browser** loads MediPortal from `http://localhost:5173` and then calls
  MediCore **directly** at `http://localhost:8080`. Nginx does not forward API
  requests.
- **MediCore** reaches PostgreSQL through the Docker network by container name
  (`medinexus-postgres`). Container names only work *between containers*; the
  browser can never use them.
- All ports are published on `127.0.0.1` only, so nothing is reachable from
  other machines on your network.

## Ports

| Container              | Host address (your machine) | Container port | Used by |
|------------------------|-----------------------------|----------------|---------|
| `medinexus-mediportal` | `127.0.0.1:5173`            | `8080` (Nginx, unprivileged) | browser |
| `medinexus-medicore`   | `127.0.0.1:8080`            | `8080` (Spring Boot)         | browser (API), tools |
| `medinexus-postgres`   | `127.0.0.1:5432`            | `5432`                       | MediCore (via network), local tools |

MediPortal uses host port **5173** on purpose: it is the same origin as the
Vite development server (`npm run dev`), which MediCore's CORS configuration
allows (`http://localhost:5173`). As a consequence, the Docker frontend and
`npm run dev` cannot run at the same time; the start script detects this.

## Prerequisites and network setup

1. Docker Desktop is installed and running (Linux containers). See
   [database/README.md](database/README.md#prerequisites) for the PowerShell
   execution policy on Windows.
2. That's all. The Docker network is set up **automatically** by the
   PostgreSQL start script (`start-postgres.ps1` / `start-postgres.sh`):
   - If `medinexus-network` does not exist, it creates it
     (`docker network create medinexus-network`).
   - A **new** PostgreSQL container is created directly on
     `medinexus-network`.
   - An **existing** PostgreSQL container that is not yet attached is
     attached with `docker network connect medinexus-network medinexus-postgres`.
     This works whether it is running or stopped, does not restart PostgreSQL
     and does not touch the data, the volume or the Flyway history. Its other
     network connections (for example Docker's default `bridge`) are kept.
   - If it is already attached, nothing is changed.

   The MediCore and MediPortal scripts only **check** the network. If it is
   missing, they ask you to run the PostgreSQL start script first. Check the
   network at any time with `docker network inspect medinexus-network`.

## Starting everything

Run the scripts from the project root (`MediNexus/`), in this order:
PostgreSQL → MediCore → MediPortal. The first PostgreSQL run asks for a new
database password; see [database/README.md](database/README.md#start-postgresql).

### Windows (PowerShell)

```powershell
.\infrastructure\database\start-postgres.ps1
.\infrastructure\medicore\start-medicore.ps1
.\infrastructure\mediportal\start-mediportal.ps1
```

### macOS (zsh / bash)

```bash
bash infrastructure/database/start-postgres.sh
bash infrastructure/medicore/start-medicore.sh
bash infrastructure/mediportal/start-mediportal.sh
```

Then open **http://localhost:5173**. Use `localhost`, not `127.0.0.1`: the
browser origin must be exactly `http://localhost:5173` for CORS. The home page
shows "Backend: Online" when MediCore and PostgreSQL are running.

### What the scripts do

Every script distinguishes three separate steps and only does what is needed:

| Step | When | What happens |
|------|------|--------------|
| **Build an image** | only if the image does not exist | `docker build ...` |
| **Create a container** | only if the container does not exist | port check, then `docker run ...` |
| **Start a container** | if the container exists but is stopped | port check, then `docker start ...` |
| Reuse | if the container is already running | nothing is changed |

Before reusing or starting an existing container, the script checks that it
has the expected image name, port binding and network (MediCore also checks
`DB_URL` and that `DB_USERNAME`/`DB_PASSWORD` are set). If anything differs, it
stops and explains the conflict. **The scripts never delete or recreate
containers, images, networks or volumes.** The only change made to an existing
container is the network attachment described in
[Prerequisites and network setup](#prerequisites-and-network-setup).

MediCore's script additionally:

- checks that `medinexus-postgres` is running, attached to `medinexus-network`
  and accepting connections,
- on first creation, takes `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` from
  your session, the root [`.env` file](#the-env-file-database-settings-for-medicore)
  or a prompt, and **tests the login** (`SELECT 1`) before creating the
  container, so a wrong password does not leave a broken container behind,
- waits until `http://127.0.0.1:8080/actuator/health` reports `UP` (application
  and database), and shows the last log lines if startup fails.

MediPortal's script additionally waits until Nginx serves the page and warns if
MediCore is not running.

## The .env file (database settings for MediCore)

To avoid typing the database password every time MediCore's container is
created, put the settings in a `.env` file in the **project root**.

### Creating it

Copy the committed template and replace the placeholder with your real
database password, the one you chose when `start-postgres` first created the
database:

```powershell
Copy-Item .env.example .env      # Windows
notepad .env
```

```bash
cp .env.example .env             # macOS
open -e .env
```

```dotenv
# MediNexus Docker Environment

DB_URL=jdbc:postgresql://medinexus-postgres:5432/medinexus_db
DB_USERNAME=medinexus
DB_PASSWORD=your-real-local-password
```

### How the scripts load it

Only `start-medicore.ps1` / `start-medicore.sh` read `.env`, and only when they
**create** the MediCore container. Both scripts behave the same:

- The file is read as **plain data**: one `NAME=value` per line. Blank lines and
  lines starting with `#` are ignored. An optional `export ` prefix and one pair
  of surrounding quotes (`"..."` or `'...'`) are removed. Nothing is executed
  or expanded, so `$`, backticks or `$(...)` in a password are taken literally.
  Quote a password that starts or ends with spaces.
- Only `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` are used; other names are
  ignored.
- The values exist only inside the running script. They are not set as Windows
  or macOS environment variables. They are handed to `docker run` for that one
  command and then removed.

**Precedence** (first non-empty value wins):

| Setting | 1. Session | 2. `.env` | 3. Fallback |
|---------|------------|-----------|-------------|
| `DB_URL` | `$env:DB_URL` / exported `DB_URL` | `DB_URL=` | `jdbc:postgresql://medinexus-postgres:5432/medinexus_db` |
| `DB_USERNAME` | `$env:DB_USERNAME` / exported `DB_USERNAME` | `DB_USERNAME=` | prompt (default `medinexus`), or `medinexus` when not interactive |
| `DB_PASSWORD` | `$env:DB_PASSWORD` / exported `DB_PASSWORD` | `DB_PASSWORD=` | hidden prompt |

Checks before anything is created:

- `DB_URL` must be exactly
  `jdbc:postgresql://medinexus-postgres:5432/medinexus_db`. Inside Docker,
  MediCore reaches PostgreSQL by its container name on `medinexus-network`;
  `127.0.0.1` or `localhost` would mean the MediCore container itself. A
  different value stops the script with an explanation.
- `DB_PASSWORD=CHANGE_ME` (the placeholder from `.env.example`) is rejected.
- The login is tested against PostgreSQL. A wrong password stops the script and
  nothing is created.

The scripts **never change the password stored in PostgreSQL**; `.env` must
contain the password the database already has.

### Never commit real credentials

`.env` is listed in `.gitignore` and excluded from both Docker build contexts,
so it is neither committed nor copied into an image. Only `.env.example`, with
the placeholder, belongs in Git. Anything that has been pushed to GitHub must
be treated as public, even after deleting it again. Each developer keeps their
own `.env` with their own local password.

### Editing .env does not update an existing container

Docker copies `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` into the container's
configuration when the container is **created**. Starting or reusing a
container does not read `.env` again (the script says so). After changing
`.env`, for example after changing the database password, recreate the MediCore
container (no data is lost):

```bash
docker stop medinexus-medicore
docker rm medinexus-medicore
```

Then run `start-medicore.ps1` / `start-medicore.sh` again.

### Docker vs. IntelliJ / `mvnw spring-boot:run`

These are two different ways to run MediCore, with different values:

| | MediCore in Docker | MediCore in IntelliJ or with `mvnw spring-boot:run` |
|---|---|---|
| Configured by | root `.env` (read by `start-medicore`) | the IntelliJ run configuration, or `$env:`/`export` in the terminal ([database/README.md](database/README.md#connecting-medicore-spring-boot)) |
| `DB_URL` | `jdbc:postgresql://medinexus-postgres:5432/medinexus_db` (container name, inside the Docker network) | `jdbc:postgresql://127.0.0.1:5432/medinexus_db` (the published port on your machine) |

Spring Boot does not read `.env` itself, and IntelliJ does not use it. If a
terminal still has the local `DB_URL` from a `mvnw` run, it takes precedence
over `.env` and the Docker script stops. Remove it for that terminal
(`Remove-Item Env:DB_URL` / `unset DB_URL`) or open a new terminal. Both ways
cannot use port 8080 at the same time.

## Environment variables

### Authentication sessions

MediCore uses in-memory HTTP sessions with HttpOnly, SameSite=Lax cookies and a
30-minute inactivity timeout. Restarting MediCore ends existing sessions. Set
`SESSION_COOKIE_SECURE=true` when serving the API over HTTPS.

Browser clients calling the API across origins must use `credentials: 'include'`.
Only the configured `MEDINEXUS_CORS_ALLOWED_ORIGINS` may make credentialed calls.

1. Call `GET /api/auth/csrf` to obtain `token` and `headerName`, retaining the
   session cookie. Send that token in the named header for POST requests,
   including registration, login, and logout. Missing or invalid tokens return 403.
2. Call `POST /api/auth/login` with JSON `email` and `password`. Success returns
   200 with `id`, `email`, and `role`, and rotates the session ID. Invalid
   credentials return an empty 401 response. Malformed JSON returns 400.
3. Fetch a new CSRF token after login. `GET /api/auth/me` returns the same user
   summary, or 401 without an authenticated session.
4. Call `POST /api/auth/logout` with a valid CSRF token. It returns 204,
   invalidates the session, and expires the session cookie. Bootstrap a new CSRF
   token before further writes.

Registration retains its request and response formats and 201/400/409 behavior
when supplied with a valid CSRF token. Frontend CSRF integration is separate work.

### Container settings

| Variable | Used by | When | Value |
|----------|---------|------|-------|
| `DB_URL` | MediCore container | on creation | session, `.env` or default; must be `jdbc:postgresql://medinexus-postgres:5432/medinexus_db` |
| `DB_USERNAME` | MediCore container | on creation | session, `.env`, or prompt (default `medinexus`) |
| `DB_PASSWORD` | MediCore container | on creation | session, `.env`, or hidden prompt; never `CHANGE_ME` |
| `VITE_API_BASE_URL` | MediPortal image | **at image build time** | default `http://localhost:8080`; not read from `.env` |
| `MEDINEXUS_CORS_ALLOWED_ORIGINS` | MediCore | optional | not set: MediCore allows `http://localhost:5173` |

- MediPortal never receives database credentials. Its only setting,
  `VITE_API_BASE_URL`, is **public**: it is compiled into JavaScript that every
  browser downloads.
- The password is passed to `docker` through its process environment
  (`--env DB_PASSWORD` without a value). It never appears on the command line,
  in script output or in the image.
- Like `POSTGRES_PASSWORD`, Docker stores the container's environment in its
  configuration: anyone who can use Docker on your machine can read it with
  `docker inspect medinexus-medicore`. That is acceptable for a local
  development password that you do not use anywhere else.
- `VITE_API_BASE_URL` is compiled into the JavaScript files by Vite. Nginx
  cannot change it at startup; a different URL needs a new image. It must be a
  URL the **browser** can reach; the script rejects container names such as
  `http://medinexus-medicore:8080`. The image records the URL in the label
  `medinexus.api-base-url`. To build with another URL, set the variable before
  running the script (only used when the image is built):

  ```powershell
  $env:VITE_API_BASE_URL = 'http://localhost:8080'      # Windows
  ```
  ```bash
  VITE_API_BASE_URL=http://localhost:8080 bash infrastructure/mediportal/start-mediportal.sh   # macOS
  ```

## Dockerfiles

| Image | Dockerfile | Build context | Notes |
|-------|------------|---------------|-------|
| `medinexus-medicore:local` | `infrastructure/medicore/Dockerfile` | repository root, filtered by `/.dockerignore` (only `pom.xml`, `mvnw`, `.mvn/`, `src/`) | Maven Wrapper + JDK 21 build, JRE 21 runtime, non-root user, tests not run |
| `medinexus-mediportal:local` | `infrastructure/mediportal/Dockerfile` | `MediPortal/`, filtered by `MediPortal/.dockerignore` | `npm ci` + `npm run build` on Node 24, served by unprivileged Nginx with React Router fallback |

Neither image contains credentials.

## Stopping and restarting

The commands are the same on Windows and macOS.

| Action | Command |
|--------|---------|
| Stop frontend | `docker stop medinexus-mediportal` |
| Stop backend | `docker stop medinexus-medicore` |
| Stop database | `docker stop medinexus-postgres` |
| Start again | run the start scripts again (recommended), or `docker start <container>` |
| Restart | `docker restart <container>` |
| Status | `docker ps --filter name=medinexus` |
| Logs | `docker logs -f <container>` |

Stopping or restarting containers does **not** delete any data. The database
lives in the named volume `medinexus-postgres-data`, which survives stopping,
removing and recreating containers, Docker Desktop restarts and reboots. The
MediCore and MediPortal containers hold no data of their own.

## Rebuilding images after code changes

An image is a snapshot of the code at build time, and a container keeps using
the image it was **created** from. Rebuilding an image therefore does **not**
change a running or stopped container. The start scripts warn when a container
was created from an older build than the current image. Updating is always an
explicit, manual step:

### MediCore (backend)

```bash
# 1. Rebuild the image (from the project root; same on Windows and macOS)
docker build -f infrastructure/medicore/Dockerfile -t medinexus-medicore:local .

# 2. Replace the container (no data is lost: the database is in its own container and volume)
docker stop medinexus-medicore
docker rm medinexus-medicore
```

Then run `start-medicore.ps1` / `start-medicore.sh` again. It reads the
database settings from `.env` again, or asks for them if there is no `.env`,
because the old container's settings are gone.

### MediPortal (frontend)

```bash
# 1. Rebuild the image (from the project root; same on Windows and macOS)
docker build -f infrastructure/mediportal/Dockerfile -t medinexus-mediportal:local --build-arg VITE_API_BASE_URL=http://localhost:8080 --label medinexus.api-base-url=http://localhost:8080 MediPortal

# 2. Replace the container
docker stop medinexus-mediportal
docker rm medinexus-mediportal
```

Then run `start-mediportal.ps1` / `start-mediportal.sh` again.

> Only ever remove `medinexus-medicore` and `medinexus-mediportal` this way.
> Never remove `medinexus-postgres` or the volume `medinexus-postgres-data`
> unless you really want an empty database (see
> [database/README.md](database/README.md#forgotten-password--starting-from-scratch)).

## Resolving problems and configuration conflicts

### "A container named '...' already exists but its configuration differs"

The script found a container with the expected name but, for example, a
different port, image, network or `DB_URL`. It lists each difference and
changes nothing. Either:

- keep it under another name and let the script create a new one:
  `docker rename medinexus-medicore medinexus-medicore-old`, or
- if it is a MediCore or MediPortal container you no longer need:
  `docker stop <name>` and `docker rm <name>`, then run the script again.

### "Host port 8080 / 5173 is already in use"

- **8080:** usually MediCore started locally with `mvnw spring-boot:run` or
  from IntelliJ. Stop it (both cannot use port 8080 at the same time).
- **5173:** usually the Vite development server (`npm run dev`), shown as a
  `node` process. Stop it with Ctrl+C.
- Another container: the message lists its name; stop it with
  `docker stop <name>`.

### "The Docker network 'medinexus-network' does not exist" / "PostgreSQL ... is not attached"

Shown by the MediCore or MediPortal script. Run the PostgreSQL start script
(`start-postgres.ps1` / `start-postgres.sh`); it creates the network and
attaches the database. Then run the other script again.

### "PostgreSQL rejected the username or password"

The password must be the one chosen when the database was first created. Check
`DB_PASSWORD` in `.env` and in your session (the session wins). Nothing was
created; run the script again. See
[database/README.md](database/README.md#changing-the-password) to change it.

### "DB_PASSWORD from .env is still the placeholder 'CHANGE_ME'"

`.env` was copied from `.env.example` but not edited. Replace `CHANGE_ME` with
your real database password.

### "DB_URL from ... is '...', but inside Docker MediCore must use ..."

Usually a terminal that still has `DB_URL=jdbc:postgresql://127.0.0.1:5432/...`
from running MediCore with `mvnw`. Run `Remove-Item Env:DB_URL` (Windows) or
`unset DB_URL` (macOS), or open a new terminal. Otherwise correct `DB_URL` in
`.env`.

### "Line N of ... .env is not in the form NAME=value"

Every non-empty line that does not start with `#` must look like `NAME=value`.
The message shows only the line number, never the content.

### MediCore does not report UP / the container stops

The script prints the last log lines. Common causes:

- **Wrong password stored in the container** (for example after changing the
  database password): remove and recreate the MediCore container as in
  [Rebuilding images](#medicore-backend), step 2.
- **`Found non-empty schema(s) "public" but no schema history table`**: the
  database predates Flyway and needs the one-time baseline described in
  [database/README.md](database/README.md#existing-database-one-time-baseline).

### "Image 'medinexus-mediportal:local' was built for the API URL ..."

The image was built for a different `VITE_API_BASE_URL` than requested. The URL
is fixed at build time: rebuild the image with the command printed by the
script (see [Rebuilding images](#mediportal-frontend)), then replace the
container.

### "Container '...' was created from an older build"

The image was rebuilt but the container still runs the previous version.
Replace the container as described in
[Rebuilding images](#rebuilding-images-after-code-changes).

### The home page shows "Backend: Offline"

- Check that MediCore is running: `docker ps --filter name=medinexus-medicore`.
- Open the page at `http://localhost:5173`, not `http://127.0.0.1:5173`: only
  the `localhost` origin is allowed by CORS.

### Scripts fail on Windows Git Bash with `$'\r': command not found`

The `.sh` scripts must use LF line endings. `.gitattributes` enforces this
(`*.sh text eol=lf`) for new checkouts. For a copy checked out earlier with
CRLF, run `git checkout -- infrastructure/` after committing or stashing your
changes. macOS is not affected.
