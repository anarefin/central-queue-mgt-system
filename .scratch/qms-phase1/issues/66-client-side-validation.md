# 66 — Client-side validation mirrors server rules

**What to build:** An administrator filling a form finds out immediately, next to the field, that a value is wrong instead of only after a server round trip. Examples: a site code with spaces, a notice that ends before it starts, a report range that runs backwards. The server stays authoritative, and the two sides never disagree.

**Blocked by:** 63 — Admin app redesign on the app shell

**Status:** todo

- [ ] A shared validators module, `frontend/packages/ui/src/validators.ts` (or `packages/api-client` if it fits better; record the choice). It holds pure functions that return i18n error keys and mirror the backend:
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
- [ ] Wiring:
  - `SiteForm`, `ZoneForm`, `CounterForm`, `NoticeForm` and each `ReportsAdmin` card validate on blur and on submit
  - errors show through `TextField`/`SelectField` error props (`role="alert"`, `aria-describedby`)
  - submit is blocked while any field is invalid, and the first invalid field gets focus
  - server `validation_failed` `details.fields[]` still maps onto the same fields
- [ ] Site languages stay a comma list whose order matters. Parse the list, trim entries, and flag duplicates by position.
- [ ] Vitest:
  - a table-driven test per validator, covering boundary values (e.g. chime 0, 100, -1, 101; depth 0, 1, 20, 21; label 30 vs 31 chars; end == start rejected; from == to accepted)
  - one component test per wired form showing an inline error and a blocked submit
- [ ] Parity check: a backend unit test or a documented table in the ticket notes lists each rule with the matching Java constant, so a later change to `SiteRules` or `CatalogueRules` is caught in review.
- [ ] New error strings in en and bn packs.
- [ ] Verification: `pnpm --filter admin --filter @qms/ui typecheck test`
- [ ] Definition of done (SRS §27.5): strings in en and bn; server validation unchanged; traceability rows for FR-CFG-* and NFR-USA-* updated
