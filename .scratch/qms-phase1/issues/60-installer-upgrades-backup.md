# 60 — Installer, upgrades, backup and diagnostics

**What to build:** A consultant installs the system from an offline bundle on Linux (or Windows Server), in single-node or multi-node mode, with prerequisite checks that refuse to half-install; upgrades keep waiting Tickets and open sessions; backups run and restore to target; and support gets a one-click diagnostics bundle.

**Blocked by:** 59 — Multi-node operation

**Status:** ready-for-agent

- [ ] Offline artefact bundle with backend image, PostgreSQL, reverse proxy and static app bundles; no downloads at install (NFR-POR-004, ADR-0012)
- [ ] Runs on Linux x86-64 with Docker/Podman and installs on Windows Server (NFR-POR-001)
- [ ] Single-node, multi-node and air-gapped modes (§26.1)
- [ ] Prerequisite checks incl. clock sync; refuses with a clear message (FR-OPS-001, FR-OPS-002)
- [ ] Migrations as a separate step; upgrade keeps waiting Tickets and open sessions (FR-OPS-020, FR-OPS-021, NFR-AVL-002)
- [ ] Documented rollback per release; changed defaults listed in release notes (FR-OPS-022, FR-OPS-023)
- [ ] Encrypted full + incremental backups incl. config bundle and media, restore verified to RPO ≤ 5 min / RTO ≤ 60 min (FR-OPS-030, FR-OPS-031, NFR-SEC-012, NFR-AVL-003)
- [ ] Diagnostics bundle (logs, versions, config without secrets, recent events) in one action (FR-OPS-040, §2.1)
- [ ] TLS 1.2+ everywhere incl. LAN; no HTTP in production (NFR-SEC-010)
- [ ] Kiosk and display packaged shells: locked down, no browser chrome, admin-code exit (NFR-POR-003, NFR-SEC-052)
- [ ] Site-survey checklist and hardware/environment spec for clients: zones, counters, network drops, power, mounting, speaker coverage; kiosk 10–40 °C / ≤ 80% RH and glare guidance (NFR-ENV-001, NFR-ENV-002, NFR-ENV-003, §24)
- [ ] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
