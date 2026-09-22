# Installer, upgrades and prerequisite checks

Covers NFR-POR-001, NFR-POR-004, FR-OPS-001, FR-OPS-002, FR-OPS-020..023, §26.1-§26.3. Ticket 60.

## Installation modes (SRS §26.1)

| Mode | What runs where | Script/compose entry point |
| --- | --- | --- |
| Single node | Postgres, backend, proxy on one host, container compose | `deploy/compose.yaml` (as already used by `docker compose -f deploy/compose.yaml up --build`, and by CI's `compose-smoke` job) |
| Multi node | Two or more backend nodes behind a load balancer, database on its own host with a standby (ticket 59, ADR-0010) | Run `deploy/compose.yaml`'s `backend` service on each node against one shared `QMS_DB_URL`, no sticky sessions required (NFR-SCL-001); front them with any TCP/HTTP load balancer. The application itself needs no per-node configuration beyond the shared database. |
| Air-gapped | No registry access at install time | `deploy/bundle.sh` then `deploy/install.sh --bundle <file>` (Linux) or `deploy/windows/install.ps1 -Bundle <file>` (Windows Server) |

## Offline artefact bundle (NFR-POR-004, ADR-0012)

`deploy/bundle.sh` builds the backend image, saves it alongside the pinned `postgres:14-alpine` image and the proxy
image into one tarball, and packages `deploy/` (compose file, proxy config, i18n packs, runtime config template),
the installer/operations scripts and this documentation. Nothing in the bundle needs a package registry at install
time: `deploy/install.sh` only ever calls `docker load` against the bundled tarball, never `docker pull`.

```
deploy/bundle.sh [output-file]              # build, on a host with registry access
deploy/install.sh --bundle <output-file>    # install, on the target - no registry access needed
```

## Prerequisite checks (FR-OPS-001, FR-OPS-002)

`deploy/preflight.sh` (Linux) and `deploy/windows/install.ps1`'s own preflight section (Windows Server) both check,
before anything is installed or changed:

- OS and architecture (NFR-POR-001: Linux x86-64, or Windows Server)
- container runtime present and reachable (Docker or Podman)
- disk space (10 GB minimum, the Small tier's own floor, SRS §24.3)
- database reachability, when `QMS_DB_URL` is already set (skipped on a first install with `--skip-db`)
- **clock synchronisation** (FR-OPS-002: mandatory, because queue ordering and SLA measurement both depend on it) -
  `timedatectl`/`chronyc` on Linux, the Windows Time service on Windows Server

Any failure is reported together (not one at a time) and the script exits non-zero **without installing anything**
(FR-OPS-001: "refuse to proceed with a clear message rather than half-installing"). `deploy/install.sh` and
`deploy/windows/install.ps1` both run this check first and stop if it fails.

Verified manually in this environment (no live target host in this sandbox): `deploy/preflight.sh --skip-db` run
against this development machine correctly reports Docker present/reachable and disk space OK, and correctly
**refuses** (non-zero exit, itemised message) when no NTP time-sync service is present - demonstrating both the pass
and the refuse-to-half-install paths.

## Upgrades (FR-OPS-020, FR-OPS-021, §26.3)

Migrations are forward-only, idempotent, and run as a separate step before the new application version starts
(FR-OPS-020) - this is already how `deploy/compose.yaml`'s `migrate` service works (`backend` only starts once
`migrate` has completed) and is covered by `MigrationIT` (ticket 01) and every ticket's own migration.

An upgrade never loses a waiting Ticket or an open Counter Session, and both come back exactly as they were if a
restart is required (FR-OPS-021, NFR-AVL-002): nothing about a Ticket's or a Counter Session's state lives only in
a running backend process's memory (ADR-0010) - every fact is a row in PostgreSQL, so a fresh process reading the
same database sees exactly what the old one left. `backend/src/test/java/com/qms/platform/UpgradeRestartIT.java`
proves this against real PostgreSQL: it opens a Counter Session and issues a waiting Ticket on one running backend
node, stops that node entirely, starts a brand-new node against the same database (migrations already applied, the
same sequencing `deploy/compose.yaml` and this doc use), and shows the session is still open and calling "next" on
it reaches the exact Ticket that was waiting before the restart.

Upgrade procedure:

1. Take a backup (`deploy/backup.sh --mode full`; see `docs/ops/backup-restore.md`).
2. Pull or load the new version's images.
3. Run the migration step alone: `docker compose -f deploy/compose.yaml run --rm migrate` (or the bundle's own
   compose file for an air-gapped upgrade).
4. Restart the `backend` service(s) one at a time on a multi-node install, so the site never loses all API capacity
   at once; a single-node install has one, necessarily brief, restart window.
5. Confirm `GET /api/v1/health/dependencies` is `up` on every node before considering the upgrade complete.

## Rollback (FR-OPS-022) and changed defaults (FR-OPS-023)

See `docs/ops/rollback-and-release-notes.md` for the rollback template every release fills in, and the requirement
that release notes list any changed configuration default explicitly.
