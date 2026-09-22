# Backup and recovery

Covers FR-OPS-030, FR-OPS-031, NFR-SEC-012, NFR-AVL-003 (SRS §26.4). Ticket 60.

## What is backed up (FR-OPS-030)

`deploy/backup.sh --mode full` takes, in one run:

- a full logical database dump (`pg_dump`, custom format - restorable with `pg_restore`)
- the configuration bundle (`deploy/config/` by default; override with `--config-dir`)
- uploaded media (notice-board images, branding assets; `QMS_MEDIA_DIR`/`--media-dir`, default `./data/media`)

Every one of those three pieces is individually AES-256-CBC encrypted (via `openssl enc -pbkdf2`) with
`QMS_BACKUP_KEY` before it touches disk (NFR-SEC-012: "Backups MUST be encrypted"). There is no unencrypted mode -
the script refuses to run at all without `QMS_BACKUP_KEY` set. Old full backups past `--retention-days` (default
30) are pruned automatically, so retention is configurable per FR-OPS-030.

```
QMS_BACKUP_KEY=... QMS_DB_URL=... QMS_DB_USER=... QMS_DB_PASSWORD=... \
  deploy/backup.sh --mode full --out /backups --retention-days 30
```

Schedule this with cron/systemd-timer/Task Scheduler at the interval the site's RPO needs (see below).

## Incremental backup and RPO <= 5 minutes (NFR-AVL-003)

A full dump alone only gets a site back to the moment the last full backup finished; NFR-AVL-003 needs a recovery
point at most 5 minutes old. PostgreSQL's own mechanism for that is WAL archiving, and `deploy/backup.sh --mode
wal-archive` is the real `archive_command` target: PostgreSQL calls it once per WAL segment, and it encrypts that
one segment into `<out>/wal/`. Enable it in `postgresql.conf` (or the equivalent container/Ansible config for the
Docker Compose Postgres service):

```
archive_mode    = on
archive_timeout = 300                 # forces a segment switch at least every 5 minutes, bounding the RPO
archive_command = 'deploy/backup.sh --mode wal-archive --wal-path %p --wal-file %f --out /backups'
```

With `archive_timeout = 300`, no more than 5 minutes of committed transactions can ever be missing from the archive
at any point in time, meeting NFR-AVL-003's RPO <= 5 min. Point-in-time recovery restores the last full backup and
then replays every WAL segment since it; that is a `pg_basebackup`/`recovery.conf`-style procedure outside a shell
script's own scope, and is the client's DBA's own drill on top of these two primitives, documented in the site's own
runbook per the Support model (SRS §26.6).

## Restore procedure (FR-OPS-031)

```
QMS_BACKUP_KEY=... deploy/restore.sh --from ./backups/full-<timestamp> \
  --db-url jdbc:postgresql://host:5432/qms --db-user qms --db-password ...
```

The target database must already exist and be freshly migrated (empty of data) before restoring into it - run
`docker compose -f deploy/compose.yaml run --rm migrate` (or `deploy/install.sh`) against it first. `restore.sh`
then:

1. decrypts the database dump, config bundle and media bundle;
2. restores the database with `pg_restore --clean --if-exists`;
3. unpacks the config and media bundles back into place;
4. prints elapsed time and a row-count sanity check (`site`, `ticket`, `users`).

## The acceptance drill (verified during acceptance, NFR-SEC-012, FR-OPS-031)

1. Take a full backup of a populated environment: `deploy/backup.sh --mode full`.
2. Provision a fresh, empty target database and migrate it.
3. Time the restore: `time deploy/restore.sh --from <the backup> ...`. Elapsed time is RTO; it must be <= 60
   minutes (NFR-AVL-003). `restore.sh` prints this itself.
4. Compare row counts (and, for a full drill, a few known records) between source and target.
5. Confirm the encryption key is required: attempting the same restore with the wrong `QMS_BACKUP_KEY` must fail to
   decrypt, never silently produce garbage data.

Verified in this environment: the encryption round trip (`openssl enc -aes-256-cbc -pbkdf2` then `openssl enc -d`)
was run end to end on a sample file and the decrypted output matched the original byte for byte, and the JDBC-URL
parsing both scripts share was exercised against representative `QMS_DB_URL` values. A full `pg_dump`/`pg_restore`
round trip against a running PostgreSQL server was not run in this sandbox (no PostgreSQL client tools installed
here) - run the five-step drill above against a real target before relying on this procedure in production, and
record the result (timestamp, elapsed time, row counts) as this ticket's own restore-verified evidence.
