#!/usr/bin/env bash
# NFR-POR-004, ADR-0012, SRS §26.1 (air-gapped mode): builds every container image this install needs, saves them
# plus the compose file, proxy config and static app bundles into one offline artefact tarball. `deploy/install.sh`
# loads images from this tarball with no registry access, so a regulated or air-gapped site never needs outbound
# network access at install time.
#
# Usage: deploy/bundle.sh [output-file]   (default: qms-offline-bundle-<version>.tar.gz, run from the repo root)
set -euo pipefail

cd "$(dirname "$0")/.."

VERSION="$(grep -m1 '^version' backend/build.gradle.kts | sed -E 's/.*"([^"]+)".*/\1/')"
OUT="${1:-qms-offline-bundle-${VERSION}.tar.gz}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "Building images (backend, proxy)..."
docker compose -f deploy/compose.yaml build backend proxy migrate

echo "Pulling pinned third-party images (postgres:14-alpine) so the bundle needs no registry access at install..."
docker pull postgres:14-alpine

echo "Saving images to $WORK/images.tar..."
IMAGES="$(QMS_DB_PASSWORD=bundling docker compose -f deploy/compose.yaml config --images)"
docker save -o "$WORK/images.tar" $IMAGES

echo "Copying deploy/ (compose file, proxy config, i18n packs, runtime config template)..."
mkdir -p "$WORK/deploy"
cp -R deploy/. "$WORK/deploy/"

echo "Copying installer and operations scripts..."
cp deploy/preflight.sh deploy/install.sh deploy/backup.sh deploy/restore.sh "$WORK/deploy/" 2>/dev/null || true

echo "Copying documentation (installer, backup/restore, rollback, site survey)..."
mkdir -p "$WORK/docs"
cp -R docs/ops "$WORK/docs/"
cp docs/admin-guide.md "$WORK/docs/"

VERSION_FILE="$WORK/VERSION"
{
  echo "qms-backend $VERSION"
  echo "bundled_at $(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$VERSION_FILE"

echo "Packing $OUT..."
tar -C "$WORK" -czf "$OUT" .

echo "Done: $OUT"
echo "On the target host, offline and with no registry access: deploy/install.sh --bundle $OUT"
