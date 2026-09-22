#!/usr/bin/env bash
# FR-OPS-031, NFR-AVL-003 (SRS §26.4): the documented, verifiable restore procedure for a deploy/backup.sh --mode
# full backup. Decrypts the database dump, config bundle and media bundle, then restores the database dump into a
# target database with pg_restore. docs/ops/backup-restore.md walks the full acceptance drill (RPO <= 5 min via WAL
# replay past this point, RTO <= 60 min for this script's own steps end to end).
#
# Usage: QMS_BACKUP_KEY=... deploy/restore.sh --from BACKUP_DIR --db-url jdbc:postgresql://host:port/db \
#          --db-user USER --db-password PASSWORD [--config-out DIR] [--media-out DIR]
#
# BACKUP_DIR is one run directory from deploy/backup.sh --mode full (e.g. ./backups/full-20260101T000000Z).
# The target database must already exist and be empty (e.g. freshly migrated: `deploy/install.sh` or
# `docker compose run migrate`) - pg_restore fills it; it does not create the database itself.
set -euo pipefail

FROM=""
DB_URL=""
DB_USER=""
DB_PASSWORD=""
CONFIG_OUT="./deploy/config"
MEDIA_OUT="${QMS_MEDIA_DIR:-./data/media}"

while [ $# -gt 0 ]; do
  case "$1" in
    --from) FROM="$2"; shift 2 ;;
    --db-url) DB_URL="$2"; shift 2 ;;
    --db-user) DB_USER="$2"; shift 2 ;;
    --db-password) DB_PASSWORD="$2"; shift 2 ;;
    --config-out) CONFIG_OUT="$2"; shift 2 ;;
    --media-out) MEDIA_OUT="$2"; shift 2 ;;
    *) echo "restore: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

: "${QMS_BACKUP_KEY:?set QMS_BACKUP_KEY (the same key the backup was encrypted with)}"
: "${FROM:?--from BACKUP_DIR is required}"
: "${DB_URL:?--db-url is required}"
: "${DB_USER:?--db-user is required}"
: "${DB_PASSWORD:?--db-password is required}"
[ -d "$FROM" ] || { echo "restore: '$FROM' is not a directory" >&2; exit 1; }

START_EPOCH="$(date +%s)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

decrypt() { # src dest
  openssl enc -d -aes-256-cbc -pbkdf2 -pass env:QMS_BACKUP_KEY -in "$1" -out "$2"
}

echo "Step 1/3: decrypting the backup..."
[ -f "$FROM/database.dump.enc" ] || { echo "restore: $FROM/database.dump.enc not found - is this a deploy/backup.sh --mode full directory?" >&2; exit 1; }
decrypt "$FROM/database.dump.enc" "$WORK/database.dump"
[ -f "$FROM/config.tar.gz.enc" ] && decrypt "$FROM/config.tar.gz.enc" "$WORK/config.tar.gz"
[ -f "$FROM/media.tar.gz.enc" ] && decrypt "$FROM/media.tar.gz.enc" "$WORK/media.tar.gz"

echo "Step 2/3: restoring the database into $DB_URL (must already exist and be freshly migrated, empty of data)..."
DBHOSTPORT="$(printf '%s' "$DB_URL" | sed -n 's#^jdbc:postgresql://\([^/]*\)/\(.*\)#\1 \2#p')"
DBHOST="$(printf '%s' "$DBHOSTPORT" | cut -d' ' -f1 | cut -d: -f1)"
DBPORT="$(printf '%s' "$DBHOSTPORT" | cut -d' ' -f1 | cut -d: -f2)"
DBNAME="$(printf '%s' "$DBHOSTPORT" | cut -d' ' -f2 | cut -d'?' -f1)"
PGPASSWORD="$DB_PASSWORD" pg_restore -h "$DBHOST" -p "${DBPORT:-5432}" -U "$DB_USER" -d "$DBNAME" --clean --if-exists --no-owner "$WORK/database.dump"

echo "Step 3/3: restoring configuration and media..."
if [ -f "$WORK/config.tar.gz" ]; then
  mkdir -p "$(dirname "$CONFIG_OUT")"
  tar -C "$(dirname "$CONFIG_OUT")" -xzf "$WORK/config.tar.gz"
  echo "  config -> $CONFIG_OUT"
fi
if [ -f "$WORK/media.tar.gz" ]; then
  mkdir -p "$(dirname "$MEDIA_OUT")"
  tar -C "$(dirname "$MEDIA_OUT")" -xzf "$WORK/media.tar.gz"
  echo "  media -> $MEDIA_OUT"
fi

ELAPSED=$(( $(date +%s) - START_EPOCH ))
echo "Restore complete in ${ELAPSED}s (NFR-AVL-003 target: RTO <= 3600s for this script's own steps)."
echo "Verify: row counts below, and docs/ops/backup-restore.md's acceptance drill before trusting this restore."
PGPASSWORD="$DB_PASSWORD" psql -h "$DBHOST" -p "${DBPORT:-5432}" -U "$DB_USER" -d "$DBNAME" -c \
  "SELECT 'site' t, count(*) FROM site UNION ALL SELECT 'ticket', count(*) FROM ticket UNION ALL SELECT 'users', count(*) FROM users;" \
  2>/dev/null || true
