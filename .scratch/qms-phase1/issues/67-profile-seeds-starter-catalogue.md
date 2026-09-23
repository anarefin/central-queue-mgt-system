# 67 — Vertical profile seeds starter catalogue and numbering

**What to build:** A consultant who has applied a vertical profile and created a site clicks **Seed starter catalogue** in the setup wizard. The site then gets the profile's service group, starter services (e.g. Cash deposit `A` … Remittance `F` for banking) and a group numbering rule from the profile's `numbering_defaults`, ready to issue tokens. Running it twice creates nothing new.

**Blocked by:** —

**Status:** done

Background (ticket 56): `starter_services` and `numbering_defaults` are carried in `backend/src/main/resources/profiles/*.json` and returned by the API but never created, because they are site-scoped and no site exists when a profile is applied. See the `[~]` item in `56-vertical-profiles-and-setup-wizard.md`.

- [x] Design decision recorded in the ticket notes and in an ADR if it changes a domain rule:
  - seeding is an explicit per-site action, not part of profile apply/reset
  - each profile maps to exactly one service group per site, named from the profile's `entity.service_group` label, or a `starter_group` name added to each profile JSON
  - services are created under that group
  - numbering is one `service_group`-scoped rule (prefix source `service`, the profile's padding, start and reset boundary)

  **Decision:** all four bullets adopted as stated, with one choice made on the second: the seeded group is named
  from the profile's existing `entity.service_group` label (`en`/`bn`), not a new `starter_group` JSON field. The
  label is already bilingual and already means "what this vertical calls a service group" (e.g. banking's "Branch
  function", generic's "Department"), so it doubles as the one group's own name without adding a field that could
  drift from it. No ADR: this implements ticket 56's own already-agreed, already-deferred behaviour (CFG-001/002/003
  and the profile-carries-starter-catalogue design are unchanged) — it does not introduce or revise a domain rule.
- [x] Catalogue seeding API: a public method in `com.qms.configuration.catalogue` (e.g. `CatalogueSeeding.seedStarter(siteId, groupDef, services)`), because `CreateServiceGroupRequest` and `CreateServiceRequest` are package-private. It reuses `CatalogueService.createGroup` and `createService` so all `CatalogueRules` validation and audit still apply.
- [x] Numbering is set via `NumberingService.setRule("service_group", groupId, NumberingRuleRequest)`. This respects `ScopeGuard` site scope.
- [x] Endpoint `POST /api/v1/setup/seed-catalogue` with body `{ "site_id": uuid }`:
  - requires both `config:org_sites_zones` and `config:service_catalogue`, and the site must be within the caller's scope (else `forbidden`)
  - refuses with `409 conflict/profile_not_applied` when no profile is active
  - refuses with `conflict/parent_inactive` for an inactive site
  - idempotent: an existing group, and services within it, are matched by English name and skipped, not duplicated or modified; an existing group numbering rule is left as it is
  - the response lists created and skipped items
  - audited as `profile.catalogue_seeded` with the profile id, site id and counts
- [x] Token-prefix clash: if a starter service's prefix is already used by another active service at the site, it is skipped and reported (`skipped_reason: prefix_in_use`), never silently changed.
- [x] `SetupWizardService.state()` step `services_and_numbering` still means at least one service exists; seeding makes it true. (No code change needed — `CatalogueSeeding` inserts through the same `service` table `state()`'s `count("service") > 0` already reads.)
- [x] Admin UI:
  - on the "services and numbering" step card in `SetupWizard.tsx`, a per-site **Seed starter catalogue** button showing the profile's starter list as a preview
  - the result (created and skipped) appears as a toast plus inline list
  - the button is disabled when no profile is applied or the user lacks either permission
- [x] `frontend/packages/api-client`: add `setup.seedCatalogue(siteId)` and its types.
- [x] Tests:
  - IT per profile (banking, healthcare, producer_services, government, generic) covering: seed, assert group, services with prefixes and numbering rule, issue a test token and check the token format
  - a second seed creates nothing
  - prefix clash
  - permission (org_admin with only one of the two permissions → 403)
  - `SetupWizardIT` still passes; its raw-SQL `G`/`A` prefixes must not collide with seeded data
  - vitest for the wizard button
- [x] `docs/api/error-codes.md` gains the reason `profile_not_applied`. New strings in en and bn packs.
- [x] Verification: `cd backend && ./gradlew check`; `pnpm --filter admin typecheck test`
- [x] Definition of done (SRS §27.5):
  - strings in en and bn
  - endpoint permission-checked and audited
  - the ticket-56 `[~]` row in `docs/traceability-matrix.md` becomes passing (CFG-002, §3.3/§3.4)
