# 70 — Playwright UAT suite repaired and actually run

**What to build:** The release owner runs one command per shipped profile and sees UAT scenarios U1, U5, U6, U8, U9, U11, U12 and the vertical-specific scenarios pass against a real stack. A failure means a real product bug, not a stale selector.

**Blocked by:** 62, 63, 64, 65, 66, 67, 68, 69

**Status:** todo

Background: `frontend/e2e/` (ticket 61) was typechecked but never executed. Review found these defects:

- [ ] `support/kiosk.ts` never pairs the device. Fix: create a pairing code through the API with the fixture admin (`POST /devices/pairing-codes`, kind kiosk) and enter it through `DevicePairing`. Do the same for a display in any spec that needs one.
- [ ] Specs read `page.locator("main")`; after ticket 65 `<main>` exists, but prefer role or test-ID selectors (`data-testid` added only where no accessible name exists).
- [ ] U12 clicks the English "Get my token" after switching to Bangla. Fix: use the bn pack value (`আমার টোকেন নিন`), read from the pack JSON rather than hard-coded, or a test ID.
- [ ] U9 navigates to the non-existent `/admin/audit/` and types a class *name* into a raw-ID field. Fix: use the dashboard's priority-class picker (ticket 64) and read the audit entry via the audit report on `/admin/reports/` (or `GET /audit` for the assertion).
- [ ] U11 logs in as reception (no `reports:run_export`), uses ambiguous "From"/"To" labels, and assumes a direct download link. Fix:
  - log in as a team_admin or org_admin fixture user with `visitor_pii:view`
  - scope selectors to the detailed-token report card
  - follow the export-job flow (queued, then ready, then download) with a timeout suited to 12 months of data
- [ ] U8's QR assertion depends on `data-qr-value`, which ticket 65 adds. Make the assertion mandatory rather than best-effort.
- [ ] `support/profiles.ts` label expectations ("Customer" / "Patient" / "Producer") now pass because of ticket 69.
- [ ] `support/fixtures.ts` uses the ticket-67 seed endpoint where a profile's starter catalogue is needed, instead of hand-creating services, and keeps creating its own site, zone, counter and users.
- [ ] Vertical-specific healthcare and producer-services cross-building journey: add the missing journey-template fixture (`POST /service-groups/{id}/journey-templates`) and finish the partial scenarios.
- [ ] U2, U3, U4, U7 and U10 stay `test.fixme` with their existing comments pointing to integration tests. Do not add real waits.
- [ ] **Run it:**
  - bring up `deploy/compose.yaml` and apply each profile via the wizard (or a new reset between runs)
  - run `QMS_PROFILE=banking|healthcare|producer_services ... pnpm e2e` from `frontend/e2e`
  - save the Playwright HTML report summary (pass/fail counts per profile) in the ticket notes; artifacts stay git-ignored
- [ ] `SetupWizardIT` stability: run `./gradlew integrationTest --tests '*SetupWizardIT'` 5 times in a row. If it flakes, find the root cause and fix it (the notes on tickets 58, 59 and 60 report a flake).
- [ ] CI: add an optional, manually triggered `e2e` workflow job (`workflow_dispatch`) that runs the suite against the compose stack for the three profiles.
- [ ] `docs/traceability-matrix.md`: the ticket-61 rows for Playwright UAT and vertical scenarios move from partial to passing, citing the run.
- [ ] Verification: the three profile runs are green except the documented fixmes, and `SetupWizardIT` passes 5 out of 5.
- [ ] Definition of done (SRS §27.5): no new user-facing strings; traceability matrix updated with real run evidence
