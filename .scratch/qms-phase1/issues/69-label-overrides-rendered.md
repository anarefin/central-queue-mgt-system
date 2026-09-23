# 69 — Label overrides render on every screen

**What to build:** A bank's screens say "Customer" and "Officer", a hospital's say "Patient", "Consultation room" and "Doctor", and an administrator can rename any of these terms per language without a code change. Every app shows the renamed term: admin, console, kiosk, display and the visitor's phone.

**Blocked by:** 63 — Admin app redesign; 64 — Console and dashboard redesign; 65 — Kiosk, display and visitor redesign

**Status:** todo

Background:
- Profiles write `label_override(key, lang, value)` rows for `entity.visitor`, `entity.visitor_id`, `entity.service_group`, `entity.counter`, `entity.agent`, `entity.category` and `entity.ticket`.
- `GET /labels?lang=` and `PUT /labels/{key}` exist (`LabelController.java`), and so do `client.labels.get/update` in `packages/api-client/src/client.ts`.
- No screen reads them. Around 470 of the ~1,456 strings in `packages/i18n/src/packs/en.json` hard-code entity words.

- [ ] `LabelsProvider` and a `useLabels()` hook in `packages/i18n` (`react.tsx`):
  - fetches labels for the resolved language and falls back to the pack's default noun when a label is missing
  - makes `t(key, params)` auto-inject `{visitor}`, `{visitor_id}`, `{service_group}`, `{counter}`, `{agent}`, `{category}`, `{ticket}` plus capitalised and plural forms (`{Visitor}`, `{visitors}`)
  - explicit params win over injected ones
  - plural and capitalised variants are either extra label keys (`entity.visitor.plural`) added to the five profile JSONs, or derived; document the choice, and remember Bangla has no case
- [ ] Pack rewrite:
  - every en and bn string whose entity noun names one of the seven concepts uses the placeholder instead; keys stay the same, and both packs keep identical key sets (the existing parity test)
  - strings where the word is not the configurable concept are left alone, e.g. "ticket secret" as a technical term, and "Token" in token-number formatting stays governed by `entity.ticket`
  - a vitest check fails if a pack value contains a bare entity word from the list outside an allow-list
- [ ] Label access for every surface:
  - staff apps: `GET /labels` (already any authenticated user)
  - kiosk and display: add `labels` to `/config/bootstrap` (`backend/.../device/BootstrapResponse.java`) and refresh it on `config_changed`
  - anonymous visitor ticket page and join page: add a read-only `@PublicEndpoint` `GET /labels/public?lang=` returning only the seven `entity.*` values (non-sensitive), with an IT
- [ ] Admin **Terminology** editor (a new card on `/admin/setup`, or an `/admin/labels` route in the sidebar under Setup):
  - a table of key × enabled language showing the pack default, the current override and an inline edit
  - a reset per cell (needs `DELETE /labels/{key}?lang=`; add it to the backend, audited as `label.reset`)
  - validation: required, at most 60 characters
  - saving audits `label.updated` (verify the existing `PUT` audits it, and add it if not)
  - a live preview sentence
- [ ] Changing a label pushes `config_changed` to devices, so kiosk and display update without a reload.
- [ ] Tests:
  - vitest: the provider injects labels, missing labels fall back, and a banking fixture renders "Customer" on the reception and console screens while a healthcare fixture renders "Patient"
  - backend ITs for the bootstrap labels, the public labels endpoint and label reset
  - the existing `SetupWizard.test.tsx` labels fixture still works
- [ ] New strings in en and bn packs.
- [ ] Verification:
  - `./gradlew check`; `pnpm typecheck test lint:css build`
  - with the stack up: apply banking and see "Customer" on kiosk, console and display; reset to healthcare and see "Patient"; edit `entity.counter` to "Desk" and see it on the display without a reload
- [ ] Definition of done (SRS §27.5): strings in en and bn; the new endpoints are permission-checked (public read-only / config) and audited; traceability rows for CFG-001 and §3.2 marked passing on the UI side
