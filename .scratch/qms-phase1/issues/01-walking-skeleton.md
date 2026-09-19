# 01 — Walking skeleton: backend, frontend workspace and single-node stack

**What to build:** A developer can bring up the whole system with one command on one machine and see the admin app report that the backend and database are healthy. This is the empty-but-real path every later ticket extends: a Spring Boot backend laid out by bounded context, PostgreSQL with forward-only migrations, the five frontend apps as static exports behind one reverse proxy, and CI that builds and tests both sides. Stack per ADR-0012.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] Backend on Java 25 / Spring Boot 4.1 / Gradle 9 with one package per bounded context (configuration, issuance, queue, appointment, session, notification, reporting, identity, audit) (ADR-0010, ADR-0012)
- [ ] Architecture test enforces package boundaries and that the queue engine package has no web/transport dependency (ADR-0001 seam)
- [ ] PostgreSQL schema managed by forward-only, idempotent migrations runnable as a separate step before the app starts (FR-OPS-020, §2.1)
- [ ] Health endpoints distinguish liveness, readiness and dependency health (database; placeholders for realtime hub and notification gateway) (NFR-MNT-002)
- [ ] Structured JSON logs with a correlation id propagated through each request and returned as `trace_id` (NFR-MNT-001)
- [ ] Every API error uses the §20.3 error envelope with a code from a closed, documented set; base path `/api/v1`, UTF-8 JSON (§20.1, FR-I18N-022)
- [ ] pnpm workspace with Next.js 16 apps kiosk, console, display, admin, visitor (static export) and shared packages api-client, realtime-client, i18n, ui (stubs); each app reads its API origin from a runtime config.json
- [ ] Admin app shell shows backend health fetched through the api-client
- [ ] Single-node container compose: backend, PostgreSQL, reverse proxy serving all apps and the API on one origin
- [ ] CI builds and tests backend (JUnit 5 + Testcontainers) and frontend (Vitest), and fails on unpatched critical dependency vulnerabilities (NFR-SEC-051)
- [ ] PostgreSQL 14+ is the only datastore; no proprietary extensions (NFR-POR-002)
- [ ] Traceability matrix created with columns requirement ID, section, test type, test reference, status (§27.1)
