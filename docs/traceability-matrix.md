# Traceability matrix

SRS §27.1: every requirement ID maps to at least one automated test or documented UAT step, and a requirement with no
test is treated as not implemented. Covers tickets 01–04; later tickets append rows.

**Test types:** unit, integration (real PostgreSQL through Testcontainers, or a full Spring context), E2E, UAT, load,
manual. **Status:** `passing` (the test ran green in the last full run), `partial` (only part of the requirement is
covered, the gap is named), `manual` (verified by hand, steps given), `configured` (wired into CI but not yet run there),
`deferred` (belongs to a later ticket).

Paths: `B` = `backend/src/test/java/com/qms`, `F` = `frontend`.

## Ticket 01, walking skeleton

| Requirement ID | Section | Test type | Test reference | Status |
|---|---|---|---|---|
| ADR-0010 / ADR-0012 package per bounded context | §6.1 | unit | `B/ArchitectureTest#packagesAreFreeOfCycles`, `#platformDoesNotDependOnAnyBoundedContext` | passing |
| ADR-0001 queue engine has no web/transport dependency | §6.1 | unit | `B/ArchitectureTest#queueEngineHasNoWebOrTransportDependency`, `#queueRuleActuallyDetectsAWebDependency` | passing |
| FR-OPS-020 forward-only, idempotent, separate migration step | §26 | integration | `B/platform/MigrationIT#migrationsRunWithoutTheApplicationAndSecondRunAppliesNothing`, `#everyMigrationScriptCanBeReExecutedAgainstAnAlreadyMigratedDatabase`, `B/identity/MigrateProfileIT` | passing |
| NFR-POR-002 PostgreSQL only, no extensions | §24 | integration | `B/platform/MigrationIT#onlyCorePostgresqlIsUsedNoExtensionsBeyondPlpgsql` | passing |
| NFR-MNT-002 liveness, readiness, dependency health | §24 | unit, integration | `B/platform/health/HealthControllerTest`, `B/platform/SkeletonIT#applicationIsReadyAndDatabaseIsUp` | passing |
| NFR-MNT-001 JSON logs with correlation id, returned as `trace_id` | §24 | unit | `B/platform/JsonLoggingTest`, `B/platform/ErrorEnvelopeTest#apiExceptionUsesEnvelopeWithCodeDetailsAndTraceId`, `#wellFormedUpstreamTraceIdIsKeptAndMaliciousOneReplaced` | passing |
| §20.1 base path `/api/v1`, UTF-8 JSON | §20.1 | unit | `B/platform/ErrorEnvelopeTest#basePathIsApiV1` | passing |
| §20.3 error envelope, closed code set | §20.3 | unit | `B/platform/ErrorEnvelopeTest` (all), `#everyCodeEmittedComesFromTheClosedSet`; codes in `docs/api/error-codes.md` | passing |
| FR-I18N-022 UTF-8 persistence and APIs | §17 | unit | `B/platform/ErrorEnvelopeTest#acceptLanguageSelectsTheMessageLanguageAndBodyIsUtf8` | passing |
| pnpm workspace, five static-export apps, runtime `config.json` | §6.2 | unit, build | `F/packages/api-client/src/api-client.test.ts` (`loadRuntimeConfig`), `pnpm build` produces `out/` with `config.json` for all five apps | passing |
| Admin app shell shows backend health through the api-client | §6.2 | unit | `F/apps/admin/src/components/HealthPanel.test.tsx` | passing |
| Single-node compose: backend, PostgreSQL, proxy on one origin | §26.1 | manual | `QMS_DB_PASSWORD=… QMS_BOOTSTRAP_ADMIN_USERNAME=admin QMS_BOOTSTRAP_ADMIN_PASSWORD=… docker compose -f deploy/compose.yaml up --build`, then the steps in the note below | manual |
| CI builds and tests both sides | §27 | CI | `.github/workflows/ci.yml` (jobs `backend`, `frontend`, `compose-smoke`) | configured |
| NFR-SEC-051 fail on unpatched critical vulnerabilities | §25 | CI | `dependencyCheckAnalyze` (CVSS 9) and `pnpm audit --audit-level=critical` in `ci.yml` | configured |
| §27.1 traceability matrix exists | §27.1 | manual | this file | passing |

## Ticket 02, language packs and i18n foundation

| Requirement ID | Section | Test type | Test reference | Status |
|---|---|---|---|---|
| FR-I18N-001 en and bn complete; more packs without a code release | §17 | unit | `B/platform/i18n/MessagesTest#shippedPacksAreCompleteAndNonBlank`, `#additionalPackLoadsFromDirectoryWithoutCodeChange`, `#clientOverrideDirectoryWinsOverShippedText`; `F/packages/i18n/src/i18n.test.ts` (shipped packs, additional packs) | passing |
| FR-I18N-003 language resolution, most specific wins | §17 | unit | `B/platform/i18n/LanguageResolverTest`; `F/packages/i18n/src/i18n.test.ts` (`resolveLanguage`); `F/apps/admin/src/components/AuthFlow.test.tsx` (user preference beats device) | passing |
| FR-I18N-011 missing translation falls back, never a raw key | §17 | unit | `B/platform/i18n/MessagesTest#missingTranslationFallsBackToSiteDefaultNotRawKey`, `#blankTranslationIsTreatedAsMissing`, `#unknownKeyStillNeverReturnsRawKeyOrEmpty`; `F/packages/i18n/src/i18n.test.ts` (translator fallback) | passing |
| FR-I18N-020 numerals per pack; Token numbers always Western Arabic | §17 | unit | `F/packages/i18n/src/i18n.test.ts` (numerals) | passing |
| FR-I18N-021 dates, times, currency by locale; 12/24-hour per site | §17 | unit | `F/packages/i18n/src/i18n.test.ts` (dates, times, currency) | passing |
| §20.3 `message_i18n` and `Accept-Language` | §20.3 | unit | `B/platform/ErrorEnvelopeTest#apiExceptionUsesEnvelopeWithCodeDetailsAndTraceId`, `#acceptLanguageSelectsTheMessageLanguageAndBodyIsUtf8`, `#unsupportedAcceptLanguageFallsBackToDefault` | passing |
| FR-I18N-031 logical CSS properties only | §17 | lint | `pnpm lint:css` (stylelint `property-disallowed-list` for left/right properties) | passing |
| FR-I18N-030 layouts tolerate 40% string expansion | §17 | lint, UAT | `pnpm lint:css` forbids fixed pixel widths, `text-overflow` and `white-space: nowrap`. **Gap:** no rendered-layout check yet; a Playwright expansion check belongs to ticket 61 | partial |
| FR-I18N-023 Bengali fonts including conjuncts | §17 | unit | `F/packages/ui/src/fonts.test.ts` (glyph coverage, conjunct GSUB features, `ক্ষ` shapes to one glyph). Thermal-printer check is an acceptance-time step (ticket 27) | passing |

## Ticket 03, staff login with stateless JWT

| Requirement ID | Section | Test type | Test reference | Status |
|---|---|---|---|---|
| FR-INT-001 pluggable identity provider, local password provider | §22.1 | integration | `B/identity/IdentityProviderIT` (a replacement provider), `B/identity/AuthFlowIT` (local provider) | passing |
| API-010 stateless resource server, no HTTP session | §20.2 | integration | `B/identity/AuthFlowIT#loginReturnsAccessTokenInTheBodyAndRefreshTokenOnlyInAnHttpOnlyStrictCookie` (no `JSESSIONID`, no session), `#protectedEndpointsRejectMissingMalformedAndForgedTokensWithTheEnvelope` | passing |
| API-011 exact claim set, ES256 | §20.2 | unit | `B/identity/JwtValidationTest#issuedTokenCarriesExactlyTheDocumentedClaims`, `#scopedRolesAddSitesAndGroupsAndNothingElse`, `#noPermissionListIsInlined` | passing |
| API-012 algorithm pinned; iss/aud/exp verified; `alg: none` rejected | §20.2 | unit | `B/identity/JwtValidationTest#algNoneIsRejected`, `#algNoneIsRejectedEvenWithTheRealKidInTheHeaderAndAnEmptySignature`, `#hmacSignedWithThePublicKeyAsSecretIsRejectedAlgorithmConfusion`, `#rs256IsRejectedBecauseTheAlgorithmIsPinned`, `#wrongIssuerWrongAudienceAndMissingAudienceAreRejected`, `#expiredTokenIsRejected`, `#tokenWithoutExpiryOrSubjectOrJtiIsRejected`, `#tamperedPayloadIsRejected` | passing |
| API-013 access tokens live at most 15 minutes | §20.2 | unit | `B/identity/JwtValidationTest#tokenLivesNoLongerThanFifteenMinutesAndEachHasItsOwnJti`, `#configuringALongerLifetimeIsRefusedAtStartup` | passing |
| API-014 opaque, hashed, single-use rotating refresh tokens; reuse revokes the family | §20.2 | integration | `B/identity/AuthFlowIT#refreshTokensArePersistedOnlyAsHashes`, `#refreshRotatesTheTokenAndIssuesAWorkingAccessToken`, `#reusingARefreshTokenRevokesTheWholeFamilyAndRaisesAnAuditEvent`, `#twoSimultaneousRefreshesOfTheSameTokenLeaveNothingUsable` | passing |
| API-015 per-installation key, outside source control, rotatable with overlap | §20.2 | unit, integration | `B/identity/JwtValidationTest#keyPairIsGeneratedOnFirstRunAndReusedAfterARestart`, `#twoInstallationsGetDifferentKeys`, `#rotationOverlapsOldTokensStayValidUntilTheWindowEnds`, `#aSecondProcessSeesARotationWithoutRestarting`; `B/identity/RotateKeysIT` | passing |
| NFR-SEC-013 secrets from environment, never in source or DB | §25 | unit | `B/identity/JwtValidationTest#privateKeyFilesAreOwnerOnly`; `deploy/compose.yaml` requires `QMS_DB_PASSWORD`; `.gitignore` excludes `keys/` | passing |
| API-017 access token in memory only; refresh in HttpOnly Secure SameSite=Strict cookie | §20.2 | integration, unit | `B/identity/AuthFlowIT#loginReturnsAccessTokenInTheBodyAndRefreshTokenOnlyInAnHttpOnlyStrictCookie`; `F/packages/api-client/src/session.test.ts#logs in, keeps the token in memory only…`; `F/apps/admin/src/components/LoginForm.test.tsx` (no web storage, no script-set cookie). Mobile keystore and kiosk file storage arrive with those clients | partial |
| NFR-SEC-001 bcrypt cost ≥ 12, configurable password policy | §25 | unit, integration | `B/identity/PasswordPolicyTest`, `B/identity/JwtValidationTest#configuringALongerLifetimeIsRefusedAtStartup` (startup limits), `B/identity/AuthFlowIT#changingPasswordEnforcesPolicyAndHistoryThenEndsAllSessions`. Password expiry only flags `password_expired` at sign-in; a forced-change screen is not built | partial |
| NFR-SEC-002 configurable lockout, logged | §25 | integration | `B/identity/AuthFlowIT#fiveFailuresLockTheAccountEvenAgainstTheCorrectPasswordAndItIsLoggedAndAudited`, `#theLockExpiresAfterFifteenMinutesAndACorrectPasswordThenWorks`, `#aSuccessfulLoginResetsTheFailureCount`, `B/identity/LockoutConfigIT` | passing |
| NFR-SEC-004 idle timeout via refresh-token inactivity | §25 | integration | `B/identity/AuthFlowIT#adminRolesIdleOutAfterThirtyMinutesAndAgentConsolesAfterTwelveHours`, `#aUserWithSeveralRolesGetsTheShortestIdleTimeout` | passing |
| NFR-SEC-003 step-up hook (MFA seam) | §25 | integration | `B/identity/StepUpIT` | passing |
| API-018 headers, tokens, OTPs redacted; denials logged | §20.2 | unit, integration | `B/platform/LogRedactionTest`, `B/identity/AuthFlowIT#tokensAndTicketSecretsAreRedactedEvenIfSomeoneLogsThem`, `#authorizationDenialsAreLoggedWithoutTheTokenAndUseTheForbiddenEnvelope` | passing |
| Admin app login and logout screens | §6.2 | unit | `F/apps/admin/src/components/LoginForm.test.tsx`, `F/apps/admin/src/components/AuthFlow.test.tsx` | passing |

## Ticket 04, roles, scopes, user administration and audit log

| Requirement ID | Section | Test type | Test reference | Status |
|---|---|---|---|---|
| FR-CFG-101 fixed code-defined roles; only display names localisable | §5.3 | unit | `B/platform/security/PermissionMatrixTest#everyCellMatchesTheSrsMatrix`, `#everyPermissionAppearsInTheSrsMatrixExactlyOnce`, `#roleWireNamesAreStableAndRoundTrip`; `F/packages/i18n/src/i18n.test.ts` (role names in both packs) | passing |
| §5.2 permission matrix enforced per role | §5.2 | integration | `B/identity/AdminApiIT#userAdministrationFollowsThePermissionMatrixForEveryRole`, `#onlyOrgAdminAndSystemAdministratorMayReadTheAuditLog` | passing |
| FR-CFG-102 Team Admin actions need Org Admin approval | §5.3 | integration | `B/identity/AdminApiIT#aTeamAdminRequestsAndAnOrgAdminDecidesWithNoRoleElevationAndNothingTakingEffectEarly`, `#rejectionCarriesAReasonAndCounterAllocationsFollowTheSameFlow` | passing |
| FR-CFG-103 / API-016 permissions enforced server-side, at the service layer | §5.3, §20.2 | integration | `B/identity/AdminApiIT#permissionsAreEnforcedAtTheServiceLayerNotOnlyTheControllers` | passing |
| FR-CFG-104 disabling a user (partial: refresh tokens, `principal.changed`) | §5.3 | integration | `B/identity/AdminApiIT#disablingAUserEndsTheirSessionsAuditsItAndRaisesPrincipalChanged`, `#enablingRestoresSignInAndYouCannotDisableYourselfOrASystemAdministratorAsAnOrgAdmin`. Closing the counter session and re-queueing tickets is ticket 12 | partial |
| FR-CFG-105 own-record (`S`) checks | §5.3 | unit | `B/platform/security/PermissionMatrixTest#authoritiesAreDerivedFromRolesOnlyAndAllowedBeatsOwn` (the `:own` authority). The object-level check needs tickets and sessions (tickets 10, 18) | deferred |
| FR-CFG-106 client-supplied scope ids intersected with claims | §5.3 | unit, integration | `B/platform/security/ScopeTest`, `B/identity/AssignmentGuardTest`, `B/identity/AdminApiIT#nobodyGrantsMoreThanTheyHold`, `#aRequestForAGroupOutsideTheCallersScopeIsForbiddenAndOtherRolesCannotRequest`, `#approvalsForOneScopedApproverStayInsideItsGroups` | passing |
| FR-CFG-107 approval is a pending row, never a role elevation | §5.3 | integration | `B/identity/AdminApiIT#aTeamAdminRequestsAndAnOrgAdminDecidesWithNoRoleElevationAndNothingTakingEffectEarly` | passing |
| FR-CFG-108 every controller method secured or explicitly public | §5.3 | unit | `B/identity/ControllerSecurityTest#everyControllerMethodIsSecuredOrExplicitlyPublic`, `#theRuleFlagsAnUnsecuredMethodAndAContradiction`, `#thePermitAllListAndThePublicEndpointMarkersDescribeTheSameEndpoints` | passing |
| FR-SEC-040 append-only log of authentication and permission changes | §25.5 | unit, integration | `B/audit/AuditLogIT#theDatabaseRefusesUpdateDeleteAndTruncate`, `#noApplicationClassExposesAWayToEditOrDeleteEntries`; events asserted in `B/identity/AuthFlowIT` and `B/identity/AdminApiIT` | passing |
| FR-SEC-041 actor, role, source, timestamp, entity, before/after, reason | §25.5 | integration | `B/audit/AuditLogIT#recordsWhoWhatWhereAndWhy`, `#takesActorSourceAddressAndDeviceFromTheCurrentRequestAndToken`, `#anEntryWithNoActorIsAllowedForFailedSignInsByUnknownUsers`, `#secretsInBeforeAndAfterAreNeverStored`; `B/audit/AuditRedactionTest` | passing |
| FR-SEC-042 searchable and exportable by Org Admin; not editable | §25.5 | integration | `B/audit/AuditLogIT#searchFiltersByActorActionEntityAndTimeRange`, `#cursorPaginationVisitsEveryRowOnceEvenWithIdenticalTimestamps`; `B/audit/AuditCursorTest`, `B/audit/AuditCsvTest`; `B/identity/AdminApiIT#auditSearchFiltersAndPaginatesByCursor`, `#badAuditQueryParametersAreValidationErrors`, `#csvExportIsSpreadsheetSafeAndItselfAudited` | passing |
| FR-INT-002 role assignment mappable from an external group claim (seam) | §22.1 | integration | `B/identity/AdminApiIT#externalGroupMappingOwnsItsOwnAssignmentsAndNeverTouchesManualOnes`; interface `RoleMapper` | passing |
| Definition of done §27.5 item 6, administrator guide | §27.5 | manual | `docs/admin-guide.md` | passing |

## Notes

- **Compose.** Verified by hand on 2026-09-19 with OrbStack Docker, from a clean build: `migrate` exited 0, then Postgres,
  backend and proxy reported healthy. Through the proxy on one origin: `/api/v1/health/dependencies` reported the database
  up; `/admin/login/`, `/console/`, `/kiosk/`, `/display/`, `/visitor/` and `/admin/config.json` served; `/` redirected
  to `/visitor/` on the same port; an unauthenticated `/users` gave `unauthenticated` and a bad token `token_invalid`; the
  bootstrap administrator signed in, the refresh cookie was HttpOnly and rotated, `/auth/me` returned `system_admin`,
  `/audit` listed the sign-in events, a replayed refresh token was refused and logged as JSON with a `trace_id`, and no
  token or password appeared in the container logs. Not checked: the Admin screens in a real browser (their behaviour is
  covered by the Vitest suites), and CI `compose-smoke`, which has not run yet. The manual run found and fixed an nginx
  redirect that dropped a non-80 published port.
- **Requirements this build interprets** where the SRS is silent are listed with the code that decides them: role and
  scope escalation (`AssignmentGuard`), idle timeout for Team Admin, Reception and multi-role users (`IdlePolicy`), lockout
  scope (per account), password policy defaults (`SecurityProperties.PasswordRules`), token endpoints and cookie
  attributes (`AuthController`), the `audit_log` additions `device`, `reason`, `trace_id` and a nullable `actor_id`.
