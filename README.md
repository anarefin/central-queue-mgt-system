# QMS — Queue Management System

A single-tenant queue management system that is configured per client without code changes and can be installed on an air-gapped LAN. It is industry-agnostic and ships with vertical profiles for **banking**, **healthcare**, **producer services**, **government** and a **generic** setup.

- Requirements (source of truth for `FR-`/`NFR-` IDs): [spec/Queue Management System - SRS.md](<spec/Queue Management System - SRS.md>)
- Domain glossary: [CONTEXT.md](CONTEXT.md). Use its terms verbatim in code, UI and docs.

## Features (Phase 1)

- **Ticket issuance** from a kiosk, at reception, through the visitor mobile web app (PWA, remote queuing) and on appointment check-in
- **Appointments** with check-in
- **Queue routing**: priority classes (head start and escalation), transfer, re-announce and miss, hold
- **Agent console** for counter staff
- **Display boards** with voice announcements
- **Administration**, a live dashboard with alerts, and reports with CSV/XLSX/PDF export and BI extracts
- **English and Bangla**
- **Notifications** by Web Push, email (SMTP) and in-app realtime updates
- **Audit log**, webhooks, service accounts, visitor directory lookup and import

Deferred to Phase 2: native apps, SMS/OTP, site edge node, cross-site transfer, hardware drivers, observability stack, MFA/OIDC/LDAP.

## Architecture

```
 Browsers (admin, console, kiosk, display, visitor)
                    │  one origin
                    ▼
        nginx proxy ── serves the static Next.js exports
                    │  /api/v1/**  (REST + WebSocket)
                    ▼
    Spring Boot backend (1..n nodes, no sticky sessions)
                    │  JDBC + LISTEN/NOTIFY fan-out
                    ▼
               PostgreSQL
```

The apps and the API must share one origin: the refresh token is kept in an HttpOnly, `SameSite=Strict` cookie (`qms_refresh`). See [ADR 0010](docs/adr/0010-separate-frontend-single-spring-boot-backend.md).

## Tech stack

| Layer | Technology |
|---|---|
| Backend | Java 25, Spring Boot 4.1 (Web MVC, WebSocket, JDBC — no JPA, Security as OAuth2 resource server, Mail, Validation), Gradle Kotlin DSL |
| Database | PostgreSQL 14+ (compose uses 18), Flyway migrations |
| Reporting | Apache POI (XLSX), PDFBox (PDF) |
| Frontend | Next.js 16 (static export only), React 19, TypeScript, Tailwind CSS 4, pnpm 12.4.1 workspace, Node 24 |
| Testing | JUnit 5, Testcontainers, ArchUnit; Vitest, Testing Library; Playwright |
| Runtime | Docker Compose, nginx 1.27, `eclipse-temurin:25-jre` |

Rationale: [ADR 0012 — Tech stack](docs/adr/0012-tech-stack.md).

## Repository layout

| Path | Contents |
|---|---|
| [backend/](backend/) | Spring Boot app. One package per bounded context under `com.qms` (`queue`, `issuance`, `appointment`, `identity`, `reporting`, `notification`, …) plus `platform` for cross-cutting code |
| [frontend/apps/](frontend/apps/) | Five Next.js apps: `admin`, `console` (agent), `kiosk`, `display`, `visitor` (PWA). Each is served under a base path of the same name |
| [frontend/packages/](frontend/packages/) | Shared `api-client`, `realtime-client`, `i18n`, `ui` |
| [frontend/e2e/](frontend/e2e/) | Playwright UAT suite. Uses npm and is **not** part of the pnpm workspace |
| [deploy/](deploy/) | Compose file, Dockerfiles, nginx config, install/backup/restore scripts, i18n packs, load tests, demo seeds |
| [docs/](docs/) | ADRs, admin guide, ops runbooks, API error codes, testing guide |
| [spec/](spec/) | Software requirements specification |
| [.github/workflows/ci.yml](.github/workflows/ci.yml) | CI pipeline |

## Quick start (Docker Compose)

Prerequisite: Docker with Compose.

```bash
QMS_DB_PASSWORD=change-me \
QMS_BOOTSTRAP_ADMIN_USERNAME=admin \
QMS_BOOTSTRAP_ADMIN_PASSWORD=change-me \
docker compose -f deploy/compose.yaml up --build
```

This starts `postgres`, a one-shot `migrate` job, `backend` and `proxy`. You can also put these variables in `deploy/.env`.

| URL (default port 8080, set `QMS_HTTP_PORT` to change) | App |
|---|---|
| http://localhost:8080/admin/ | Administration |
| http://localhost:8080/console/ | Agent console |
| http://localhost:8080/kiosk/ | Self-service kiosk |
| http://localhost:8080/display/ | Display board |
| http://localhost:8080/visitor/ | Visitor PWA |
| http://localhost:8080/api/v1/health/ready | Readiness check |

Notes:

- The bootstrap admin password must satisfy the password policy (see [Security](#security-and-conventions)).
- Over plain HTTP on a host other than `localhost`, set `QMS_REFRESH_COOKIE_SECURE=false` or login will not persist.
- Apply a vertical profile from the admin setup wizard, or with `POST /api/v1/setup/profile` and the body `{"profile_id": "banking"}` (`healthcare`, `producer_services`, `government`, `generic`).
- Demo data for the Aarong showcase: [deploy/demo/aarong/README.md](deploy/demo/aarong/README.md).

## Local development

### Backend

Requires JDK 25 and a PostgreSQL database at `localhost:5432/qms` (user `qms`).

```bash
cd backend
QMS_DB_PASSWORD=... \
QMS_BOOTSTRAP_ADMIN_USERNAME=admin QMS_BOOTSTRAP_ADMIN_PASSWORD=... \
./gradlew bootRun
```

The API listens on port 8080. Flyway migrations run on start (`QMS_MIGRATE_ON_START=true` by default).

### Frontend

Requires Node 24 and pnpm (`corepack enable`).

```bash
cd frontend
pnpm install
pnpm --filter @qms/admin dev     # or @qms/console, @qms/kiosk, @qms/display, @qms/visitor
```

Each app reads the API origin at runtime from `public/config.json` (`{"apiOrigin": ""}`, where empty means same origin). Per-app variables are listed in `frontend/apps/<app>/.env.example`.

## Build, test and lint

### Backend (`cd backend`)

| Command | What it does |
|---|---|
| `./gradlew test` | Unit tests (no Docker) |
| `./gradlew integrationTest` | Testcontainers `*IT` tests (needs Docker) |
| `./gradlew check` | Unit + integration + ArchUnit architecture tests |
| `./gradlew bootJar` | Build the executable jar |
| `./gradlew dependencyCheckAnalyze` | OWASP scan, fails at CVSS ≥ 9.0 (needs `NVD_API_KEY`) |

Run a single test with `./gradlew test --tests "com.qms.queue.WaitEstimatorTest"`.

### Frontend (`cd frontend`)

| Command | What it does |
|---|---|
| `pnpm typecheck` | `tsc --noEmit` in every package |
| `pnpm test` | Vitest in every package |
| `pnpm lint:css` | Stylelint |
| `pnpm build` | Static export of each app into `apps/<app>/out/` |

Tests live next to their source as `*.test.tsx`.

### End-to-end (`cd frontend/e2e`)

Run these against a running compose stack:

```bash
npm install
npx playwright install --with-deps chromium
BASE_URL=http://localhost:8080 QMS_PROFILE=banking npm run e2e     # or: npm run e2e:ui
```

## Configuration

The backend is configured entirely through environment variables. The most important ones:

| Variable | Purpose | Default |
|---|---|---|
| `QMS_DB_URL` / `QMS_DB_USER` / `QMS_DB_PASSWORD` | Database connection | `jdbc:postgresql://localhost:5432/qms` / `qms` / — |
| `QMS_MIGRATE_ON_START` | Run Flyway on startup (turn off in production) | `true` |
| `QMS_BOOTSTRAP_ADMIN_USERNAME` / `_PASSWORD` | First System Administrator | — |
| `QMS_ISSUER`, `QMS_KEY_DIR` | JWT issuer and signing-key directory | `./keys` |
| `QMS_VAPID_KEY_DIR`, `QMS_VAPID_SUBJECT` | Web Push keys | — |
| `QMS_REFRESH_COOKIE_SECURE` | `Secure` flag on the refresh cookie | `true` |
| `QMS_CONFIG_BUNDLE_SECRET` | Secret for config export/import bundles | — |
| `QMS_SMTP_HOST` / `_PORT` / `_USERNAME` / `_PASSWORD` / `_STARTTLS` / `_FROM` | Outgoing email | — |
| `QMS_I18N_PACK_DIR`, `QMS_SITE_DEFAULT_LANGUAGE` | Language packs and default language | — |
| `QMS_HTTP_PORT` | Host port of the proxy (compose only) | `8080` |

Queue tuning (`QMS_QUEUE_*`), reporting and export, retention, visitor directory and connectivity-probe settings are all listed in [backend/src/main/resources/application.yml](backend/src/main/resources/application.yml).

## Database and migrations

- Migrations are forward-only Flyway scripts in [backend/src/main/resources/db/migration](backend/src/main/resources/db/migration).
- In production, migrations run as a separate step, not on backend start:
  - `java -jar app.jar --spring.profiles.active=migrate`, or
  - `docker compose -f deploy/compose.yaml run --rm migrate`
- Rotate JWT signing keys with `--spring.profiles.active=rotate-keys`.

## Deployment

Three install modes are supported (details in [docs/ops/installer.md](docs/ops/installer.md)):

- **Single node** — [deploy/compose.yaml](deploy/compose.yaml).
- **Multi-node** — several backends sharing one database; no sticky sessions needed.
- **Air-gapped** — build a bundle with `deploy/bundle.sh [out]`, then install it with `deploy/install.sh --bundle <file>` (Linux) or `deploy/windows/install.ps1 -Bundle <file>` (Windows).

Supporting scripts:

| Script | Purpose |
|---|---|
| `deploy/preflight.sh` | Checks OS/arch, Docker or Podman, disk space, database and NTP clock sync |
| `deploy/backup.sh --mode full`, `deploy/restore.sh` | Backup and restore ([runbook](docs/ops/backup-restore.md)) |
| `deploy/proxy/nginx.tls.conf.example` | TLS termination ([guide](docs/ops/tls-and-kiosk-display-shell.md)) |
| `deploy/kiosk/launch-chromium-kiosk.sh` | Locked-down kiosk/display browser |
| `deploy/loadtest/` | Load-test seed and token issuance script |

Production images: `deploy/backend.Dockerfile` (runs as a non-root user) and `deploy/proxy/Dockerfile` (builds the frontends and serves them from nginx; no Node runtime in production).

## Security and conventions

- **Authentication**: stateless JWT; Spring Security runs as an OAuth2 resource server. Signing keys are generated per install into `QMS_KEY_DIR` and are never committed. Idle timeouts: 30 minutes for admins, 12 hours for agents. Visitors sign in with email + one-time code.
- **Roles**: `system_admin`, `org_admin`, `team_admin`, `agent`, `reception_operator`, `kiosk`, `display`, `visitor`, plus host-system service accounts.
- **Passwords**: bcrypt (cost 12) with a configurable policy — minimum length, character classes and reuse history. See `identity/SecurityProperties.java`.
- **Time**: timestamps are stored in UTC and shown in each Site's IANA timezone (e.g. `Asia/Dhaka`). Reports bucket by site-local time. NTP clock sync is mandatory.
- **Token numbers** always use Western Arabic digits, whatever the UI language.
- **Architecture rules** (enforced by ArchUnit): one package per bounded context; the queue engine imports no web types.
- **Frontends** are static exports only — no SSR, Server Actions or route handlers.
- **Logs** are JSON (logstash format) with secrets redacted.
- **Tests** never use an in-memory database; integration tests run against PostgreSQL via Testcontainers.

## Continuous integration

[.github/workflows/ci.yml](.github/workflows/ci.yml) runs on every push and pull request:

| Job | Steps |
|---|---|
| `backend` | `./gradlew check`, then the OWASP dependency check |
| `frontend` | install, typecheck, test, CSS lint, build, `pnpm audit` |
| `compose-smoke` | Starts the compose stack and probes `/api/v1/health/dependencies` and `/admin/` |
| `e2e` | Manual dispatch only; Playwright across the banking, healthcare and producer services profiles |

## Documentation

- Architecture decisions: [docs/adr/](docs/adr/)
- Admin guide: [docs/admin-guide.md](docs/admin-guide.md)
- BI access: [docs/bi-access.md](docs/bi-access.md)
- API error codes: [docs/api/error-codes.md](docs/api/error-codes.md)
- Requirements traceability: [docs/traceability-matrix.md](docs/traceability-matrix.md)
- Operations: [installer](docs/ops/installer.md), [backup and restore](docs/ops/backup-restore.md), [rollback and release notes](docs/ops/rollback-and-release-notes.md), [site survey checklist](docs/ops/site-survey-checklist.md), [TLS and kiosk/display shell](docs/ops/tls-and-kiosk-display-shell.md)
- Testing guide: [docs/testing-guide/index.html](docs/testing-guide/index.html)
