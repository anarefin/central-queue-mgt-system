# MAP — cqms (Queue Management System, "QMS")

Root: /Users/ahmadnaqibularefin/Projects/cqms. Single-tenant QMS. Git repo (branch history: eca393f initial, bb9b6df tickets 01-04, bd1bd0b).
Java 25, Spring Boot 4.1.1, Gradle 9.2.1 wrapper; Next.js 16 / React 19 / TS 5.9 / Vitest 5; pnpm 12.4.1; Node 24 in CI (25.2.1 local).

## Layout
- CONTEXT.md            domain glossary (Site, Zone, Counter, Visit, Ticket, Session binding, Score, Head start...). Use terms verbatim.
- spec/Queue Management System - SRS.md   1899 lines; requirement IDs (FR-*, NFR-*, API-*) are cited in code comments/tests.
- docs/adr/0001..0012   decisions (0010 backend shape, 0012 tech stack, 0001 queue-engine embedding seam).
- docs/api/error-codes.md   closed error-code set. docs/admin-guide.md (tickets 01-04). docs/traceability-matrix.md (req ID -> test; append rows per ticket).
- backend/              Gradle single module `qms-backend` (build.gradle.kts, settings.gradle.kts, gradlew, src/).
- frontend/             pnpm workspace (apps/*, packages/*), tsconfig.base.json (strict, noUncheckedIndexedAccess, noEmit).
- deploy/               compose.yaml (postgres, migrate, backend, proxy), backend.Dockerfile, proxy/{Dockerfile,nginx.conf}, config/config.json, i18n/{backend,web} (empty pack dirs).
- .github/workflows/ci.yml   jobs: backend, frontend, compose-smoke.
- .scratch/qms-phase1/issues/   61 ticket files 01..61-*.md + DEPENDENCIES.md (authoritative status: 01-04 done, 05+ todo; execution order = filename order).
- _skills-to-install/   ignore. .claude/ is gitignored/empty. .vscode/ minimal.
- Generated, gitignored, ignore: backend/build, backend/.gradle, frontend/**/.next, frontend/apps/*/out, *.tsbuildinfo, next-env.d.ts, backend/build/test-keys.

## Backend (backend/src/main/java/com/qms/...) — one package per bounded context, no modular framework
- platform/      cross-cutting; MUST NOT import any context package (ArchUnit).
  - root: ApiException, ErrorCode (closed enum), ApiError, ErrorEnvelopeFactory, GlobalExceptionHandler, ErrorResponseWriter, TraceIdFilter/TraceIds, LogRedaction*, ApiPathConfig (BASE_PATH=/api/v1), TimeConfig (Clock bean), Profiles.
  - security/   Role, Permission, PermissionMatrix, Scope/ScopeGuard, Authz, CurrentUser, @PublicEndpoint, AuthenticatedUser.
  - health/     /api/v1/health/{live,ready,dependencies}; DatabaseProbe real, PlaceholderProbes for hub/notification.
  - i18n/       Messages, LanguageResolver, site/user/request language; packs in src/main/resources/i18n/messages_{en,bn}.properties.
- identity/      staff auth: LocalPasswordProvider, JWT (ES256 keys in files via SigningKeyStore, dir qms.security.key-dir), refresh-token cookie, lockout, step-up, users/roles CRUD (/auth, /users), BootstrapAdmin, KeyRotationRunner.
- audit/         append-only audit log: AuditWriter, AuditRepository, cursor paging, redaction, CSV export (/audit).
- configuration/approval/   maker-checker approvals (/approvals). Rest of configuration (sites/zones/counters/catalogue) = ticket 05/06, not built.
- issuance, queue, appointment, session, notification, reporting: only package-info.java (empty placeholders). queue must stay free of web/transport types.
- Resources: application.yml, application-migrate.yml (run migrations then exit, no web), application-rotate-keys.yml (rotate key then exit), db/migration/V1__baseline, V2__identity, V3__audit_and_approvals (next = V4; forward-only, plain PostgreSQL, no extensions).
- REST base path /api/v1; error envelope {error:{code,message,message_i18n,details,trace_id}}. Endpoints: /health, /auth/{login,refresh,logout,me,password}, /users, /approvals, /audit.

## Frontend (frontend/)
- apps/ (all static exports, `output: "export"`, basePath /<app>, trailingSlash): admin (only real app: login, signed-out, HealthPanel, auth libs), console, display, kiosk, visitor (stubs: layout/page/providers).
- packages/ (consumed as TS source via `exports` -> src/index.ts; apps use transpilePackages):
  - api-client   fetch client, error types (mirror ErrorCode), createAuth/session (refresh + in-memory access token), runtime config loader (/config.json).
  - i18n         packs/en.json, bn.json, resolve, Western-Arabic numerals (numerals.ts), React provider (`@qms/i18n/react`).
  - ui           shared components + styles.css, Noto Sans Bengali font (fonts.css).
  - realtime-client   stub (WebSocket hub arrives with ticket 11).
- Runtime config: each app reads /<app>/config.json ({apiOrigin}) at boot; source in apps/*/public/config.json; deploy/config/config.json is mounted over it.

## Tests
- Backend: backend/src/test/java/com/qms/<context>/ mirroring main packages. Naming: `*Test` = unit/slice, no Docker; `*IT` = integration, Docker.
  - support/PostgresContainerConfig (postgres:14-alpine, @ServiceConnection), support/MutableClock; platform/WebSliceTestConfig (beans for @WebMvcTest slices); fixtures/ (bad classes used by ArchitectureTest/ControllerSecurityTest — excluded from scans).
  - src/test/resources/permission-matrix.txt: SRS 5.2 transcribed independently; PermissionMatrixTest compares it to PermissionMatrix.java.
  - Guard tests: ArchitectureTest (no cycles, platform !-> contexts, queue transport-free), ControllerSecurityTest (every handler needs @PreAuthorize xor @PublicEndpoint), i18n/error-code sync tests.
- Frontend: colocated `*.test.ts(x)` next to source. Existing: apps/admin/src/components/{AuthFlow,HealthPanel,LoginForm}.test.tsx; packages/api-client/src/{api-client,session}.test.ts; packages/i18n/src/i18n.test.ts; packages/ui/src/fonts.test.ts.
  - admin has vitest.config.ts (jsdom + @vitejs/plugin-react + vitest.setup.ts w/ jest-dom & cleanup) and src/test-utils.tsx. Packages have NO vitest config; api-client tests opt in with `// @vitest-environment jsdom` first line.
  - kiosk/display/visitor/console/realtime-client use `vitest run --passWithNoTests`.
- Traceability: add a row to docs/traceability-matrix.md for every requirement ID a new test covers.

## Commands (backend, cwd backend/)
- Unit/slice only (no Docker):   ./gradlew test
- Integration only (Docker):     ./gradlew integrationTest
- FULL suite (unit + IT):        ./gradlew check        (check dependsOn integrationTest; this is what CI runs)
- Single unit test file/class:   ./gradlew test --tests com.qms.platform.ErrorEnvelopeTest
- Single IT class:               ./gradlew integrationTest --tests com.qms.platform.SkeletonIT   (`test --tests <IT>` fails: ITs are excluded from `test`)
- Single method:                 append `.methodName` to --tests. Add `--rerun` to force (or `cleanTest`) since Gradle caches passing results.
- Compile/typecheck:             ./gradlew compileJava compileTestJava    Jar: ./gradlew bootJar (jar task disabled; only boot jar)
- Vuln scan (CI, needs NVD_API_KEY, slow): ./gradlew dependencyCheckAnalyze
- Reports: backend/build/reports/tests/{test,integrationTest}/index.html; XML in backend/build/test-results/.

## Commands (frontend, cwd frontend/; run `pnpm install --frozen-lockfile` first if node_modules stale)
- Full suite:        pnpm test            (= pnpm -r test, every workspace package)
- Typecheck all:     pnpm typecheck       (tsc --noEmit per package)
- Build all apps:    pnpm build           (next build for apps/* only -> apps/*/out)
- Lint CSS:          pnpm lint:css        (stylelint packages/ui + apps CSS)
- Single test file:  pnpm --filter @qms/admin exec vitest run src/components/LoginForm.test.tsx
                     pnpm --filter @qms/api-client exec vitest run src/session.test.ts   (or cd into the package and `pnpm exec vitest run <file>`)
- One package:       pnpm --filter @qms/i18n test
- Dev server:        pnpm --filter @qms/admin dev

## Integration-test database (exact)
- Real PostgreSQL 14 via Testcontainers, image `postgres:14-alpine`, started by the test JVM itself. No in-memory DB, no H2, nothing to pre-start or configure, no QMS_DB_* env needed.
- Requires: a running Docker-API daemon reachable by the test JVM (here OrbStack; /var/run/docker.sock -> ~/.orbstack/run/docker.sock; ~/.testcontainers.properties has docker.client.strategy). Image must be pullable or cached (first run pulls).
- Mechanism: Spring tests `@Import(PostgresContainerConfig.class)` (@ServiceConnection wires datasource); MigrationIT uses raw @Testcontainers/@Container. Flyway migrates on context start.
- OBSERVED in this agent sandbox: `docker ps` -> "permission denied ... orbstack/run/docker.sock". Bash sandbox blocks the socket, so ./gradlew check/integrationTest cannot run ITs unless the sandbox is disabled for that command (needs user permission). `./gradlew test` works without Docker.
- RotateKeysIT does not import the container config (flyway disabled, temp key dir) but still runs under integrationTest.
- Tests set qms.security.key-dir to backend/build/test-keys (gradle systemProperty) so no keys land in source tree.

## Non-obvious
- Run the app locally: needs PostgreSQL at localhost:5432/qms (user qms, QMS_DB_PASSWORD env, defaults empty); or `QMS_DB_PASSWORD=x docker compose -f deploy/compose.yaml up --build` (port 8080; proxy serves /admin,/console,/kiosk,/display,/visitor and /api). Env vars: QMS_DB_URL/USER/PASSWORD, QMS_MIGRATE_ON_START, QMS_KEY_DIR (default ./keys, gitignored), QMS_ISSUER, QMS_REFRESH_COOKIE_SECURE (default true: login over plain http needs false), QMS_BOOTSTRAP_ADMIN_USERNAME/PASSWORD (creates first system_admin only when no users exist).
- Compose runs migrations in a separate `migrate` container (profile `migrate`); backend then has QMS_MIGRATE_ON_START=false. Dev/tests migrate on start.
- Adding an API error code touches 4 places: ErrorCode.java, docs/api/error-codes.md, packages/api-client/src/errors.ts, messages_{en,bn}.properties (`error.<code>`) + frontend packs (`errors.<code>`); tests enforce the language-pack part.
- New controller method: must carry @PreAuthorize or @PublicEndpoint("reason") or ControllerSecurityTest fails. New permission: update PermissionMatrix.java AND test/resources/permission-matrix.txt.
- New bounded-context class must not create a package cycle across com.qms.<context> (ArchitectureTest slices rule); platform may not import contexts.
- Migrations: never edit an applied V*.sql; add V<n+1>. Keep plain PostgreSQL 14 SQL.
- All user-facing strings need en and bn entries; token numbers always Western Arabic digits (Bengali locale must not localise them).
- ADR-0012 says api-client is "generated from OpenAPI", but no springdoc/OpenAPI exists yet: api-client is hand-written.
- Frontends are pure static clients: no Server Actions/route handlers/middleware/SSR; dynamic ids via query params. Refresh cookie is SameSite=Strict, so everything is one origin behind the proxy.
- Playwright (ADR-0012 E2E) is not set up yet. No frontend lint beyond stylelint; no backend formatter configured.
- frontend/apps/*/.next and out/ are build artefacts already present; `pnpm build` rewrites them. `pnpm audit --audit-level=critical` runs in CI.
- Ticket workflow: read .scratch/qms-phase1/issues/NN-*.md and DEPENDENCIES.md; update status there (git shows them modified/untracked, uncommitted).
- This directory (.scratch) is in .dockerignore and not in .gitignore.
