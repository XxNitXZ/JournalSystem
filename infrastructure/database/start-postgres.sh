#!/usr/bin/env bash
#
# Starts the local MediNexus PostgreSQL container (macOS / bash).
#
# Idempotent start script for the MediNexus development database.
#
#   * No container yet  -> checks port 5432, asks for a password, creates the
#                          volume (if missing) and the container.
#   * Container stopped -> checks port 5432 and starts it.
#   * Container running -> reuses it.
#
# In every case the script verifies that an existing container matches the
# expected image, volume mount and port binding, and waits for PostgreSQL to
# accept connections (pg_isready) with a bounded number of retries.
#
# The script never deletes, recreates or overwrites containers or volumes.
#
# Windows equivalent: start-postgres.ps1 (keep the configuration identical).
# Compatible with the bash 3.2 that ships with macOS.
#
# Usage:
#   bash infrastructure/database/start-postgres.sh

set -euo pipefail

# ---------------------------------------------------------------------------
# Shared configuration - must stay identical to start-postgres.ps1
# ---------------------------------------------------------------------------
readonly POSTGRES_IMAGE='postgres:18'
readonly CONTAINER_NAME='medinexus-postgres'
readonly VOLUME_NAME='medinexus-postgres-data'
readonly DATABASE_NAME='medinexus_db'
readonly DATABASE_USER='medinexus'
readonly HOST_ADDRESS='127.0.0.1'
readonly HOST_PORT='5432'
readonly CONTAINER_PORT='5432'
readonly DATA_MOUNT_PATH='/var/lib/postgresql'
readonly INIT_DB_ARGS='--auth-local=scram-sha-256 --auth-host=scram-sha-256'
readonly READY_MAX_ATTEMPTS=30
readonly READY_DELAY_SECONDS=2
readonly PASSWORD_ATTEMPTS=3

# Go templates used with "docker inspect" (identical in both scripts).
readonly MOUNTS_TEMPLATE='{{range .Mounts}}{{.Type}}|{{.Name}}|{{.Destination}};{{end}}'
readonly PORTS_TEMPLATE='{{range $p, $b := .HostConfig.PortBindings}}{{range $b}}{{$p}}={{.HostIp}}:{{.HostPort}};{{end}}{{end}}'

readonly EXPECTED_MOUNT="volume|${VOLUME_NAME}|${DATA_MOUNT_PATH}"
readonly EXPECTED_PORTS="${CONTAINER_PORT}/tcp=${HOST_ADDRESS}:${HOST_PORT};"

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

# Prints the container state (running, exited, ...) or nothing if it does not exist.
get_container_state() {
  local output
  if output="$(docker container inspect --format '{{.State.Status}}' "$CONTAINER_NAME" 2>&1)"; then
    printf '%s' "$output"
    return 0
  fi
  case "$(printf '%s' "$output" | tr '[:upper:]' '[:lower:]')" in
    *'no such container'*) return 0 ;;
  esac
  fail "Could not inspect container '${CONTAINER_NAME}': ${output}"
}

volume_exists() {
  local output
  if output="$(docker volume inspect --format '{{.Name}}' "$VOLUME_NAME" 2>&1)"; then
    return 0
  fi
  case "$(printf '%s' "$output" | tr '[:upper:]' '[:lower:]')" in
    *'no such volume'*) return 1 ;;
  esac
  fail "Could not inspect volume '${VOLUME_NAME}': ${output}"
}

inspect_value() {
  local output
  if ! output="$(docker container inspect --format "$1" "$CONTAINER_NAME" 2>&1)"; then
    fail "Could not inspect container '${CONTAINER_NAME}': ${output}"
  fi
  printf '%s' "$output"
}

# Verifies that an existing container uses the expected image, volume and port.
# Never modifies the container; reports conflicts and stops instead.
assert_container_configuration() {
  local image mounts ports conflicts=''
  image="$(inspect_value '{{.Config.Image}}')"
  mounts="$(inspect_value "$MOUNTS_TEMPLATE")"
  ports="$(inspect_value "$PORTS_TEMPLATE")"

  if [ "$image" != "$POSTGRES_IMAGE" ] && [ "$image" != "docker.io/library/${POSTGRES_IMAGE}" ]; then
    conflicts="${conflicts}
         - Image:  expected '${POSTGRES_IMAGE}', found '${image}'"
  fi
  case ";${mounts}" in
    *";${EXPECTED_MOUNT};"*) ;;
    *) conflicts="${conflicts}
         - Volume: expected volume '${VOLUME_NAME}' at '${DATA_MOUNT_PATH}', found '${mounts}'" ;;
  esac
  if [ "$ports" != "$EXPECTED_PORTS" ]; then
    conflicts="${conflicts}
         - Port:   expected '${EXPECTED_PORTS}', found '${ports}'"
  fi

  if [ -n "$conflicts" ]; then
    fail "A container named '${CONTAINER_NAME}' already exists but its configuration differs:${conflicts}
       The script will not modify or delete it. Inspect it with 'docker inspect ${CONTAINER_NAME}'.
       To keep it and let this script create a new one, rename it first, for example:
         docker rename ${CONTAINER_NAME} ${CONTAINER_NAME}-old"
  fi
  success "Existing container '${CONTAINER_NAME}' matches the expected configuration."
}

# Prints human-readable descriptions of anything using the host port.
port_conflicts() {
  local published listeners

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

  # Fallback probe: lsof only lists processes owned by the current user.
  if [ -z "$published" ] && [ -z "${listeners:-}" ]; then
    if (exec 3<>"/dev/tcp/${HOST_ADDRESS}/${HOST_PORT}") 2>/dev/null; then
      printf '         - Something accepts connections on %s:%s\n' "$HOST_ADDRESS" "$HOST_PORT"
    fi
  fi
}

assert_port_free() {
  local conflicts
  conflicts="$(port_conflicts)"
  if [ -n "$conflicts" ]; then
    fail "Host port ${HOST_PORT} is already in use:
${conflicts}
       Stop the other PostgreSQL server or container and run this script again.
       See infrastructure/database/README.md -> Troubleshooting."
  fi
  success "Host port ${HOST_PORT} is free."
}

assert_volume_not_in_use() {
  local users
  if ! users="$(docker ps --all --filter "volume=${VOLUME_NAME}" --format '{{.Names}}' 2>&1)"; then
    fail "Could not check which containers use volume '${VOLUME_NAME}': ${users}"
  fi
  if [ -n "$users" ]; then
    fail "Volume '${VOLUME_NAME}' is already used by other container(s): $(printf '%s' "$users" | tr '\n' ' ')
       Two PostgreSQL servers must never share one data directory. Resolve this manually."
  fi
}

# ---------------------------------------------------------------------------
# Password input
# ---------------------------------------------------------------------------
DB_PASSWORD=''

# Prompts (hidden input) for a new password; rejects empty or mismatching input.
# Stores the result in DB_PASSWORD (no subshell, so nothing leaks via stdout).
read_new_database_password() {
  if [ ! -t 0 ]; then
    fail 'A password is required on first initialization. Run this script in an interactive terminal.'
  fi

  local attempt=1 password confirmation
  while [ "$attempt" -le "$PASSWORD_ATTEMPTS" ]; do
    attempt=$((attempt + 1))

    printf "Enter a new password for database user '%s': " "$DATABASE_USER" >&2
    IFS= read -r -s password || fail 'Could not read the password.'
    printf '\n' >&2
    if [ -z "${password//[[:space:]]/}" ]; then
      warn 'The password must not be empty.'
      continue
    fi

    printf 'Confirm the password: ' >&2
    IFS= read -r -s confirmation || fail 'Could not read the password.'
    printf '\n' >&2
    if [ "$password" != "$confirmation" ]; then
      warn 'The passwords do not match.'
      continue
    fi

    DB_PASSWORD="$password"
    return 0
  done
  fail "No valid password entered after ${PASSWORD_ATTEMPTS} attempts. Nothing was created."
}

# ---------------------------------------------------------------------------
# Docker actions (create / start only - never delete)
# ---------------------------------------------------------------------------
create_data_volume() {
  info "Creating Docker volume '${VOLUME_NAME}'..."
  local output
  if ! output="$(docker volume create "$VOLUME_NAME" 2>&1)"; then
    fail "Could not create volume '${VOLUME_NAME}': ${output}"
  fi
  success "Volume '${VOLUME_NAME}' created."
}

create_postgres_container() {
  info "Creating container '${CONTAINER_NAME}' from '${POSTGRES_IMAGE}' (the image is downloaded on first use)..."

  # The password is handed to docker through its process environment
  # ("--env POSTGRES_PASSWORD" without a value), so it never appears in the
  # command line. The prefix assignment applies to this one command only.
  local exit_code=0
  POSTGRES_PASSWORD="$DB_PASSWORD" docker run \
    --detach \
    --name "$CONTAINER_NAME" \
    --publish "${HOST_ADDRESS}:${HOST_PORT}:${CONTAINER_PORT}" \
    --volume "${VOLUME_NAME}:${DATA_MOUNT_PATH}" \
    --env "POSTGRES_DB=${DATABASE_NAME}" \
    --env "POSTGRES_USER=${DATABASE_USER}" \
    --env POSTGRES_PASSWORD \
    --env "POSTGRES_INITDB_ARGS=${INIT_DB_ARGS}" \
    "$POSTGRES_IMAGE" >/dev/null || exit_code=$?

  DB_PASSWORD=''

  if [ "$exit_code" -ne 0 ]; then
    fail "'docker run' failed (exit code ${exit_code}). See the Docker output above. If a container was created anyway, run this script again to start it."
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

wait_postgres_ready() {
  local max_seconds=$((READY_MAX_ATTEMPTS * READY_DELAY_SECONDS))
  local attempt=1 state
  info "Waiting for PostgreSQL to accept connections (up to ${max_seconds} seconds)..."

  while [ "$attempt" -le "$READY_MAX_ATTEMPTS" ]; do
    state="$(get_container_state)"
    if [ "$state" != 'running' ]; then
      fail "Container '${CONTAINER_NAME}' is no longer running (state: '${state}').
       Check the logs with: docker logs ${CONTAINER_NAME}"
    fi

    # 127.0.0.1 here is the loopback interface inside the container. Using
    # TCP avoids a false positive while the image runs its first-time
    # initialization on a socket-only temporary server.
    if docker exec "$CONTAINER_NAME" pg_isready \
        --host 127.0.0.1 --port "$CONTAINER_PORT" \
        --username "$DATABASE_USER" --dbname "$DATABASE_NAME" --quiet >/dev/null 2>&1; then
      success "PostgreSQL is ready (attempt ${attempt} of ${READY_MAX_ATTEMPTS})."
      return 0
    fi
    sleep "$READY_DELAY_SECONDS"
    attempt=$((attempt + 1))
  done

  fail "PostgreSQL did not become ready within ${max_seconds} seconds.
       Check the logs with: docker logs ${CONTAINER_NAME}"
}

show_connection_info() {
  printf '\n%sMediNexus PostgreSQL is running.%s\n' "$C_GREEN" "$C_RESET"
  printf '  Host:      %s\n' "$HOST_ADDRESS"
  printf '  Port:      %s\n' "$HOST_PORT"
  printf '  Database:  %s\n' "$DATABASE_NAME"
  printf '  Username:  %s\n' "$DATABASE_USER"
  printf '  JDBC URL:  jdbc:postgresql://%s:%s/%s\n' "$HOST_ADDRESS" "$HOST_PORT" "$DATABASE_NAME"
  printf '  Volume:    %s\n' "$VOLUME_NAME"
  printf '\n'
  printf '  Stop:      docker stop %s\n' "$CONTAINER_NAME"
  printf '  psql:      docker exec -it %s psql -U %s -d %s\n' "$CONTAINER_NAME" "$DATABASE_USER" "$DATABASE_NAME"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
  info "MediNexus PostgreSQL (${POSTGRES_IMAGE}), container '${CONTAINER_NAME}'"
  assert_docker_available

  local container_state
  container_state="$(get_container_state)"

  if [ -n "$container_state" ]; then
    info "Container '${CONTAINER_NAME}' already exists (state: ${container_state})."
    assert_container_configuration

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
    info "Container '${CONTAINER_NAME}' does not exist yet. Preparing first initialization."
    assert_port_free

    local volume_existed=false
    if volume_exists; then
      volume_existed=true
      assert_volume_not_in_use
      warn "Volume '${VOLUME_NAME}' already exists and will be reused; its data is preserved. If it already contains a database, that database keeps its original password and the password entered below is ignored."
    fi

    read_new_database_password

    if [ "$volume_existed" = false ]; then
      create_data_volume
    else
      success "Reusing existing volume '${VOLUME_NAME}'."
    fi

    create_postgres_container
  fi

  wait_postgres_ready
  show_connection_info
}

main "$@"
