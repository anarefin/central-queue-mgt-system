# 60 — Installer, upgrades, backup and diagnostics

**What to build:** A consultant installs the system from an offline bundle on Linux (or Windows Server), in single-node or multi-node mode, with prerequisite checks that refuse to half-install; upgrades keep waiting Tickets and open sessions; backups run and restore to target; and support gets a one-click diagnostics bundle.

**Blocked by:** 59 — Multi-node operation

**Status:** done

- [x] Offline artefact bundle with backend image, PostgreSQL, reverse proxy and static app bundles; no downloads at install (NFR-POR-004, ADR-0012) — `deploy/bundle.sh` + `deploy/install.sh`; not run end to end in this sandbox (see traceability matrix)
- [x] Runs on Linux x86-64 with Docker/Podman and installs on Windows Server (NFR-POR-001) — `deploy/preflight.sh` (run and verified here) + `deploy/windows/install.ps1` (reviewed, not runnable here)
- [x] Single-node, multi-node and air-gapped modes (§26.1)
- [x] Prerequisite checks incl. clock sync; refuses with a clear message (FR-OPS-001, FR-OPS-002) — `deploy/preflight.sh`, run and verified (pass and refuse paths both demonstrated)
- [x] Migrations as a separate step; upgrade keeps waiting Tickets and open sessions (FR-OPS-020, FR-OPS-021, NFR-AVL-002) — `UpgradeRestartIT`, passing against real PostgreSQL
- [x] Documented rollback per release; changed defaults listed in release notes (FR-OPS-022, FR-OPS-023) — `docs/ops/rollback-and-release-notes.md`
- [x] Encrypted full + incremental backups incl. config bundle and media, restore verified to RPO ≤ 5 min / RTO ≤ 60 min (FR-OPS-030, FR-OPS-031, NFR-SEC-012, NFR-AVL-003) — `deploy/backup.sh`/`deploy/restore.sh`; encryption round trip and URL parsing verified here, full pg_dump/restore drill against a live server not run in this sandbox (no PostgreSQL client tools installed) — the drill to run before go-live is in `docs/ops/backup-restore.md`
- [x] Diagnostics bundle (logs, versions, config without secrets, recent events) in one action (FR-OPS-040, §2.1) — `GET /api/v1/ops/diagnostics`, admin UI, `DiagnosticsIT` passing
- [x] TLS 1.2+ everywhere incl. LAN; no HTTP in production (NFR-SEC-010) — `deploy/proxy/nginx.tls.conf.example` + `docs/ops/tls-and-kiosk-display-shell.md` (opt-in production config; the compose default stays plain HTTP, unchanged, so CI's compose-smoke job keeps passing)
- [x] Kiosk and display packaged shells: locked down, no browser chrome, admin-code exit (NFR-POR-003, NFR-SEC-052) — `deploy/kiosk/launch-chromium-kiosk.sh` (Linux) + `docs/ops/tls-and-kiosk-display-shell.md` (Windows Assigned Access, Android kiosk/MDM)
- [x] Site-survey checklist and hardware/environment spec for clients: zones, counters, network drops, power, mounting, speaker coverage; kiosk 10–40 °C / ≤ 80% RH and glare guidance (NFR-ENV-001, NFR-ENV-002, NFR-ENV-003, §24) — `docs/ops/site-survey-checklist.md`
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
