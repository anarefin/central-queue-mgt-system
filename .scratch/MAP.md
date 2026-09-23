# cqms repo map

Queue Management System (single-tenant, air-gapped-installable). One Spring Boot backend + separately
deployed static-export Next.js frontends behind one reverse proxy (ADR-0010, ADR-0012).
Domain vocabulary (Site/Zone/Counter/Ticket/Visit/Journey/Transfer/etc.) is defined in `CONTEXT.md` at repo
root — read it before touching domain logic, terms must match verbatim. Design rationale lives in `docs/adr/`
(0001–0012), numbered and sequential.

## Top-level layout
- `backend/` — Java/Spring Boot app, Gradle (Kotlin DSL), single module.
- `frontend/` — pnpm workspace: 5 Next.js apps + 4 shared packages.
- `deploy/` — Dockerfiles, `compose.yaml`, install/backup/restore scripts, proxy + kiosk + i18n config for the
  installer.
- `docs/` — `adr/` (design decisions), `api/`, `ops/`, `admin-guide.md`, `bi-access.md`, `traceability-matrix.md`.
- `spec/` — `Queue Management System - SRS.md` (source of truth for requirements; ADRs/tickets reference its
  §numbers, e.g. FR-INT-040, NFR-PERF-006).
- `CONTEXT.md` — domain glossary, read first for terminology.
- `.scratch/` — scratch workspace (this file lives here), not part of the product.

## Backend (`backend/`)
Java 25, Spring Boot 4.1.x, single Gradle module `com.qms`, package-per-bounded-context under
`backend/src/main/java/com/qms/`:
- `queue` — the queue engine itself (scoring, wait estimates, calls, transfers, priority). Imports no web/transport
  types by design (ADR-0001 embedding seam) — enforced by `ArchitectureTest`.
- `appointment` — appointments, check-in, availability.
- `configuration/` — sub-packages: `approval`, `branding`, `breaks`, `catalogue`, `notice`, `priority`, `privacy`,
  `site`, `versioning`.
- `dashboard`, `device`, `feedback`, `identity`, `issuance` (+ `bundle`, `setup`), `mobile`, `notification`,
  `reporting`, `session`.
- `platform/` — cross-cutting: `alerts`, `connectivity`, `crypto`, `devices`, `health`, `i18n`, `idempotency`,
  `jobs`, `notifications`, `realtime`, `security`.
- `audit/` (+ `diagnostics`), `integration/` (+ `serviceaccount`, `webhook`).
- Package boundaries between bounded contexts are enforced by convention + `ArchitectureTest`
  (`backend/src/test/java/com/qms/ArchitectureTest.java`, ArchUnit) — a boundary violation fails `test`, not just
  review.
- Resources: `backend/src/main/resources/application.yml` (+ `application-migrate.yml`,
  `application-rotate-keys.yml`), `db/migration/` (Flyway, forward-only), `i18n/`, `profiles/` (vertical seed
  configs: `banking.json`, `generic.json`, `government.json`, `healthcare.json`, `producer_services.json`).
- `backend/keys/vapid/` — Web Push keys; real per-install keys are gitignored (`keys/` in root `.gitignore`),
  tests write signing keys under `build/test-keys` instead (see build config note below).

### Backend tests
- Location: `backend/src/test/java/com/qms/<same-package-as-main>/`. Also `fixtures/` (shared test fixtures incl.
  `badqueue`, `controllers`) and `support/`.
- Naming: `*Test.java` = plain unit/slice tests, no Docker (67 files). `*IT.java` = Testcontainers integration
  tests against real PostgreSQL, Docker required (84 files). `gradlew test` excludes `*IT`; `gradlew check`
  (and CI) runs both.
- **No in-memory DB substitute anywhere** — ITs need Docker running locally.
- Full suite: `cd backend && ./gradlew check` (runs unit tests, then integration tests, then the dependency-check
  task is separate — see CI below).
  - Unit-only, no Docker needed: `./gradlew test`
  - Integration-only: `./gradlew integrationTest`
- Single test file/class: `./gradlew test --tests "com.qms.queue.WaitEstimatorTest"` (swap `test` for
  `integrationTest` if it's an `*IT` class, or use `-PtestSet` pattern via `--tests "*ClassName"`).
- `maxHeapSize` for test JVM is bumped to 3g in `build.gradle.kts` — the IT suite boots ~70+ Spring contexts in one
  forked JVM and OOMs on default heap otherwise (see comment in `build.gradle.kts`).

## Frontend (`frontend/`)
pnpm workspace (`pnpm-workspace.yaml`: `apps/*`, `packages/*`); pnpm 12.4.1 pinned via `packageManager`.
- `apps/admin`, `apps/console`, `apps/kiosk`, `apps/display`, `apps/visitor` — Next.js 16 + React 19, each a
  **static export** (no SSR/route handlers/Server Actions — business logic lives only in the backend, ADR-0012).
  Each app reads its API origin from a runtime `public/config.json`, not baked in at build.
- `packages/api-client` — generated from backend OpenAPI.
- `packages/realtime-client` — WebSocket client.
- `packages/i18n` — i18n.
- `packages/ui` — shared components, Tailwind v4 theme/tokens (`theme.css`, `styles.css`, `fonts.css`), app shell.
- `frontend/e2e/` — Playwright UAT specs mapped to SRS §27.2/27.3 (`u01`…`u12` + `vertical-specific`).
  **Deliberately excluded from the pnpm workspace** (see its `package.json` description) — runs against a live
  `deploy/compose.yaml` stack, not picked up by `pnpm -r test`. Run separately: `cd frontend/e2e && pnpm e2e`.

### Frontend tests
- Location: colocated `*.test.ts(x)` next to the source file (e.g.
  `frontend/apps/admin/src/components/CatalogueAdmin.test.tsx` beside `CatalogueAdmin.tsx`). Same convention in
  `packages/ui/src/`.
- Runner: Vitest + Testing Library, jsdom.
- Full suite (all apps/packages): `cd frontend && pnpm -r test` (each package's `test` script is `vitest run`).
- Single app: `cd frontend/apps/admin && pnpm test` (or `pnpm --filter @qms/admin test`).
- Single test file: `cd frontend/apps/admin && pnpm vitest run src/components/CatalogueAdmin.test.tsx`.
- CSS lint (separate from `test`): `pnpm lint:css` (stylelint over `packages/ui/src/**/*.css` and
  `apps/*/src/**/*.css`).

## Build / typecheck
- Backend build: `cd backend && ./gradlew build` (also runs `check`). Dependency vuln scan (separate, not part of
  `check`): `./gradlew dependencyCheckAnalyze` (needs `NVD_API_KEY` env in CI; fails build on CVSS ≥ 9.0).
- Backend jar: the plain `jar` task is disabled — only the Spring Boot fat jar is built (avoids an ambiguous
  Docker `COPY` between `-plain.jar` and the boot jar).
- Frontend typecheck (all packages): `cd frontend && pnpm typecheck` (each package: `tsc --noEmit`).
- Frontend build (apps only): `cd frontend && pnpm build` (`pnpm -r --filter './apps/*' build`, i.e. `next build`
  per app, producing static export `out/`).
- Full CI sequence is in `.github/workflows/ci.yml`: backend job = `./gradlew check` then
  `./gradlew dependencyCheckAnalyze`; frontend job = `pnpm install --frozen-lockfile`, `pnpm typecheck`,
  `pnpm test`, `pnpm lint:css`, `pnpm build`, `pnpm audit --audit-level=critical`; then a `compose-smoke` job
  brings up `deploy/compose.yaml` and curls health + admin routes.

## Non-obvious gotchas
- `backend/gradlew check` requires Docker (Testcontainers ITs); running just `./gradlew test` first is much
  faster for a quick sanity check and matches what you want for a single unit-test iteration loop.
- ArchUnit (`ArchitectureTest`) will fail the build if you add a cross-bounded-context import that violates
  ADR-0010/0012 package boundaries, or if `com.qms.queue` imports a web/transport type — read the test before
  restructuring packages.
- `frontend/e2e` is NOT part of `pnpm -r test` and not part of the pnpm workspace at all — don't expect
  `pnpm -r test` to catch Playwright spec breakage, and don't add it to `pnpm-workspace.yaml`.
- Frontend apps are static export only — there is no Next.js server at runtime; don't reach for Server
  Actions/route handlers/middleware, they won't work in production deploy.
- Real secrets/keys (`backend/keys/`, `.env*`) are gitignored; tests never touch real keys — they write to
  `build/test-keys` (backend) via a `qms.security.key-dir` system property set in `build.gradle.kts`.
- `.claude/` directories are scattered throughout the tree (repo root, `backend/`, `frontend/`, per-app, even
  inside `com/qms` package dirs) and are gitignored — agent scratch state, not source.
- Domain terms have specific "avoid" synonyms called out in `CONTEXT.md` (e.g. never say "recall", never say
  "weight" for Head start, never say "building"/"branch" for Site) — grep `CONTEXT.md` before naming anything.
