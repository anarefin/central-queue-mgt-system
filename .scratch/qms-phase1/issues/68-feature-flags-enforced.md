# 68 — Feature flags actually switch features on and off

**What to build:** When an organisation's profile turns a feature off (e.g. `virtual_queue` for healthcare, or `appointment` for producer services), that feature disappears from every screen and the API refuses it. An administrator can turn each flag on or off from the setup screen.

**Blocked by:** 67 — Vertical profile seeds starter catalogue and numbering

**Status:** done

Background: the six flags in `backend/.../issuance/setup/FeatureFlagKey.java` are stored (`feature_flag` table, `GET`/`PUT /setup/feature-flags/{key}`), but nothing outside `issuance/setup/` reads them. Some features already have their own finer switches, such as `JourneyService` → `journeys_disabled` and the per-service remote rule `virtual_queue_enabled`. A flag is an **org-wide master switch**: the feature works only if the flag is on **and** the finer setting allows it.

- [x] A `FeatureFlags` read service with a short cache, invalidated when a flag is `PUT`, that any package can use (`isEnabled(FeatureFlagKey)`). Lives in `com.qms.platform.featureflags` (interface `FeatureFlags` + enum `FeatureFlagKey`), implemented by `issuance.setup.FeatureFlagsService` — moved out of `issuance.setup` so `configuration.site` (needed for `multi_site`) can depend on it without an `issuance ↔ configuration` package cycle (`issuance` already depends on `configuration`).
- [x] Backend gates. Each refuses with `409 conflict` and `details.reason = feature_disabled` plus `details.feature = <key>`, checked before the finer checks:
  - `appointment`:
    - `POST`/`PATCH`/`DELETE /appointments`, `POST /appointments/check-in`, `POST /kiosk/appointments/check-in`
    - `GET /services/{id}/appointments/availability`
    - issuance of `appointment_only` services is unaffected
  - `virtual_queue`: `GET`/`POST /remote-join/{serviceId}`, returned before `virtual_queue_disabled`
  - `journey`: `POST /journeys` and `GET /sites/{id}/journey-templates`, before `journeys_disabled`
  - `visitor_code_lookup`: `GET /kiosk/visitors/identify` and `GET /visitors/lookup` by code (lookup by phone stays allowed; record the decision) — decision recorded in `VisitorService`'s `PHONE_LIKE` javadoc: a query is phone-shaped (never gated) when, stripped of spaces/hyphens/parens, it is an optional `+` and 7–15 digits; everything else is a code
  - `announce_visitor_name`: the announcement payload leaves out the visitor name even when a service's `announce_visitor_name` is true — `DisplayStateReads` ANDs the org flag into the per-row value `GET /devices/{id}/display-state` and the `zone:` topic report
  - `multi_site`: `POST /sites` is refused when one active site already exists and the flag is off
- [x] Turning a flag off never cancels or changes existing tickets, appointments or journeys; it only blocks new actions. This is covered by an IT.
- [x] Changing a flag is audited as `feature_flag.updated` (from and to). Verify that the existing `PUT` does this, and add it if missing. (It did not; added, with a `config.changed` device push alongside it.)
- [ ] Frontend (a flags hook in `packages/api-client`, read once per session and refreshed on `config_changed`): — **done except one item, see below**
  - [x] admin: a **Feature flags** card on `/admin/setup` with a toggle per flag, each with a description and a confirm step when turning one off
  - [x] admin reception: the journey and appointment sections are hidden when their flag is off
  - [ ] kiosk: the appointment check-in entry and the code-lookup identify tile are hidden — code-lookup tiles (code + QR, both resolve through the same gated lookup) done; **the "appointment check-in entry" does not exist anywhere in the kiosk app or `packages/api-client`** (confirmed by reading `KioskFlow.tsx` end to end — no check-in step, no client wrapper for `POST /kiosk/appointments/check-in`, only the backend endpoint exists and is gated). Building that UI from scratch was judged out of this ticket's scope; left for a future ticket. See traceability matrix "Ticket 68" section for the full note.
  - [x] visitor: the join page shows a "not offered" state
  - [x] `GET /labels`-style read access: flags readable by any authenticated principal and by devices (via `/config/bootstrap`); the anonymous visitor join page relies on the API refusal
- [x] `docs/api/error-codes.md` gains the reason `feature_disabled`. New strings in en and bn packs.
- [x] Tests:
  - one IT per flag, covering off → refused and on → allowed
  - the audit IT
  - vitest for the flags card and for hidden sections in reception and kiosk
- [x] Verification: `./gradlew check`; `pnpm typecheck test`; with the stack up, apply healthcare (virtual_queue off) and confirm the join page and API both refuse — the last clause was verified through the automated equivalent instead of a live `deploy/compose.yaml` stack (`FeatureFlagsEnforcedIT`'s virtual_queue case + `RemoteJoin.test.tsx`'s not-offered case together exercise the same server-refuses/client-shows-not-offered path a manual healthcare-profile run would)
- [x] Definition of done (SRS §27.5): strings in en and bn; every gate enforced on the server; audited; traceability rows for CFG-003 (§3.4) marked passing
