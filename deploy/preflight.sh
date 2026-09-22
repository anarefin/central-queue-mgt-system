#!/usr/bin/env bash
# FR-OPS-001, FR-OPS-002 (SRS §26.1): verifies prerequisites before an install or upgrade proceeds, and refuses with
# a clear message rather than half-installing. Every check runs; every failure is reported together, so a consultant
# fixes the site once instead of one error at a time. Exit code 0 means "safe to proceed", non-zero means "do not".
#
# Usage: deploy/preflight.sh [--skip-db]
#   --skip-db   Skip the database-reachability check (first install, before QMS_DB_* is set).
#
# Reads the same QMS_DB_* environment variables deploy/compose.yaml and deploy/install.sh use.
set -u

SKIP_DB=0
for arg in "$@"; do
  case "$arg" in
    --skip-db) SKIP_DB=1 ;;
    *)
      echo "preflight: unknown argument '$arg'" >&2
      exit 2
      ;;
  esac
done

FAILURES=()
fail() { FAILURES+=("$1"); }
ok() { echo "  OK   $1"; }

echo "QMS installer preflight (FR-OPS-001, FR-OPS-002)"
echo "=================================================="

# ---- OS (NFR-POR-001: Linux x86-64 with Docker/Podman, or Windows Server via deploy/windows/install.ps1) ---------
echo "OS and architecture:"
OS_NAME="$(uname -s 2>/dev/null || echo unknown)"
ARCH="$(uname -m 2>/dev/null || echo unknown)"
case "$OS_NAME" in
  Linux)
    if [ "$ARCH" = "x86_64" ]; then
      ok "Linux x86-64 ($ARCH)"
    else
      fail "Linux architecture is '$ARCH', not x86-64. NFR-POR-001 supports Linux x86-64 only; use deploy/windows/install.ps1 on Windows Server, or contact support for other architectures."
    fi
    ;;
  Darwin)
    # Not a supported production target (NFR-POR-001), but common for a consultant's own dry run before a site visit.
    echo "  WARN macOS detected: not a supported production target (NFR-POR-001 names Linux x86-64 and Windows Server). Proceeding for a local dry run only."
    ;;
  *)
    fail "Unrecognised OS '$OS_NAME'. NFR-POR-001 supports Linux x86-64 (this script) and Windows Server (deploy/windows/install.ps1)."
    ;;
esac

# ---- Container runtime (NFR-POR-001, NFR-POR-004: Docker or Podman, no downloads at install) ---------------------
echo "Container runtime:"
if command -v docker >/dev/null 2>&1; then
  if docker info >/dev/null 2>&1; then
    ok "Docker is installed and the daemon is reachable ($(docker --version))"
  else
    fail "Docker is installed but its daemon is not reachable. Start Docker (or Podman) before installing."
  fi
elif command -v podman >/dev/null 2>&1; then
  if podman info >/dev/null 2>&1; then
    ok "Podman is installed and reachable ($(podman --version))"
  else
    fail "Podman is installed but not reachable. Start the Podman service before installing."
  fi
else
  fail "Neither Docker nor Podman is installed. NFR-POR-001 requires one of them on Linux x86-64."
fi

if command -v docker >/dev/null 2>&1 && ! command -v docker-compose >/dev/null 2>&1 && ! docker compose version >/dev/null 2>&1; then
  fail "Docker Compose (the 'docker compose' plugin) is not available; deploy/compose.yaml needs it."
fi

# ---- Database reachability (FR-OPS-001) ---------------------------------------------------------------------------
echo "Database reachability:"
if [ "$SKIP_DB" = "1" ]; then
  echo "  SKIP  --skip-db given (first install: PostgreSQL starts as part of this install)."
elif [ -n "${QMS_DB_URL:-}" ]; then
  # Extract host:port from a jdbc:postgresql://host:port/db URL without needing a JDBC driver.
  HOSTPORT="$(printf '%s' "$QMS_DB_URL" | sed -n 's#^jdbc:postgresql://\([^/]*\)/.*#\1#p')"
  HOST="${HOSTPORT%%:*}"
  PORT="${HOSTPORT##*:}"
  [ "$PORT" = "$HOST" ] && PORT=5432
  if [ -z "$HOST" ]; then
    fail "QMS_DB_URL='$QMS_DB_URL' could not be parsed; expected jdbc:postgresql://host:port/db."
  elif command -v pg_isready >/dev/null 2>&1; then
    if pg_isready -h "$HOST" -p "$PORT" >/dev/null 2>&1; then
      ok "PostgreSQL at $HOST:$PORT is reachable"
    else
      fail "PostgreSQL at $HOST:$PORT is not reachable (pg_isready failed). Check QMS_DB_URL and that the database is running."
    fi
  elif (exec 3<>"/dev/tcp/$HOST/$PORT") 2>/dev/null; then
    exec 3>&- 3<&-
    ok "$HOST:$PORT accepts TCP connections"
  else
    fail "Could not reach $HOST:$PORT (no pg_isready available and a raw TCP connect failed). Check QMS_DB_URL and that the database is running."
  fi
else
  echo "  SKIP  QMS_DB_URL is not set; pass --skip-db for a first install, or set QMS_DB_URL to check an existing database."
fi

# ---- Disk space (FR-OPS-001) ---------------------------------------------------------------------------------------
echo "Disk space:"
MIN_FREE_GB=10
TARGET_DIR="${QMS_INSTALL_DIR:-.}"
if command -v df >/dev/null 2>&1; then
  AVAILABLE_KB="$(df -Pk "$TARGET_DIR" 2>/dev/null | awk 'NR==2 {print $4}')"
  if [ -n "$AVAILABLE_KB" ]; then
    AVAILABLE_GB=$((AVAILABLE_KB / 1024 / 1024))
    if [ "$AVAILABLE_GB" -ge "$MIN_FREE_GB" ]; then
      ok "${AVAILABLE_GB} GB free at $TARGET_DIR (minimum ${MIN_FREE_GB} GB)"
    else
      fail "Only ${AVAILABLE_GB} GB free at $TARGET_DIR; the Small server-sizing tier (SRS §24.3) needs at least ${MIN_FREE_GB} GB to install and start accumulating data."
    fi
  else
    fail "Could not determine free disk space at $TARGET_DIR."
  fi
else
  fail "'df' is not available; cannot check disk space."
fi

# ---- Clock synchronisation (FR-OPS-002: mandatory - queue ordering and SLA measurement depend on it) --------------
echo "Clock synchronisation:"
CLOCK_OK=0
if command -v timedatectl >/dev/null 2>&1; then
  STATUS="$(timedatectl show -p NTPSynchronized --value 2>/dev/null || true)"
  if [ "$STATUS" = "yes" ]; then
    ok "timedatectl reports the clock is NTP-synchronised"
    CLOCK_OK=1
  elif [ -n "$STATUS" ]; then
    fail "timedatectl reports the clock is NOT synchronised (NTPSynchronized=$STATUS). FR-OPS-002: queue ordering and SLA measurement depend on synchronised clocks across server and site devices. Enable NTP (e.g. 'timedatectl set-ntp true') and re-run preflight."
    CLOCK_OK=1
  fi
fi
if [ "$CLOCK_OK" = "0" ] && command -v chronyc >/dev/null 2>&1; then
  if chronyc tracking >/dev/null 2>&1; then
    ok "chronyd is tracking a time source"
    CLOCK_OK=1
  else
    fail "chronyc is installed but reports no synchronised time source. FR-OPS-002 requires clock sync; install/enable chrony and re-run preflight."
    CLOCK_OK=1
  fi
fi
if [ "$CLOCK_OK" = "0" ]; then
  fail "No supported time-sync service found (timedatectl or chronyc). FR-OPS-002 requires mandatory clock synchronisation across the server and every site device; install and enable one (chrony is recommended) before installing."
fi

# ---- Verdict --------------------------------------------------------------------------------------------------
echo "=================================================="
if [ "${#FAILURES[@]}" -eq 0 ]; then
  echo "PREFLIGHT PASSED: safe to install or upgrade."
  exit 0
fi
echo "PREFLIGHT FAILED: ${#FAILURES[@]} problem(s) found. Refusing to install (FR-OPS-001: no half-installs)."
for f in "${FAILURES[@]}"; do
  echo "  - $f"
done
exit 1
