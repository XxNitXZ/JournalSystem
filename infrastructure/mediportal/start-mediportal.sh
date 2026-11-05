#!/usr/bin/env bash
#
# Starts the MediPortal frontend container (macOS / bash).
#
# Idempotent start script for the MediPortal frontend (Nginx) in Docker.
#
#   * Image missing     -> builds medinexus-mediportal:local from
#                          infrastructure/mediportal/Dockerfile with
#                          MediPortal/ as the build context.
#   * No container yet  -> checks port 5173 and creates the container.
#   * Container stopped -> checks port 5173 and starts it.
#   * Container running -> reuses it.
#
# The MediCore API URL used by the BROWSER is embedded into the JavaScript
# bundle when the image is built (VITE_API_BASE_URL, default
# http://localhost:8080). It is recorded as the image label
# 'medinexus.api-base-url' so the script can detect a mismatch.
#
# The script never deletes, recreates or overwrites containers, images,
# networks or volumes. Rebuilding the image does NOT update an existing
# container; see infrastructure/README.md.
#
# Windows equivalent: start-mediportal.ps1 (keep the configuration identical).
# Compatible with the bash 3.2 that ships with macOS.
#
# Usage:
#   bash infrastructure/mediportal/start-mediportal.sh
#   # different browser-facing API URL (only used when the image is built):
#   VITE_API_BASE_URL=http://localhost:8080 bash infrastructure/mediportal/start-mediportal.sh

set -euo pipefail

# ---------------------------------------------------------------------------
# Shared configuration - must stay identical to start-mediportal.ps1
# ---------------------------------------------------------------------------
readonly IMAGE_NAME='medinexus-mediportal:local'
readonly CONTAINER_NAME='medinexus-mediportal'
readonly NETWORK_NAME='medinexus-network'
readonly BACKEND_CONTAINER='medinexus-medicore'
readonly HOST_ADDRESS='127.0.0.1'
readonly HOST_PORT='5173'
readonly CONTAINER_PORT='8080'
readonly DEFAULT_API_BASE_URL='http://localhost:8080'
readonly API_URL_LABEL='medinexus.api-base-url'
readonly READY_MAX_ATTEMPTS=15
readonly READY_DELAY_SECONDS=1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
readonly SCRIPT_DIR REPO_ROOT
readonly DOCKERFILE="${REPO_ROOT}/infrastructure/mediportal/Dockerfile"
readonly BUILD_CONTEXT="${REPO_ROOT}/MediPortal"
readonly FRONTEND_URL="http://${HOST_ADDRESS}:${HOST_PORT}/"

# Go templates used with "docker inspect" (identical in both scripts).
readonly PORTS_TEMPLATE='{{range $p, $b := .HostConfig.PortBindings}}{{range $b}}{{$p}}={{.HostIp}}:{{.HostPort}};{{end}}{{end}}'
readonly NETWORKS_TEMPLATE='{{range $k, $v := .NetworkSettings.Networks}}{{$k}};{{end}}'
readonly LABEL_TEMPLATE="{{if .Config.Labels}}{{index .Config.Labels \`${API_URL_LABEL}\`}}{{end}}"

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
       Run the PostgreSQL start script first; it creates the network:
         bash infrastructure/database/start-postgres.sh" ;;
  esac
  fail "Could not inspect network '${NETWORK_NAME}': ${output}"
}

# The API URL is used by the user's browser, so it must be reachable from the
# host - not a Docker container name such as http://medinexus-medicore:8080.
# Stores the result in API_BASE_URL.
API_BASE_URL=''
resolve_api_base_url() {
  local url="${VITE_API_BASE_URL:-}" rest host
  if [ -z "${url//[[:space:]]/}" ]; then
    url="$DEFAULT_API_BASE_URL"
  else
    info "Using VITE_API_BASE_URL '${url}' from this shell."
  fi
  url="$(printf '%s' "$url" | tr -d '[:space:]')"
  while [ "${url%/}" != "$url" ]; do url="${url%/}"; done

  case "$url" in
    http://*) rest="${url#http://}" ;;
    https://*) rest="${url#https://}" ;;
    *) fail "VITE_API_BASE_URL '${url}' is not a valid http(s) URL, for example ${DEFAULT_API_BASE_URL}" ;;
  esac
  host="${rest%%/*}"
  case "$host" in
    \[*\]*) host="${host%%]*}]" ;;
    *) host="${host%%:*}" ;;
  esac
  case "$host" in
    '') fail "VITE_API_BASE_URL '${url}' is not a valid http(s) URL, for example ${DEFAULT_API_BASE_URL}" ;;
    localhost|*.*|\[*\]) ;;
    *) fail "VITE_API_BASE_URL '${url}' uses the host '${host}', which looks like a Docker container name.
       The browser calls this URL, and browsers cannot resolve container names.
       Use an address reachable from the browser, for example ${DEFAULT_API_BASE_URL}" ;;
  esac
  API_BASE_URL="$url"
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

rebuild_command() {
  printf 'docker build --file infrastructure/mediportal/Dockerfile --tag %s --build-arg VITE_API_BASE_URL=%s --label %s=%s MediPortal' \
    "$IMAGE_NAME" "$1" "$API_URL_LABEL" "$1"
}

# Builds the image only when it does not exist yet; otherwise checks that it
# was built for the requested API URL.
initialize_image() {
  if [ -z "$(get_image_id)" ]; then
    info "Image '${IMAGE_NAME}' not found. Building it with VITE_API_BASE_URL=${API_BASE_URL}..."
    local exit_code=0
    docker build \
      --file "$DOCKERFILE" \
      --tag "$IMAGE_NAME" \
      --build-arg "VITE_API_BASE_URL=${API_BASE_URL}" \
      --label "${API_URL_LABEL}=${API_BASE_URL}" \
      "$BUILD_CONTEXT" || exit_code=$?
    if [ "$exit_code" -ne 0 ]; then
      fail "'docker build' failed (exit code ${exit_code}). See the Docker output above."
    fi
    success "Image '${IMAGE_NAME}' built."
    return 0
  fi

  local built_for
  if ! built_for="$(docker image inspect --format "$LABEL_TEMPLATE" "$IMAGE_NAME" 2>&1)"; then
    fail "Could not inspect image '${IMAGE_NAME}': ${built_for}"
  fi
  if [ -z "$built_for" ]; then
    warn "Image '${IMAGE_NAME}' has no '${API_URL_LABEL}' label, so its API URL cannot be checked. If unsure, rebuild it from the project root:
         $(rebuild_command "$API_BASE_URL")"
  elif [ "$built_for" != "$API_BASE_URL" ]; then
    fail "Image '${IMAGE_NAME}' was built for the API URL '${built_for}', but '${API_BASE_URL}' was requested.
       The URL is embedded at build time. Rebuild the image from the project root:
         $(rebuild_command "$API_BASE_URL")"
  else
    success "Image '${IMAGE_NAME}' exists and was built for the API URL '${built_for}'."
  fi
}

# Verifies that an existing container matches the expected configuration.
# Never modifies the container; reports conflicts and stops instead.
assert_container_configuration() {
  local image ports networks conflicts=''
  image="$(inspect_value "$CONTAINER_NAME" '{{.Config.Image}}')"
  ports="$(inspect_value "$CONTAINER_NAME" "$PORTS_TEMPLATE")"
  networks="$(inspect_value "$CONTAINER_NAME" "$NETWORKS_TEMPLATE")"

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
    warn "Container '${CONTAINER_NAME}' was created from an older build of '${IMAGE_NAME}'. It keeps serving the old version until you recreate it:
         docker stop ${CONTAINER_NAME}
         docker rm ${CONTAINER_NAME}
         bash infrastructure/mediportal/start-mediportal.sh"
  fi
}

# Prints human-readable descriptions of anything using the host port.
# Checks all addresses: the Vite dev server listens on ::1 (IPv6 localhost).
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
        case "$line" in
          node*) printf '         - %s  (probably the Vite development server: npm run dev)\n' "$line" ;;
          *) printf '         - %s\n' "$line" ;;
        esac
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
       If the Vite development server is running (npm run dev), stop it with Ctrl+C.
       Then run this script again."
  fi
  success "Host port ${HOST_PORT} is free."
}

# ---------------------------------------------------------------------------
# Docker actions (build / create / start only - never delete)
# ---------------------------------------------------------------------------
create_mediportal_container() {
  info "Creating container '${CONTAINER_NAME}' from '${IMAGE_NAME}'..."
  local output exit_code=0
  output="$(docker run --detach \
      --name "$CONTAINER_NAME" \
      --network "$NETWORK_NAME" \
      --publish "${HOST_ADDRESS}:${HOST_PORT}:${CONTAINER_PORT}" \
      "$IMAGE_NAME" 2>&1)" || exit_code=$?
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

wait_mediportal_ready() {
  local attempt=1 state body
  info "Waiting for MediPortal at ${FRONTEND_URL}..."
  while [ "$attempt" -le "$READY_MAX_ATTEMPTS" ]; do
    state="$(get_container_state "$CONTAINER_NAME")"
    if [ "$state" != 'running' ]; then
      fail "Container '${CONTAINER_NAME}' is no longer running (state: '${state}').
       Check the logs with: docker logs ${CONTAINER_NAME}"
    fi
    if body="$(curl -fsS -m 3 "$FRONTEND_URL" 2>/dev/null)"; then
      case "$body" in
        *'id="root"'*)
          success "MediPortal is serving the frontend (attempt ${attempt} of ${READY_MAX_ATTEMPTS})."
          return 0 ;;
      esac
    fi
    sleep "$READY_DELAY_SECONDS"
    attempt=$((attempt + 1))
  done
  fail "MediPortal did not respond at ${FRONTEND_URL}.
       Check the logs with: docker logs ${CONTAINER_NAME}"
}

show_connection_info() {
  printf '\n%sMediNexus MediPortal is running.%s\n' "$C_GREEN" "$C_RESET"
  printf "  Open:       http://localhost:%s   (use 'localhost': it is the origin MediCore's CORS allows)\n" "$HOST_PORT"
  printf '  API URL:    %s   (embedded in the image, used by the browser)\n' "$API_BASE_URL"
  printf '  Container:  %s (image %s, network %s)\n' "$CONTAINER_NAME" "$IMAGE_NAME" "$NETWORK_NAME"
  printf '\n'
  printf '  Logs:       docker logs -f %s\n' "$CONTAINER_NAME"
  printf '  Stop:       docker stop %s\n' "$CONTAINER_NAME"

  if [ "$(get_container_state "$BACKEND_CONTAINER")" != 'running' ]; then
    printf '\n'
    warn "MediCore ('${BACKEND_CONTAINER}') is not running, so the backend status shows Offline. Start it with: bash infrastructure/medicore/start-medicore.sh"
  fi
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
  info "MediNexus MediPortal (${IMAGE_NAME}), container '${CONTAINER_NAME}'"
  assert_docker_available
  assert_network_exists
  resolve_api_base_url
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
    create_mediportal_container
  fi

  wait_mediportal_ready
  show_connection_info
}

main "$@"
