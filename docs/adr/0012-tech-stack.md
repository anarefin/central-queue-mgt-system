# 0012 — Tech stack: Java 25 / Spring Boot 4 backend, Next.js 16 static-export frontends

Status: Accepted · 2026-09-18

## Context
ADR-0010 fixed the shape — one Spring Boot backend, separately deployed browser frontends — and SRS §20.2 fixed
Spring Security with a resource-server JWT decoder, but neither pinned versions, build tooling, migrations, test
stack or the frontend framework. The installer must work air-gapped and single-node (NFR-POR-004), so every
runtime added to the install is a cost. The visitor surface must be an installable PWA with Web Push (ADR-0011).

## Decision
**Backend**
- Java 25, Spring Boot 4.1.x, Gradle 9 (Kotlin DSL), a single Gradle module.
- One package per bounded context (configuration, issuance, queue, appointment, session, notification,
  reporting, identity, audit). An ArchUnit test enforces the boundaries and that the queue engine imports no
  web or transport types (the ADR-0001 embedding seam).
- Spring Security with `spring-boot-starter-oauth2-resource-server` and method security (API-010, API-016).
- PostgreSQL 14+ with Flyway forward-only migrations, runnable as a separate step before the app starts.
- ShedLock (JDBC) for cluster-wide jobs, PostgreSQL `LISTEN/NOTIFY` for fan-out, Spring WebSocket for the stream.
- Tests: JUnit 5 and Testcontainers against real PostgreSQL. No in-memory database substitutes.

**Frontend**
- Next.js 16 with TypeScript in one pnpm workspace: apps `kiosk`, `console`, `display`, `admin`, `visitor`;
  shared packages `api-client` (generated from the backend's OpenAPI), `realtime-client`, `i18n`, `ui`.
- Every app builds as a **static export** and is served as static files by the reverse proxy. The apps are pure
  clients of the REST and WebSocket API: no Server Actions, route handlers or backend-for-frontend. All business
  rules and authorisation live in the backend.
- The API origin and other deployment values are read at boot from a runtime `config.json`, not baked in at build.
- The visitor app uses the Next.js metadata manifest and a hand-written service worker for Web Push.
- Apps and API are served behind one reverse proxy on one registrable domain so the `SameSite=Strict` refresh
  cookie works (API-017).
- Tests: Vitest and Testing Library for units; Playwright for end-to-end and UAT scripts.

## Consequences
- The installer ships a JVM image, PostgreSQL and a reverse proxy with static bundles — no Node.js runtime.
- Routes that would be dynamic segments (e.g. a ticket page) use query parameters or client-side routing,
  since static export cannot render unknown ids on the server.
- Server-side rendering, image optimisation and middleware from Next.js are not available; none is needed for
  kiosk, display, console or admin, and the visitor page renders client-side from the API.
- If a surface ever needs server rendering, switching that one app to `output: 'standalone'` adds a Node
  runtime for it alone and requires revisiting this ADR.

SRS refs: §6.1, §6.2, NFR-POR-001..004, §20.2, API-010, API-016, API-017, FR-OPS-020, NFR-MNT-004.
