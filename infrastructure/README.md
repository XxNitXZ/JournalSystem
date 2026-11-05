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
- on first creation, asks for the database username (default `medinexus`) and
  password and **tests the login** (`SELECT 1`) before creating the container,
  so a typo does not leave a broken container behind,
- waits until `http://127.0.0.1:8080/actuator/health` reports `UP` (application
  and database), and shows the last log lines if startup fails.

MediPortal's script additionally waits until Nginx serves the page and warns if
MediCore is not running.

## Environment variables

| Variable | Used by | When | Value |
|----------|---------|------|-------|
| `DB_URL` | MediCore container | set by the script on creation | `jdbc:postgresql://medinexus-postgres:5432/medinexus_db` |
| `DB_USERNAME` | MediCore container | on creation | prompted (default `medinexus`), or taken from the current session |
| `DB_PASSWORD` | MediCore container | on creation | prompted with hidden input, or taken from the current session |
| `VITE_API_BASE_URL` | MediPortal image | **at image build time** | default `http://localhost:8080` |
| `MEDINEXUS_CORS_ALLOWED_ORIGINS` | MediCore | optional | not set: MediCore allows `http://localhost:5173` |

- Any `DB_URL` in your session (for example `127.0.0.1:5432` for
  `mvnw spring-boot:run`) is **ignored** by the script. Inside a container,
  `127.0.0.1` is the container itself, so the script always uses the
  container name.
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

Then run `start-medicore.ps1` / `start-medicore.sh` again. It asks for the
database password again, because the old container's settings are gone.

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

The password must be the one chosen when the database was first created. Nothing
was created; run the script again. See
[database/README.md](database/README.md#changing-the-password) to change it.

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
