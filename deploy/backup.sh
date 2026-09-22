#!/usr/bin/env bash
# FR-OPS-030, NFR-SEC-012 (SRS §26.4): automated, encrypted, configurable full and incremental backup, including the
# configuration bundle and uploaded media (notice-board images, branding assets). See docs/ops/backup-restore.md for
# the restore drill that verifies RPO <= 5 min / RTO <= 60 min (NFR-AVL-003).
#
# Modes:
#   deploy/backup.sh --mode full [--out DIR] [--retention-days N]
#       A full logical dump (pg_dump, custom format) plus the config bundle and the media directory, each
#       AES-256-encrypted with QMS_BACKUP_KEY. Old backups past --retention-days are pruned (default: 30).
#
#   deploy/backup.sh --mode wal-archive --wal-path <p> --wal-file <f> --out DIR
#       The real incremental primitive: PostgreSQL's own archive_command target (RPO <= 5 min via a short
#       archive_timeout), called once per WAL segment. Encrypts that one segment and copies it into DIR/wal/.
#       Wire it in postgresql.conf: archive_mode = on ; archive_command = 'deploy/backup.sh --mode wal-archive
#       --wal-path %p --wal-file %f --out /backups'. A full backup's basebackup label plus every WAL segment since
#       it lets a restore replay forward to any point in time (point-in-time recovery), not only to the last full.
#
# Required: QMS_BACKUP_KEY (the symmetric encryption key; NFR-SEC-012 backups MUST be encrypted). Required for
# --mode full: QMS_DB_URL, QMS_DB_USER, QMS_DB_PASSWORD (same variables the backend itself reads).
set -euo pipefail

MODE=""
OUT="./backups"
RETENTION_DAYS=30
CONFIG_BUNDLE_DIR="./deploy/config"
MEDIA_DIR="${QMS_MEDIA_DIR:-./data/media}"
WAL_PATH=""
WAL_FILE=""

while [ $# -gt 0 ]; do
  case "$1" in
    --mode) MODE="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --retention-days) RETENTION_DAYS="$2"; shift 2 ;;
    --config-dir) CONFIG_BUNDLE_DIR="$2"; shift 2 ;;
    --media-dir) MEDIA_DIR="$2"; shift 2 ;;
    --wal-path) WAL_PATH="$2"; shift 2 ;;
    --wal-file) WAL_FILE="$2"; shift 2 ;;
    *) echo "backup: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

: "${QMS_BACKUP_KEY:?set QMS_BACKUP_KEY - NFR-SEC-012 requires every backup to be encrypted, so there is no unencrypted mode}"

encrypt() { # src dest
  openssl enc -aes-256-cbc -pbkdf2 -salt -pass env:QMS_BACKUP_KEY -in "$1" -out "$2"
}

case "$MODE" in
  full)
    : "${QMS_DB_URL:?set QMS_DB_URL}"
    : "${QMS_DB_USER:?set QMS_DB_USER}"
    : "${QMS_DB_PASSWORD:?set QMS_DB_PASSWORD}"
    STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
    RUN_DIR="$OUT/full-$STAMP"
    mkdir -p "$RUN_DIR"
    WORK="$(mktemp -d)"
    trap 'rm -rf "$WORK"' EXIT

    echo "Dumping the database (pg_dump, custom format)..."
    DBHOSTPORT="$(printf '%s' "$QMS_DB_URL" | sed -n 's#^jdbc:postgresql://\([^/]*\)/\(.*\)#\1 \2#p')"
    DBHOST="$(printf '%s' "$DBHOSTPORT" | cut -d' ' -f1 | cut -d: -f1)"
    DBPORT="$(printf '%s' "$DBHOSTPORT" | cut -d' ' -f1 | cut -d: -f2)"
    DBNAME="$(printf '%s' "$DBHOSTPORT" | cut -d' ' -f2 | cut -d'?' -f1)"
    PGPASSWORD="$QMS_DB_PASSWORD" pg_dump -h "$DBHOST" -p "${DBPORT:-5432}" -U "$QMS_DB_USER" -d "$DBNAME" -F c -f "$WORK/database.dump"
    encrypt "$WORK/database.dump" "$RUN_DIR/database.dump.enc"

    echo "Bundling configuration ($CONFIG_BUNDLE_DIR)..."
    if [ -d "$CONFIG_BUNDLE_DIR" ]; then
      tar -C "$(dirname "$CONFIG_BUNDLE_DIR")" -czf "$WORK/config.tar.gz" "$(basename "$CONFIG_BUNDLE_DIR")"
      encrypt "$WORK/config.tar.gz" "$RUN_DIR/config.tar.gz.enc"
    else
      echo "  (skipped: $CONFIG_BUNDLE_DIR does not exist)"
    fi

    echo "Bundling media ($MEDIA_DIR)..."
    if [ -d "$MEDIA_DIR" ]; then
      tar -C "$(dirname "$MEDIA_DIR")" -czf "$WORK/media.tar.gz" "$(basename "$MEDIA_DIR")"
      encrypt "$WORK/media.tar.gz" "$RUN_DIR/media.tar.gz.enc"
    else
      echo "  (skipped: $MEDIA_DIR does not exist - nothing uploaded yet)"
    fi

    {
      echo "taken_at $STAMP"
      echo "db_name $DBNAME"
      echo "encryption aes-256-cbc-pbkdf2"
    } > "$RUN_DIR/manifest.txt"

    echo "Full backup written to $RUN_DIR"

    if [ "$RETENTION_DAYS" -gt 0 ] && [ -d "$OUT" ]; then
      echo "Pruning full backups older than $RETENTION_DAYS days..."
      find "$OUT" -maxdepth 1 -type d -name 'full-*' -mtime +"$RETENTION_DAYS" -print -exec rm -rf {} \;
    fi
    ;;

  wal-archive)
    : "${WAL_PATH:?--wal-path is required (the %p that postgresql.conf archive_command passes)}"
    : "${WAL_FILE:?--wal-file is required (the %f that postgresql.conf archive_command passes)}"
    [ -f "$WAL_PATH" ] || { echo "backup: WAL file '$WAL_PATH' not found" >&2; exit 1; }
    mkdir -p "$OUT/wal"
    encrypt "$WAL_PATH" "$OUT/wal/$WAL_FILE.enc"
    ;;

  *)
    echo "backup: --mode must be 'full' or 'wal-archive'" >&2
    exit 2
    ;;
esac
