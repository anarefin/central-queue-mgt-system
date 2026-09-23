# 66 — Client-side validation mirrors server rules

**What to build:** An administrator filling a form finds out immediately, next to the field, that a value is wrong instead of only after a server round trip. Examples: a site code with spaces, a notice that ends before it starts, a report range that runs backwards. The server stays authoritative, and the two sides never disagree.

**Blocked by:** 63 — Admin app redesign on the app shell

**Status:** done

- [x] A shared validators module, `frontend/packages/ui/src/validators.ts` (choice recorded: `packages/ui`, not `packages/api-client` — every form that needs wiring already imports `TextField`/`SelectField` from `@qms/ui`, so the pure validators live next to them rather than adding a new cross-package dependency for presentation-layer validation). It holds pure functions that return i18n error keys and mirror the backend:
  - Site (`backend/.../configuration/site/SiteRules.java`):
    - `code` matches `^[A-Za-z0-9_-]+$` and is required
    - `timezone` is a valid IANA zone (`Intl.supportedValuesOf('timeZone')` with a try/catch fallback to `Intl.DateTimeFormat` construction)
    - `enabled_languages` are unique and non-empty
    - `default_language` is one of `enabled_languages`
    - `display_order` is 0–100000
  - Zone:
    - `chime_volume` is 0–100
    - `max_announce_queue_depth` is 1–20
    - a quiet period needs both start and end
  - Counter: label is required and at most 30 characters
  - Notice (`configuration/notice/`): `ends_at` is strictly after `starts_at` when both are set; `sort_order` is 0–1000
  - Reports: `from` ≤ `to` for every report card that has a range (detailed-token, operational, domain, peak-hours / staffing-gap)

  Note: `chime_volume`, `max_announce_queue_depth`, the quiet period and Site's `display_order` are implemented and
  table-tested as pure functions, but no admin UI form edits those fields today (confirmed by reading `ZoneForm`
  and searching all of `apps/admin` — `ZoneForm` exposes only `name`/`floor_label`/`building_label`). Adding such
  inputs would be a UI feature outside this ticket's scope ("no opportunistic refactors"), so they are implemented
  and tested for parity but not wired to any field, ready the moment a form exposes them.
- [x] Wiring:
  - `SiteForm`, `ZoneForm`, `CounterForm`, `NoticeForm` and each `ReportsAdmin` card validate on blur and on submit
  - errors show through `TextField`/`SelectField` error props (`role="alert"`, `aria-describedby`)
  - submit is blocked while any field is invalid, and the first invalid field gets focus
  - server `validation_failed` `details.fields[]` still maps onto the same fields
- [x] Site languages stay a comma list whose order matters. Parse the list, trim entries, and flag duplicates by position.
- [x] Vitest:
  - a table-driven test per validator, covering boundary values (e.g. chime 0, 100, -1, 101; depth 0, 1, 20, 21; label 30 vs 31 chars; end == start rejected; from == to accepted)
  - one component test per wired form showing an inline error and a blocked submit
- [x] Parity check: a backend unit test or a documented table in the ticket notes lists each rule with the matching Java constant, so a later change to `SiteRules` or `CatalogueRules` is caught in review. (See `docs/traceability-matrix.md`'s "Parity table" for ticket 66, added at the end of that file.)
- [x] New error strings in en and bn packs.
- [x] Verification: `pnpm --filter admin --filter @qms/ui typecheck test` — run as `pnpm --filter admin --filter @qms/ui typecheck` then `pnpm --filter admin --filter @qms/ui test` (pnpm does not chain two script names after `--filter` in one invocation); both green (ui 171/171, admin 203/203). Also ran, beyond what this line asks: full workspace `pnpm -r typecheck` and `pnpm -r test` (all 9 active packages green) and `pnpm lint:css` (clean).
- [x] Definition of done (SRS §27.5): strings in en and bn; server validation unchanged; traceability rows for FR-CFG-* and NFR-USA-* updated
