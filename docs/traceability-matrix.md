# Traceability matrix

SRS §27.1: every requirement ID maps to at least one automated test or documented UAT step, and a requirement with no
test is treated as not implemented. Covers tickets 01–07; later tickets append rows.

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

## Ticket 05, site, zone and counter administration

`IT` = `B/configuration/site/HierarchyAdminIT`, `Rules` = `B/configuration/site/SiteRulesTest`, `UI` = `F/apps/admin/src/components/SiteAdmin.test.tsx`.

| Requirement ID | Section | Test type | Test reference | Status |
|---|---|---|---|---|
| FR-CFG-001 create, rename and soft-deactivate sites, zones, counters; history keeps resolving | §7.1 | integration, unit | `IT#aSiteCarriesTimezoneAddressAndOrderedLanguagesStoresUtcAndIsAuditedOnCreateAndRename`, `IT#aZoneNeedsAFloorLabelAndMayHaveABuildingLabelThatCanBeCleared`, `IT#aCounterHasAShortLabelAZoneAndAnOptionalLocationNote`, `IT#deactivationIsSoftSoHistoricalReferencesKeepResolving` (no delete endpoint, the database refuses a delete), `IT#anActiveZoneOrCounterNeverSitsUnderAnInactiveParent`; `UI` (adds, renames, asks before deactivating, deactivates and reactivates); `F/packages/api-client/src/api-client.test.ts` (site hierarchy paths) | passing |
| FR-CFG-002 site timezone, address, default language; stored UTC, rendered in site timezone | §7.1 | integration, unit | `IT#aSiteCarriesTimezoneAddressAndOrderedLanguagesStoresUtcAndIsAuditedOnCreateAndRename` (`created_at` is a UTC instant equal to the stored `timestamptz`), `IT#invalidTimezonesLanguagesAndCodesAreRefusedWithTheFieldNamed`; `Rules#timezoneMustBeAnIanaZoneId`; `UI` (`renders each site's timestamps in that site's own timezone`, Dhaka and New York) | passing |
| FR-I18N-002 site default language and ordered enabled languages | §17 | integration, unit | `IT#aSiteCarriesTimezoneAddressAndOrderedLanguagesStoresUtcAndIsAuditedOnCreateAndRename` (order kept and changed), `IT#invalidTimezonesLanguagesAndCodesAreRefusedWithTheFieldNamed`; `Rules#enabledLanguagesKeepTheirOrderAndMustIncludeTheDefault`, `Rules#unknownAndRepeatedLanguagesAreRefused`; `UI` (`adds a site with timezone, address, default language and ordered languages`). The resolver's site default (`SiteDefaultLanguage`) still reads configuration: which site applies to a request arrives with device and visitor context | partial |
| FR-CFG-003 zone floor label, optional building label (ADR-0002) | §7.1 | integration, unit | `IT#aZoneNeedsAFloorLabelAndMayHaveABuildingLabelThatCanBeCleared`; `UI` (`shows a site's zones with building and floor…`, `adds a zone with floor and optional building…`) | passing |
| FR-CFG-004 counter short display label, zone, optional location note | §7.1 | integration, unit | `IT#aCounterHasAShortLabelAZoneAndAnOptionalLocationNote`; `UI` (same two tests) | passing |
| NFR-SCL-002 adding a site needs no code change or restart | §23 | integration, unit | `IT#addingSitesNeedsNoCodeChangeOrRestart` (two sites, a zone under one, in the one running context); `UI` (the new site appears without a reload) | passing |
| FR-SEC-040 configuration changes audited with before and after values | §25.5 | integration | `IT#aSiteCarriesTimezoneAddressAndOrderedLanguagesStoresUtcAndIsAuditedOnCreateAndRename` (`site.created`, `site.updated`, a no-op edit writes nothing), `IT#aZoneNeeds…`, `IT#aCounterHas…`, `IT#deactivationIsSoftSoHistoricalReferencesKeepResolving` (`*.deactivated` with reason, cascaded entries say why), `IT#anActiveZoneOrCounterNeverSitsUnderAnInactiveParent` (`*.activated`) | passing |
| §5.2 `config:org_sites_zones` permission on every endpoint, server-side | §5.2 | integration | `IT#everyEndpointFollowsThePermissionMatrixForEveryRole` (every role, every endpoint, and no token), `IT#permissionsAreEnforcedAtTheServiceLayerNotOnlyTheControllers`; `B/identity/ControllerSecurityTest#everyControllerMethodIsSecuredOrExplicitlyPublic` | passing |
| FR-CFG-106 a site-scoped admin is limited to their own sites | §5.3 | integration | `IT#aSiteScopedAdminSeesAndChangesOnlyTheirOwnSites` | passing |
| Definition of done §27.5 item 3, strings in both packs | §27.5 | unit | `F/packages/i18n/src/i18n.test.ts` (`English and Bangla have exactly the same keys`); `UI` (`renders every label in Bangla…`) | passing |
| Definition of done §27.5 item 6, administrator guide | §27.5 | manual | `docs/admin-guide.md` section 7 | passing |

## Ticket 06, service catalogue administration

`IT` = `B/configuration/catalogue/CatalogueAdminIT`, `Rules` = `B/configuration/catalogue/CatalogueRulesTest`, `UI` =
`F/apps/admin/src/components/CatalogueAdmin.test.tsx`.

| Requirement ID | Section | Test type | Test reference | Status |
|---|---|---|---|---|
| Service groups per site: per-language names, token prefix, display order, active flag | §7.2, §18.2 | integration, unit | `IT#aServiceGroupCarriesPerLanguageNamesPrefixOrderAndActiveFlagAndIsAuditedWithItsTeam`, `IT#deactivatingAGroupDeactivatesItsServicesAndNothingActiveSitsUnderAnInactiveParent`; `UI` (`lists a site's service groups…`); `F/packages/api-client/src/api-client.test.ts` (catalogue paths) | passing |
| FR-CFG-010 service: per-language name, group, token prefix, expected minutes, SLA wait, channels, active flag | §7.2 | integration, unit | `IT#aServiceCarriesEveryConfiguredFieldAndIsAuditedWithBeforeAndAfterValues`, `IT#invalidServiceFieldsAreRefusedWithTheFieldNamed`; `Rules#channelsAreKnownAndUniqueAndNoneGivenMeansEveryChannel`, `Rules#prefixesCodesMinutesWeightsAndChoicesAreBounded`; `UI` (`adds a service with prefix, times, channels…`) | passing |
| FR-CFG-012 service display order and kiosk icon | §7.2 | integration, unit | `IT#aServiceCarriesEveryConfiguredFieldAndIsAuditedWithBeforeAndAfterValues` (list ordered by display order, icon set and cleared); `UI` (`adds a service with prefix, times, channels…`) | passing |
| FR-CFG-013 visitor identifier not required, optional or mandatory | §7.2 | integration, unit | `IT#aServiceCarriesEveryConfiguredFieldAndIsAuditedWithBeforeAndAfterValues` (default `not_required`, change audited), `IT#invalidServiceFieldsAreRefusedWithTheFieldNamed`; `UI` (`adds a service with prefix, times, channels…`) | passing |
| FR-CFG-014 appointment-only, walk-in-only or both | §7.2 | integration, unit | `IT#aServiceCarriesEveryConfiguredFieldAndIsAuditedWithBeforeAndAfterValues` (default `both`), `IT#invalidServiceFieldsAreRefusedWithTheFieldNamed`; `UI` (`adds a service with prefix, times, channels…`) | passing |
| FR-CFG-011 counter and service links with preference weight (1 = primary) | §7.2 | integration, unit | `IT#servicesAreLinkedToCountersWithAPreferenceWeightThatCanBeChanged`, `IT#aCounterOfAnotherSiteOrAnInactiveCounterCannotBeLinked`; `Rules#prefixesCodesMinutesWeightsAndChoicesAreBounded`; `UI` (`links counters to a service with a preference weight and unlinks them`) | passing |
| One Team per service group, with members | §3, §18.2 | integration | `IT#aServiceGroupCarriesPerLanguageNamesPrefixOrderAndActiveFlagAndIsAuditedWithItsTeam` (team created with the group, one per group), `IT#anOrgAdminChangesTheTeamDirectlyAndItIsAudited`; `UI` (`adds and removes team members directly…`) | passing |
| FR-CFG-102, FR-CFG-107 a Team Admin's membership change goes through approval and takes effect only when approved | §5.3 | integration | `IT#aTeamAdminsMembershipChangeTakesEffectOnlyOnceAnOrgAdminApprovesIt` (pending changes nothing, a Team Admin can neither change directly nor approve, reject changes nothing, approved add and remove apply and are audited with the approval id, an unappliable approval stays pending) | passing |
| FR-AGT-032 outcome codes per service, chosen on completion | §11.4 | integration, unit | `IT#outcomeCodesAreConfiguredPerServiceWithLocalisedLabelsAndOnlyEverDeactivated`; `UI` (`adds outcome codes with a label per language…`). Recording an outcome on completion arrives with the serving tickets | partial |
| FR-AGT-033 outcome codes reportable and configurable without a code change | §11.4 | integration, unit | `IT#outcomeCodesAreConfiguredPerServiceWithLocalisedLabelsAndOnlyEverDeactivated` (created, relabelled and deactivated at run time; the code never changes, nothing is deleted); `UI` (same). The reports themselves arrive with the reporting tickets | partial |
| FR-CFG-015 a service with tickets cannot be deleted, only deactivated | §7.2 | integration, unit | `IT#aServiceWithTicketsCannotBeDeletedOnlyDeactivatedAndAnUnusedOneGoesWithItsLinksAndCodes` (409 `conflict`, still resolves, deactivation allowed), `IT#theRealTicketCheckLooksAtTheTicketTableWhenThereIsOne` (the real check reads `ticket.service_id` once that table exists; no ticket table exists until issuance); `UI` (`explains that a service with tickets cannot be deleted…`, `deletes a service nobody has used…`) | passing |
| FR-I18N-010 one input per enabled language; warn, do not block, on a missing translation | §17.2 | integration, unit | `IT#aMissingTranslationWarnsButNeverBlocksAndAMissingDefaultLanguageNameIsRefused`; `Rules#namesKeepTheSiteLanguageOrderDropBlanksAndNeedOnlyTheDefaultLanguage`, `Rules#aMissingTranslationIsAWarningButAMissingDefaultLanguageNameIsAnError`; `UI` (`shows one name input per enabled language, warns on a blank translation and still saves`, `lists a site's service groups…`) | passing |
| FR-I18N-011 a missing translation falls back to the site default language | §17.2 | unit | `UI` (`lists a site's service groups…`: the Bangla name is shown in English when English is missing). The default-language name is required by the API (`IT#aMissingTranslationWarns…`) | passing |
| FR-SEC-040 catalogue changes audited with before and after values | §25.5 | integration | `IT#aServiceGroupCarries…`, `IT#aServiceCarriesEvery…`, `IT#servicesAreLinkedToCountersWith…`, `IT#outcomeCodesAreConfigured…`, `IT#deactivatingAGroupDeactivates…` (`service_group.*`, `service.*`, `outcome_code.*`, `team.*`; cascades say why) | passing |
| §5.2 `config:service_catalogue` (and `team_member:approve` for direct team changes) on every endpoint, server-side | §5.2 | integration | `IT#everyEndpointFollowsThePermissionMatrixForEveryRole`, `IT#permissionsAreEnforcedAtTheServiceLayerNotOnlyTheControllers`; `B/identity/ControllerSecurityTest#everyControllerMethodIsSecuredOrExplicitlyPublic` | passing |
| FR-CFG-106 a site-scoped admin is limited to their own sites' catalogue | §5.3 | integration | `IT#aSiteScopedAdminManagesTheCatalogueOfTheirOwnSitesOnly` | passing |
| Definition of done §27.5 item 3, strings in both packs | §27.5 | unit | `F/packages/i18n/src/i18n.test.ts` (`English and Bangla have exactly the same keys`); `UI` (`renders every label in Bangla…`) | passing |
| Definition of done §27.5 item 6, administrator guide | §27.5 | manual | `docs/admin-guide.md` section 8 | passing |

## Ticket 07, reception issues a walk-in ticket

`IT` = `B/issuance/IssuanceIT`, `Unit` = `B/issuance/TokenNumberingTest`, `UI` = `F/apps/admin/src/components/ReceptionDesk.test.tsx`, `Client` = `F/packages/api-client/src/api-client.test.ts`.

| Requirement ID | Section | Test type | Test reference | Status |
|---|---|---|---|---|
| §20.1 `POST /tickets` requires an `Idempotency-Key`; a replay within 24 hours returns the original result | §20.1 | integration, unit | `IT#issuingWithoutAnIdempotencyKeyIsRefusedAndIssuesNothing`, `IT#replayingAKeyWithinTwentyFourHoursReturnsTheOriginalTicketAndIssuesNothingMore` (same body, secret included, one ticket, one event), `IT#aKeyIsPerCallerAndCannotBeReusedForADifferentRequest`, `IT#aKeyOlderThanTwentyFourHoursIssuesAgain`, `IT#simultaneousRequestsWithOneKeyIssueExactlyOneTicket`; `Client` (`issues a ticket with an Idempotency-Key that survives a token refresh retry…`); `UI` (`keeps one idempotency key across a retry after a lost response…`) | passing |
| FR-ISS-001 sequence allocation, ticket row and queue insertion succeed or fail together | §8 | integration | `IT#aFailureAfterTheNumberIsDrawnLeavesNoTicketVisitNumberOrKeyBehind` (a failure after the number is drawn leaves no ticket, visit, sequence block or idempotency claim, and the retry gets the same number), `IT#simultaneousIssuesGetDistinctConsecutiveTokenNumbers` | passing |
| FR-QUE-201 token numbers drawn from a per-site sequence block; §4.4 default rule `{prefix}-{padded sequence}` with a daily reset key | §4.4, §6.3 | integration, unit | `IT#theDefaultRuleIsPrefixDashPaddedSequenceWithADailyResetKeyInTheSiteTimezone`, `IT#sequencesRestartForANewResetKeyAndNewBlocksOpenWhenOneIsUsedUp`, `IT#simultaneousIssuesGetDistinctConsecutiveTokenNumbers`; `Unit` (all: format, Western digits, site-local reset day). Re-requesting a block at 80% consumption, configurable rules and scheduled resets belong to ticket 08 | partial |
| §18.4, ADR-0006 partial unique on (site, reset key, token number) for chain heads | §18.4 | integration | `IT#tokenNumbersAreUniquePerSiteAndResetKeyForChainHeadsOnly` (a second head is refused, a successor may reuse the number, the same number on another day is allowed) | passing |
| ADR-0007 a Visit is created implicitly with the first ticket; `visit_id` NOT NULL | §18.3 | integration | `IT#everyTicketBelongsToAVisitCreatedWithItsFirstTicket` (row created, schema column is NOT NULL) | passing |
| §18.5 a ticket denormalises service group, site and zone at issue | §18.5 | integration | `IT#aTicketCopiesItsGroupSiteAndZoneAtIssueAndLaterReconfigurationLeavesItAlone`, `IT#aServiceNoCounterServesYetStillIssuesWithoutAZone` | passing |
| Invariant 3, FR-QUE-070, ADR-0001 exactly one `ticket_event` per transition with device time, server time and a per-ticket sequence number | §19.1, §21 | integration | `IT#issuingWritesExactlyOneEventWithDeviceAndServerTimeAndSequenceNumberOne`, `IT#ticketEventsAreAppendOnly`. Only the issue transition exists so far; later transitions extend the same writer (`TicketEvents`) | partial |
| FR-ISS-002, §20.5 response carries token number, service, zone, building, floor, position, estimate placeholder and the ticket secret, hashed at rest | §8, §20.5 | integration, unit | `IT#theResponseCarriesEverythingTheVisitorNeedsAndTheSecretOnlyOnce` (the estimate is null until wait estimation, ticket 19; only the SHA-256 of the secret is stored and it cannot be read back); `UI` (`issues a walk-in ticket and shows token, service, waiting area, position, estimate and secret…`, `says where to wait without a building`, `says so when no counter serves the service yet…`) | passing |
| §8.5 `origin_channel` is recorded; the issuance service is channel-agnostic | §8.5 | integration | `IT#theIssuanceServiceIsChannelAgnosticAndRecordsTheChannelAndActor` (kiosk and reception through one path, one sequence), `IT#theHttpEndpointIssuesAtReceptionOnly`, `IT#anInactiveServiceOrOneNotIssuedAtReceptionCannotBeIssuedFor` (the refusal reasons of ticket 21 are not built; `conflict` names one) | passing |
| §20.4 `GET /tickets/{id}` for staff and `GET /queues/{service_id}` snapshot; `GET /sites/{id}/services` | §20.4 | integration | `IT#theIssuedTicketAppearsInTheServicesQueueSnapshotInOrder`, `IT#theSiteServiceListShowsWhatReceptionCanIssueWithLiveQueueLengths`, `IT#theResponseCarriesEverythingTheVisitorNeedsAndTheSecretOnlyOnce` (`GET` has no secret) | passing |
| Reception screen: choose service, issue, show the result, see the queue | §8.3 | unit | `UI` (`lists the services reception can issue…`, `issues a walk-in ticket…`, `explains a refusal in words…`, `tells a user with no site…`, `links to the desk from the home screen for a Reception Operator only…`) | passing |
| §5.2 `ticket:issue` (Reception only) checked server-side; readers are staff | §5.2 | integration | `IT#onlyReceptionMayIssueAndEveryStaffRoleMayReadTicketsAndQueues`; `B/identity/ControllerSecurityTest#everyControllerMethodIsSecuredOrExplicitlyPublic` | passing |
| FR-CFG-106 a Reception Operator is limited to their site; a Team Admin to their service groups | §5.3 | integration | `IT#aReceptionOperatorIsLimitedToTheirOwnSite`, `IT#aTeamAdminSeesOnlyTheQueuesOfTheirServiceGroups` | passing |
| FR-SEC-040 issuing is audited | §25.5 | integration | `IT#issuingIsAuditedWithTheActorAndTheTicketDetails` (`ticket.issued`) | passing |
| FR-CFG-015 a service with tickets cannot be deleted | §7.2 | integration | `IT#aServiceThatHasTicketsCanNoLongerBeDeleted` (against the real `ticket` table now that it exists) | passing |
| FR-I18N-001, FR-I18N-020 strings in both packs; Token numbers stay Western Arabic | §17 | unit | `F/packages/i18n/src/i18n.test.ts` (`English and Bangla have exactly the same keys`); `UI` (`shows Bangla labels and keeps the token number in Western Arabic digits…`); `Unit#aTokenNumberIsPrefixSeparatorAndPaddedSequenceInWesternArabicDigits` | passing |
| FR-OPS-020 the V6 migration is forward-only and re-runnable | §26 | integration | `B/platform/MigrationIT#everyMigrationScriptCanBeReExecutedAgainstAnAlreadyMigratedDatabase`, `#onlyCorePostgresqlIsUsedNoExtensionsBeyondPlpgsql` | passing |

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
