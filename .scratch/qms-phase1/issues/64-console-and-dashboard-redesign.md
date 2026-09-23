# 64 — Agent console and live dashboard redesign, pickers instead of raw IDs

**What to build:** An agent serves visitors from a focused, modern serving desk, and every F-key shortcut still behaves exactly as it does today. A supervisor filters and acts on the live dashboard by picking a site, zone, group, service, priority class or agent from lists instead of typing raw IDs. Anyone without dashboard permission is told so instead of seeing a broken page.

**Blocked by:** 62 — Design system foundation

**Status:** todo

- [x] Console is migrated to Tailwind: every `qms-*` class and inline style in `apps/console` is replaced by Tailwind utilities, semantic theme tokens and `packages/ui` components, with no hard-coded colours. A vitest scan asserts no `qms-` class names remain.
- [x] `apps/console` uses `AppShell` with a slim top bar showing counter, session state, break timer, user menu and theme toggle. There is no sidebar on the serving desk; the dashboard gets a sidebar with filters.
- [x] Serving desk (`CounterConsole.tsx`, `ServingDesk.tsx`, `OpenSessionCard.tsx`) is redesigned:
  - the current ticket is a large card with token, state `Badge`, visitor fields, purpose note and journey stops
  - the action bar shows each button with its `<kbd>` hint
  - held tickets and the call-timeout card are side panels
  - live waiting counts and estimates per service appear as compact stat tiles
- [x] **No behaviour change to shortcuts.** F2 call next, F3 re-announce, F4 serve, F5 complete, F6 miss (with the pre-no-show warning), F7 transfer, F8 hold, F9 break, F10 close all still:
  - override the browser defaults
  - do not auto-repeat
  - run one action at a time
  - are ignored while a panel is open (except F7/F9 closing their own panel)
  - existing console tests stay green, and a new test covers every F-key after the redesign
- [x] Panels (`TransferPanel`, `CallTicketPanel`, `BreakPanel`) become side drawers built on `<dialog>`. They close with Esc and return focus to the trigger.
- [x] Live dashboard (`LiveDashboard.tsx`) filters are pickers, not text fields:
  - site (sites the user can see), zone (zones of the chosen site), service group, service, priority class, and agent for availability and force actions
  - the lists come from existing list endpoints (`/sites`, `/sites/{id}/zones`, `/sites/{id}/service-groups`, `/service-groups/{id}/services`, `/priority-classes`, `/agents/availability`)
  - options load on demand and the pickers are searchable when there are more than 10 options
  - the URL query (`site_id`, zone, group, service, priority class) still round-trips, so a shared link restores the same view
- [x] Re-prioritise, force-close, set availability, staff alert and acknowledge alert keep their current API calls and validations (reprioritise stays disabled until a ticket and class are chosen; staff alert needs a message).
- [x] Client-side permission gate on `/console/dashboard/`:
  - if the signed-in principal (`/auth/me`) has neither `dashboard:view_all` nor `dashboard:view_own_groups`, show a "not permitted" `EmptyState` with a link back to the counter
  - the server's 403 remains the real control, and a 403 from `/dashboard/live` also shows the same state
- [x] Dashboard tiles use `Card` and `Badge` (escalated, SLA-breached) and have loading skeletons and empty states.
- [x] The day summary and my-feedback cards are restyled, keeping their "unavailable" states.
- [x] Tests:
  - picker loading and URL round-trip
  - permission gate for agent vs team_admin
  - every F-key
  - no text input for a raw ID remains (assert no `input` whose name ends in `_id`)
- [x] New strings in en and bn packs.
- [ ] Verification:
  - `pnpm --filter console typecheck test build` — typecheck and test both green; `build` hits the same pre-existing Turbopack sandbox port-binding failure ticket 63 already documented (confirmed unrelated to this ticket)
  - with the stack up: open a session, then F2→F4→F5, F6, F7, F8, F9, F10 using only the keyboard, in light and dark — **not performed**: no live `deploy/compose.yaml` stack or browser was available in this run; covered instead by the full automated F-key suite (`CounterConsole.test.tsx`, 89 pre-existing + 4 new tests, all green)
- [x] Definition of done (SRS §27.5): strings in en and bn; server permission checks unchanged; traceability rows for FR-AGT-*, FR-MON-* and NFR-USA-* updated
