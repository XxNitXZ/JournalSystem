#!/usr/bin/env bash
#
# Starts the MediCore backend container (macOS / bash).
#
# Idempotent start script for the MediCore Spring Boot backend in Docker.
#
#   * Image missing     -> builds medinexus-medicore:local from
#                          infrastructure/medicore/Dockerfile.
#   * No container yet  -> checks port 8080, reads the database username and
#                          password, verifies them against PostgreSQL and
#                          creates the container.
#   * Container stopped -> checks port 8080 and starts it.
#   * Container running -> reuses it.
#
# Before that it checks that the Docker network 'medinexus-network' exists and
# that the PostgreSQL container is running, attached to that network and
# accepting connections. Afterwards it waits until MediCore reports status UP
# (including the database check).
#
# The script never deletes, recreates or overwrites containers, images,
# networks or volumes. Rebuilding the image does NOT update an existing
# container; see infrastructure/README.md.
#
# Windows equivalent: start-medicore.ps1 (keep the configuration identical).
# Compatible with the bash 3.2 that ships with macOS.
#
# Usage:
#   bash infrastructure/medicore/start-medicore.sh

set -euo pipefail

# ---------------------------------------------------------------------------
# Shared configuration - must stay identical to start-medicore.ps1
# ---------------------------------------------------------------------------
readonly IMAGE_NAME='medinexus-medicore:local'
readonly CONTAINER_NAME='medinexus-medicore'
readonly NETWORK_NAME='medinexus-network'
readonly POSTGRES_CONTAINER='medinexus-postgres'
readonly POSTGRES_PORT='5432'
readonly DATABASE_NAME='medinexus_db'
readonly DEFAULT_DB_USER='medinexus'
readonly DB_URL_VALUE="jdbc:postgresql://${POSTGRES_CONTAINER}:${POSTGRES_PORT}/${DATABASE_NAME}"
readonly HOST_ADDRESS='127.0.0.1'
readonly HOST_PORT='8080'
readonly CONTAINER_PORT='8080'
readonly READY_MAX_ATTEMPTS=60
readonly READY_DELAY_SECONDS=2
readonly PASSWORD_ATTEMPTS=3

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
readonly SCRIPT_DIR REPO_ROOT
readonly DOCKERFILE="${REPO_ROOT}/infrastructure/medicore/Dockerfile"
readonly HEALTH_URL="http://${HOST_ADDRESS}:${HOST_PORT}/actuator/health"

# Go templates used with "docker inspect" (identical in both scripts). The env
# templates only ever return variable NAMES or DB_URL, never DB_PASSWORD.
readonly PORTS_TEMPLATE='{{range $p, $b := .HostConfig.PortBindings}}{{range $b}}{{$p}}={{.HostIp}}:{{.HostPort}};{{end}}{{end}}'
readonly NETWORKS_TEMPLATE='{{range $k, $v := .NetworkSettings.Networks}}{{$k}};{{end}}'
readonly DB_URL_TEMPLATE='{{range .Config.Env}}{{if eq (index (split . `=`) 0) `DB_URL`}}{{.}}{{end}}{{end}}'
readonly DB_VARS_TEMPLATE='{{range .Config.Env}}{{$k := index (split . `=`) 0}}{{if or (eq $k `DB_USERNAME`) (eq $k `DB_PASSWORD`)}}{{$k}};{{end}}{{end}}'

readonly EXPECTED_PORTS="${CONTAINER_PORT}/tcp=${HOST_ADDRESS}:${HOST_PORT};"
readonly EXPECTED_DB_URL="DB_URL=${DB_URL_VALUE}"
readonly EXPECTED_DB_VARS='DB_USERNAME;DB_PASSWORD;'

# ---------------------------------------------------------------------------
# Output helpers
# ---------------------------------------------------------------------------
if [ -t 1 ]; then
  readonly C_GREEN=$'\033[32m' C_YELLOW=$'\033[33m' C_RED=$'\033[31m' C_RESET=$'\033[0m'
else
  readonly C_GREEN='' C_YELLOW='' C_RED='' C_RESET=''
fi

info()    { printf '==> %s\n' "$*"; }
success() { printf '%sOK: %s%s\n' "$C_GREEN" "$*" "$C_RESET"; }
warn()    { printf '%sWARNING: %s%s\n' "$C_YELLOW" "$*" "$C_RESET" >&2; }
fail()    { printf '%sERROR: %s%s\n' "$C_RED" "$*" "$C_RESET" >&2; exit 1; }

lower() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }

# ---------------------------------------------------------------------------
# Checks
# ---------------------------------------------------------------------------
assert_docker_available() {
  if ! command -v docker >/dev/null 2>&1; then
    fail "The Docker CLI was not found. Install Docker Desktop for Mac (https://docs.docker.com/desktop/) and open a new terminal."
  fi

  local os_type
  if ! os_type="$(docker info --format '{{.OSType}}' 2>/dev/null)"; then
    fail "Docker Desktop is not running or not reachable. Start Docker Desktop, wait until the engine is running, then run this script again. (Run 'docker info' for details.)"
  fi
  if [ "$os_type" != 'linux' ]; then
    fail "Docker reports '${os_type}' containers; Linux containers are required."
  fi
  success 'Docker Desktop is available.'
}

assert_network_exists() {
  local output
  if output="$(docker network inspect --format '{{.Name}}' "$NETWORK_NAME" 2>&1)"; then
    success "Docker network '${NETWORK_NAME}' exists."
    return 0
  fi
  case "$(lower "$output")" in
    *'not found'*|*'no such network'*)
      fail "The Docker network '${NETWORK_NAME}' does not exist.
       Run the PostgreSQL start script first; it creates the network and attaches the database:
         bash infrastructure/database/start-postgres.sh" ;;
  esac
  fail "Could not inspect network '${NETWORK_NAME}': ${output}"
}

# Prints the state of a container (running, exited, ...) or nothing if it does not exist.
get_container_state() {
  local output
  if output="$(docker container inspect --format '{{.State.Status}}' "$1" 2>&1)"; then
    printf '%s' "$output"
    return 0
  fi
  case "$(lower "$output")" in
    *'no such container'*) return 0 ;;
  esac
  fail "Could not inspect container '$1': ${output}"
}

# inspect_value CONTAINER TEMPLATE
inspect_value() {
  local output
  if ! output="$(docker container inspect --format "$2" "$1" 2>&1)"; then
    fail "Could not inspect container '$1': ${output}"
  fi
  printf '%s' "$output"
}

# PostgreSQL must be running, attached to the network and accepting connections.
# Only reads its state; never starts, stops or changes it.
assert_postgres_available() {
  local state networks
  state="$(get_container_state "$POSTGRES_CONTAINER")"
  if [ -z "$state" ]; then
    fail "The PostgreSQL container '${POSTGRES_CONTAINER}' does not exist. Start it first:
         bash infrastructure/database/start-postgres.sh"
  fi
  if [ "$state" != 'running' ]; then
    fail "The PostgreSQL container '${POSTGRES_CONTAINER}' is not running (state: ${state}). Start it first:
         bash infrastructure/database/start-postgres.sh"
  fi

  networks="$(inspect_value "$POSTGRES_CONTAINER" "$NETWORKS_TEMPLATE")"
  case ";${networks}" in
    *";${NETWORK_NAME};"*) ;;
    *) fail "The PostgreSQL container '${POSTGRES_CONTAINER}' is not attached to the network '${NETWORK_NAME}'.
       Run the PostgreSQL start script; it attaches the database without restarting it:
         bash infrastructure/database/start-postgres.sh" ;;
  esac

  if ! docker exec "$POSTGRES_CONTAINER" pg_isready \
      --host 127.0.0.1 --port "$POSTGRES_PORT" \
      --username "$DEFAULT_DB_USER" --dbname "$DATABASE_NAME" --quiet >/dev/null 2>&1; then
    fail "PostgreSQL in '${POSTGRES_CONTAINER}' is not accepting connections yet.
       Run bash infrastructure/database/start-postgres.sh (it waits until the database is ready)."
  fi
  success "PostgreSQL '${POSTGRES_CONTAINER}' is running on network '${NETWORK_NAME}' and accepting connections."
}

# Prints the image ID, or nothing if the image does not exist.
get_image_id() {
  local output
  if output="$(docker image inspect --format '{{.Id}}' "$IMAGE_NAME" 2>&1)"; then
    printf '%s' "$output"
    return 0
  fi
  case "$(lower "$output")" in
    *'no such image'*) return 0 ;;
  esac
  fail "Could not inspect image '${IMAGE_NAME}': ${output}"
}

# Builds the image only when it does not exist yet.
initialize_image() {
  if [ -n "$(get_image_id)" ]; then
    success "Image '${IMAGE_NAME}' exists (not rebuilt; see infrastructure/README.md to rebuild)."
    return 0
  fi

  info "Image '${IMAGE_NAME}' not found. Building it from infrastructure/medicore/Dockerfile (the first build takes a few minutes)..."
  local exit_code=0
  docker build --file "$DOCKERFILE" --tag "$IMAGE_NAME" "$REPO_ROOT" || exit_code=$?
  if [ "$exit_code" -ne 0 ]; then
    fail "'docker build' failed (exit code ${exit_code}). See the Docker output above."
  fi
  success "Image '${IMAGE_NAME}' built."
}

# Verifies that an existing container matches the expected configuration.
# Never modifies the container; reports conflicts and stops instead.
assert_container_configuration() {
  local image ports networks db_url db_vars conflicts=''
  image="$(inspect_value "$CONTAINER_NAME" '{{.Config.Image}}')"
  ports="$(inspect_value "$CONTAINER_NAME" "$PORTS_TEMPLATE")"
  networks="$(inspect_value "$CONTAINER_NAME" "$NETWORKS_TEMPLATE")"
  db_url="$(inspect_value "$CONTAINER_NAME" "$DB_URL_TEMPLATE")"
  db_vars="$(inspect_value "$CONTAINER_NAME" "$DB_VARS_TEMPLATE")"

  if [ "$image" != "$IMAGE_NAME" ]; then
    conflicts="${conflicts}
         - Image:    expected '${IMAGE_NAME}', found '${image}'"
  fi
  if [ "$ports" != "$EXPECTED_PORTS" ]; then
    conflicts="${conflicts}
         - Port:     expected '${EXPECTED_PORTS}', found '${ports}'"
  fi
  case ";${networks}" in
    *";${NETWORK_NAME};"*) ;;
    *) conflicts="${conflicts}
         - Network:  expected '${NETWORK_NAME}', found '${networks}'" ;;
  esac
  if [ "$db_url" != "$EXPECTED_DB_URL" ]; then
    conflicts="${conflicts}
         - DB_URL:   expected '${DB_URL_VALUE}', found '${db_url}'"
  fi
  if [ "$db_vars" != "$EXPECTED_DB_VARS" ]; then
    conflicts="${conflicts}
         - Database: DB_USERNAME and DB_PASSWORD must both be set (found: '${db_vars}')"
  fi

  if [ -n "$conflicts" ]; then
    fail "A container named '${CONTAINER_NAME}' already exists but its configuration differs:${conflicts}
       The script will not modify or delete it. Inspect it with 'docker inspect ${CONTAINER_NAME}'.
       To keep it and let this script create a new one, rename it first, for example:
         docker rename ${CONTAINER_NAME} ${CONTAINER_NAME}-old"
  fi
  success "Existing container '${CONTAINER_NAME}' matches the expected configuration."
}

# Rebuilding the image does not change an existing container: tell the user.
warn_if_outdated_image() {
  local container_image_id current_image_id
  container_image_id="$(inspect_value "$CONTAINER_NAME" '{{.Image}}')"
  current_image_id="$(get_image_id)"
  if [ -n "$current_image_id" ] && [ "$container_image_id" != "$current_image_id" ]; then
    warn "Container '${CONTAINER_NAME}' was created from an older build of '${IMAGE_NAME}'. It keeps running the old version until you recreate it:
         docker stop ${CONTAINER_NAME}
         docker rm ${CONTAINER_NAME}
         bash infrastructure/medicore/start-medicore.sh"
  fi
}

# Prints human-readable descriptions of anything using the host port.
port_conflicts() {
  local published listeners='' address

  published="$(docker ps --filter "publish=${HOST_PORT}" --format '{{.Names}}' 2>/dev/null || true)"
  if [ -n "$published" ]; then
    printf '         - Docker container(s) already publishing port %s: %s\n' "$HOST_PORT" "$(printf '%s' "$published" | tr '\n' ' ')"
  fi

  if command -v lsof >/dev/null 2>&1; then
    listeners="$(lsof -nP -iTCP:"${HOST_PORT}" -sTCP:LISTEN 2>/dev/null | tail -n +2 || true)"
    if [ -n "$listeners" ]; then
      printf '%s\n' "$listeners" | while IFS= read -r line; do
        printf '         - %s\n' "$line"
      done
    fi
  fi

  # Fallback probe (IPv4 and IPv6 loopback): lsof only lists processes owned
  # by the current user.
  if [ -z "$published" ] && [ -z "$listeners" ]; then
    for address in 127.0.0.1 ::1; do
      if (exec 3<>"/dev/tcp/${address}/${HOST_PORT}") 2>/dev/null; then
        printf '         - Something accepts connections on %s port %s\n' "$address" "$HOST_PORT"
      fi
    done
  fi
}

assert_port_free() {
  local conflicts
  conflicts="$(port_conflicts)"
  if [ -n "$conflicts" ]; then
    fail "Host port ${HOST_PORT} is already in use:
${conflicts}
       Usually this is MediCore started with 'mvnw spring-boot:run' or from IntelliJ.
       Stop it and run this script again."
  fi
  success "Host port ${HOST_PORT} is free."
}

# ---------------------------------------------------------------------------
# Credentials
# ---------------------------------------------------------------------------
CREDENTIAL_USER=''
CREDENTIAL_PASSWORD=''

# Uses DB_USERNAME / DB_PASSWORD from this shell if exported, otherwise asks.
# Stores the result in CREDENTIAL_* (no subshell, so nothing leaks via stdout).
read_database_credentials() {
  if [ -n "${DB_USERNAME:-}" ]; then
    CREDENTIAL_USER="$DB_USERNAME"
    info "Using DB_USERNAME '${CREDENTIAL_USER}' from this shell."
  elif [ ! -t 0 ]; then
    CREDENTIAL_USER="$DEFAULT_DB_USER"
    info "Using the default database username '${CREDENTIAL_USER}'."
  else
    printf 'Database username [%s]: ' "$DEFAULT_DB_USER" >&2
    IFS= read -r CREDENTIAL_USER || fail 'Could not read the username.'
    CREDENTIAL_USER="$(printf '%s' "$CREDENTIAL_USER" | tr -d '[:space:]')"
    if [ -z "$CREDENTIAL_USER" ]; then
      CREDENTIAL_USER="$DEFAULT_DB_USER"
    fi
  fi

  if [ -n "${DB_PASSWORD:-}" ]; then
    CREDENTIAL_PASSWORD="$DB_PASSWORD"
    info 'Using DB_PASSWORD from this shell.'
    return 0
  fi

  if [ ! -t 0 ]; then
    fail 'The database password is required. Run this script in an interactive terminal.'
  fi
  local attempt=1 password
  while [ "$attempt" -le "$PASSWORD_ATTEMPTS" ]; do
    attempt=$((attempt + 1))
    printf "Password for database user '%s': " "$CREDENTIAL_USER" >&2
    IFS= read -r -s password || fail 'Could not read the password.'
    printf '\n' >&2
    if [ -n "${password//[[:space:]]/}" ]; then
      CREDENTIAL_PASSWORD="$password"
      return 0
    fi
    warn 'The password must not be empty.'
  done
  fail "No password entered after ${PASSWORD_ATTEMPTS} attempts. Nothing was created."
}

# Logs in to PostgreSQL once (SELECT 1) so a wrong password is reported now,
# before a container is created with it. The password reaches psql through
# the docker CLI's environment ("--env PGPASSWORD" without a value).
assert_database_credentials() {
  info "Checking the credentials for database user '${CREDENTIAL_USER}'..."
  local output exit_code=0
  output="$(PGPASSWORD="$CREDENTIAL_PASSWORD" docker exec --env PGPASSWORD "$POSTGRES_CONTAINER" psql \
      --host 127.0.0.1 --port "$POSTGRES_PORT" \
      --username "$CREDENTIAL_USER" --dbname "$DATABASE_NAME" \
      --no-password --tuples-only --no-align --command 'SELECT 1' 2>&1)" || exit_code=$?

  if [ "$exit_code" -ne 0 ] || [ "$output" != '1' ]; then
    case "$(lower "$output")" in
      *'password authentication failed'*|*'role '*' does not exist'*)
        fail "PostgreSQL rejected the username or password for '${CREDENTIAL_USER}'. Nothing was created.
       Use the password chosen when the database was first created (see infrastructure/database/README.md)." ;;
    esac
    fail "Could not log in to PostgreSQL: ${output}"
  fi
  success 'Database credentials accepted.'
}

# ---------------------------------------------------------------------------
# Docker actions (build / create / start only - never delete)
# ---------------------------------------------------------------------------
create_medicore_container() {
  info "Creating container '${CONTAINER_NAME}' from '${IMAGE_NAME}'..."

  # DB_USERNAME and DB_PASSWORD are handed to docker through its process
  # environment ("--env NAME" without a value), so the password never appears
  # in the command line. The prefix assignment applies to this one command only.
  local output exit_code=0
  output="$(DB_USERNAME="$CREDENTIAL_USER" DB_PASSWORD="$CREDENTIAL_PASSWORD" docker run --detach \
      --name "$CONTAINER_NAME" \
      --network "$NETWORK_NAME" \
      --publish "${HOST_ADDRESS}:${HOST_PORT}:${CONTAINER_PORT}" \
      --env "DB_URL=${DB_URL_VALUE}" \
      --env DB_USERNAME \
      --env DB_PASSWORD \
      "$IMAGE_NAME" 2>&1)" || exit_code=$?

  CREDENTIAL_PASSWORD=''

  if [ "$exit_code" -ne 0 ]; then
    fail "'docker run' failed (exit code ${exit_code}): ${output}
       If a container was created anyway, run this script again to start it."
  fi
  success "Container '${CONTAINER_NAME}' created."
}

start_existing_container() {
  info "Starting stopped container '${CONTAINER_NAME}'..."
  local output
  if ! output="$(docker start "$CONTAINER_NAME" 2>&1)"; then
    fail "Could not start container '${CONTAINER_NAME}': ${output}"
  fi
  success "Container '${CONTAINER_NAME}' started."
}

fail_with_container_logs() {
  printf '%s\n' '----- last lines of the MediCore log -----' >&2
  docker logs --tail 25 "$CONTAINER_NAME" >&2 2>&1 || true
  printf '%s\n' '------------------------------------------' >&2
  fail "$1
       Common causes: wrong DB_PASSWORD stored in the container, or a database that needs the one-time Flyway baseline.
       Full log: docker logs ${CONTAINER_NAME}"
}

# Waits until /actuator/health reports UP (application and database).
wait_medicore_ready() {
  local max_seconds=$((READY_MAX_ATTEMPTS * READY_DELAY_SECONDS))
  local attempt=1 state body
  info "Waiting for MediCore to report status UP at ${HEALTH_URL} (up to ${max_seconds} seconds)..."

  while [ "$attempt" -le "$READY_MAX_ATTEMPTS" ]; do
    state="$(get_container_state "$CONTAINER_NAME")"
    if [ "$state" != 'running' ]; then
      fail_with_container_logs "Container '${CONTAINER_NAME}' is no longer running (state: '${state}')."
    fi

    # Not started yet, or health is DOWN (HTTP 503): keep waiting.
    if body="$(curl -fsS -m 3 "$HEALTH_URL" 2>/dev/null)"; then
      case "$body" in
        *'"status":"UP"'*)
          success "MediCore is UP (attempt ${attempt} of ${READY_MAX_ATTEMPTS})."
          return 0 ;;
      esac
    fi
    sleep "$READY_DELAY_SECONDS"
    attempt=$((attempt + 1))
  done

  fail_with_container_logs "MediCore did not report status UP within ${max_seconds} seconds."
}

show_connection_info() {
  printf '\n%sMediNexus MediCore is running.%s\n' "$C_GREEN" "$C_RESET"
  printf '  API:        http://localhost:%s/api/health\n' "$HOST_PORT"
  printf '  Health:     http://localhost:%s/actuator/health\n' "$HOST_PORT"
  printf '  Container:  %s (image %s, network %s)\n' "$CONTAINER_NAME" "$IMAGE_NAME" "$NETWORK_NAME"
  printf '  Database:   %s\n' "$DB_URL_VALUE"
  printf '\n'
  printf '  Logs:       docker logs -f %s\n' "$CONTAINER_NAME"
  printf '  Stop:       docker stop %s\n' "$CONTAINER_NAME"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
  info "MediNexus MediCore (${IMAGE_NAME}), container '${CONTAINER_NAME}'"
  assert_docker_available
  assert_network_exists
  assert_postgres_available
  initialize_image

  local container_state
  container_state="$(get_container_state "$CONTAINER_NAME")"

  if [ -n "$container_state" ]; then
    info "Container '${CONTAINER_NAME}' already exists (state: ${container_state})."
    assert_container_configuration
    warn_if_outdated_image

    case "$container_state" in
      running)
        success "Container '${CONTAINER_NAME}' is already running."
        ;;
      exited|created)
        assert_port_free
        start_existing_container
        ;;
      paused)
        fail "Container '${CONTAINER_NAME}' is paused. Resume it with: docker unpause ${CONTAINER_NAME}"
        ;;
      *)
        fail "Container '${CONTAINER_NAME}' is in state '${container_state}' and cannot be started safely.
       Check the logs with: docker logs ${CONTAINER_NAME}"
        ;;
    esac
  else
    info "Container '${CONTAINER_NAME}' does not exist yet."
    assert_port_free
    read_database_credentials
    assert_database_credentials
    create_medicore_container
  fi

  wait_medicore_ready
  show_connection_info
}

main "$@"
