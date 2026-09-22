#!/usr/bin/env bash
# FR-OPS-001, FR-OPS-020, NFR-POR-004, SRS §26.1: installs QMS from an offline artefact bundle (deploy/bundle.sh's
# own output) with no downloads at install time. Runs preflight first and refuses to proceed on any failure (no
# half-installs); loads the bundled images so the container runtime never reaches a registry; migrates as an
# explicit, separate step before the application starts (FR-OPS-020); then starts the stack.
#
# Usage: QMS_DB_PASSWORD=... deploy/install.sh --bundle qms-offline-bundle-0.1.0.tar.gz
#   (single-node and multi-node modes both use this compose file; §26.1's multi-node mode adds further API nodes
#   behind a load balancer, documented in docs/ops/installer.md - the bundle and migration step are identical.)
set -euo pipefail

BUNDLE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --bundle) BUNDLE="$2"; shift 2 ;;
    *) echo "install: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

if [ -z "$BUNDLE" ]; then
  echo "install: --bundle <path to offline bundle> is required (build one with deploy/bundle.sh)" >&2
  exit 2
fi
if [ ! -f "$BUNDLE" ]; then
  echo "install: bundle '$BUNDLE' not found" >&2
  exit 2
fi

HERE="$(cd "$(dirname "$0")" && pwd)"

echo "Step 1/4: prerequisite checks (FR-OPS-001, FR-OPS-002)"
"$HERE/preflight.sh" --skip-db

echo "Step 2/4: extracting the offline bundle (no registry access needed)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
tar -C "$WORK" -xzf "$BUNDLE"
echo "Bundle: $(cat "$WORK/VERSION" 2>/dev/null || echo unknown)"

echo "Step 3/4: loading container images from the bundle (NFR-POR-004: no downloads at install)"
docker load -i "$WORK/images.tar"

echo "Step 4/4: migrating, then starting the stack (FR-OPS-020: migration is a separate step before the app starts)"
: "${QMS_DB_PASSWORD:?set QMS_DB_PASSWORD}"
docker compose -f "$WORK/deploy/compose.yaml" run --rm migrate
docker compose -f "$WORK/deploy/compose.yaml" up -d backend proxy

echo "Install complete. Health: curl -fsS http://localhost:${QMS_HTTP_PORT:-8080}/api/v1/health/dependencies"
echo "Next: complete the setup wizard (SRS §26.2) before going live, and set a TLS-terminating proxy per docs/ops/tls-and-kiosk-display-shell.md before production traffic (NFR-SEC-010)."
