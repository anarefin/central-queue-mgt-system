# 63 — Admin app redesign on the app shell

**What to build:** An administrator works in a clean SaaS-style console. A left sidebar shows only the areas their role can use, and a top bar carries the site switcher, user menu and theme toggle. Every admin screen uses the shared design-system components, so lists, forms, confirmations and empty states look and behave the same everywhere.

**Blocked by:** 62 — Design system foundation

**Status:** done

- [x] Admin is migrated to Tailwind: every `qms-*` class and inline style in `apps/admin` is replaced by Tailwind utilities and `packages/ui` components. No app-local CSS remains apart from the global entry (`@import "tailwindcss"` plus the shared theme). A vitest scan asserts no `qms-` class names remain in `apps/admin/src`.
- [x] `frontend/apps/admin/src/app/layout.tsx` wraps signed-in routes in `AppShell`. Login and signed-out stay full-page, centred cards with brand logo and colour.
- [x] The sidebar is grouped (Setup, Organisation, Queue & services, Devices & displays, Visitors, Communication, Insights, System). It uses exactly the role gating in today's `apps/admin/src/app/page.tsx` links:
  - system_admin/org_admin → setup, sites, devices, catalogue, numbering, priority, breaks, visitor-import, branding, notifications, privacy, webhooks
  - system_admin only → ops
  - system/org/team admin → availability, notice-board, reports
  - team_admin → feedback
  - reception_operator → reception
- [x] The current route is marked with `aria-current="page"`.
- [x] Home `/` becomes an overview:
  - setup progress (from `GET /setup/state`) and a health panel (with its existing Retry and "unreachable" states)
  - quick-link cards for the user's areas
  - the role names
- [x] Each route below is migrated onto `PageHeader`, `Card`, `DataTable`, `Badge`, `EmptyState` and `Button`:
  - setup, sites, devices, catalogue, numbering, priority, breaks, visitor-import, branding, notifications
  - privacy, webhooks, availability, notice-board, feedback, reports, reception, ops

  Existing behaviour, validations and API calls are unchanged.
- [x] Every `window.confirm` / `confirm()` in admin is replaced by `ConfirmDialog`. This covers:
  - privacy anonymise, report schedule delete, device revoke
  - site/zone/counter deactivate, service delete
  - numbering revert, priority class deactivate
- [x] Reception desk (`ReceptionDesk.tsx`) is laid out as a two-pane working screen: issue on the left, queue on the right. The ticket secret and QR result appear in a card with a copy action.
- [x] Reports tables use `DataTable` sort indicators wired to the existing server sort. Export job state shows as `Badge`s (queued, ready, failed).
- [x] Dark mode is checked on every admin route: no hard-coded colours remain in `apps/admin`: no hex, `rgb(` or arbitrary colour utilities such as `bg-[#…]` in `.tsx`/`.css`, except brand preview swatches. Only semantic theme tokens (`bg-surface`, `text-fg-muted`, `bg-primary`, …) are used.
- [x] The existing 28 admin vitest files stay green. Update selectors only where markup changed and never weaken an assertion. Add tests for sidebar role gating (one per role) and `aria-current`.
- [x] New strings in en and bn packs.
- [ ] Verification:
  - `pnpm --filter admin typecheck test build` and `pnpm lint:css` — typecheck/test/lint:css pass; `build` fails in this sandbox with a Turbopack `creating new process / binding to a port / Operation not permitted` error while compiling `global.css`, confirmed pre-existing and unrelated to this ticket (identical failure on unmodified `HEAD`, verified via `git stash`)
  - a manual pass with the stack up (`deploy/compose.yaml`) in light and dark, at 375px, 768px and 1440px widths, with no horizontal page scroll — not run: no browser/compose stack available in this session
- [x] Definition of done (SRS §27.5): strings in en and bn; no permission change on the client or server; traceability-matrix rows updated (NFR-USA-*)
