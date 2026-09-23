# 62 — Design system foundation: tokens, themes, app shell

**What to build:** Every QMS app shares one modern, clean design system. It has a neutral palette with one brand accent, light and dark themes, and an app shell with a sidebar and top bar. The organisation's configured brand colour and logo theme every app, not only the kiosk and display. Keyboard and assistive-technology users get visible focus, skip links, landmarks and reduced motion.

**Blocked by:** —

**Status:** done

**Styling: Tailwind CSS v4 across all five apps** (admin, console, kiosk, display, visitor) and `packages/ui`. It replaces the hand-written CSS in `packages/ui/src/styles.css`, `apps/kiosk/src/app/kiosk.css` and `apps/display/src/app/display.css`. The FR-I18N-030/031 rules still apply: logical properties only, no fixed pixel widths, no truncation.

- [x] Tailwind v4 set up in the pnpm workspace:
  - `tailwindcss` and `@tailwindcss/postcss` as dev dependencies
  - a `postcss.config.mjs` in each app with `plugins: { "@tailwindcss/postcss": {} }`
  - each app's global CSS starts with `@import "tailwindcss";` then `@import "@qms/ui/theme.css";`
  - `@source "./";` lives inside `theme.css` itself (packages/ui/src), so every app that imports it scans that directory too, regardless of which app does the importing; `node_modules` is not scanned by default
  - static export (`next build` with `output: "export"`) still works for every app. **CSS size per app** (compiled, gzip not measured): admin 13.9 kB, console 13.9 kB, display 17.2 kB, kiosk 14.6 kB, visitor 13.0 kB
- [x] Shared theme `packages/ui/src/theme.css`, the single source of design tokens:
  - raw colour values as CSS variables on `:root` (light) and their dark overrides
  - `@theme inline { … }` maps semantic Tailwind tokens to those variables, e.g. `--color-surface`, `--color-surface-muted`, `--color-fg`, `--color-fg-muted`, `--color-border`, `--color-primary`, `--color-primary-fg`, `--color-ok|warn|danger|info` (+ `-subtle`)
  - so utilities like `bg-surface`, `text-fg-muted` and `bg-primary` switch theme and brand at runtime with no `dark:` duplication
  - neutral scale (50–950), radius scale, shadow scale, type scale and font (`Noto Sans Bengali`, system-ui) defined in `@theme`
  - spacing stays Tailwind's default 4px base, used on an 8px rhythm
  - the existing `--qms-*` variable names are kept as aliases until tickets 63–65 have migrated every app, then removed in 65
- [x] Dark mode:
  - `@custom-variant dark (&:where([data-theme=dark], [data-theme=dark] *));` for the rare component that needs a `dark:` utility
  - the dark palette applies under `@media (prefers-color-scheme: dark)` guarded by `:root:not([data-theme="light"])`, and under `:root[data-theme="dark"]`
  - every text/background token pair meets WCAG AA (4.5:1 body text) in both themes, checked by a vitest test that computes contrast from the token values in `theme.css`
- [x] Logical-property guard, since Tailwind also offers physical utilities:
  - allowed: `ms-/me-/ps-/pe-/start-/end-/border-s/border-e/rounded-s/rounded-e/text-start/text-end`
  - forbidden: `ml-/mr-/pl-/pr-/left-/right-/text-left/text-right/border-l/border-r/rounded-l/rounded-r`, fixed pixel widths such as `w-[320px]`, and `truncate`/`line-clamp-*`
  - enforced by a vitest scan over `apps/*/src` and `packages/ui/src` `.tsx` files (with a short allow-list for justified cases — empty today, no violations found)
- [x] `className` composition via a small `cn()` helper (`clsx` + `tailwind-merge`) in `packages/ui`. **Choice recorded:** a plain typed `Record<Variant, string>` map per component (e.g. `BUTTON_VARIANT_CLASSES`), not `class-variance-authority` — this component set's variant lists are small and non-combinatorial (no variant×size cross-product needing `cva`'s compound-variant matching), so a plain map stayed simpler and dependency-free.
- [x] `pnpm lint:css`: stylelint keeps running over the remaining CSS files, configured to accept Tailwind at-rules (`@theme`, `@source`, `@custom-variant`, `@utility`, `@apply`). `prettier-plugin-tailwindcss` was left out: Prettier is not set up in this repo (confirmed — no config, no dependency), and the ticket says to leave it out in that case.
- [x] `@media print` rules for the kiosk slip and branding test print move to a small print layer in `theme.css` (or Tailwind `print:` utilities). Printed output is unchanged, verified by the existing token-printer tests.

- [x] `ThemeProvider` and `ThemeToggle` (system / light / dark) in `packages/ui`:
  - the choice is saved in localStorage under `qms-theme`, with every read and write wrapped in try/catch
  - the page renders correctly when storage throws
  - kiosk and display do not offer the toggle and stay on the light, high-contrast theme (no `ThemeProvider` mounted at all; `<html data-theme="light">` is fixed in the root layout)
- [x] Brand theming at runtime:
  - `applyBrand({primary_color, logo_url, org_name})` sets the raw primary variable behind `--color-primary` (so every `bg-primary`/`text-primary` utility follows it) and derives a text colour with at least 4.5:1 contrast (white or near-black) plus hover and active shades
  - an invalid or missing colour falls back to the default accent
  - unit tests cover the derivation and the fallback
- [x] Brand read path for all apps:
  - today `GET /api/v1/branding` requires `config:org_sites_zones` (`backend/.../configuration/branding/BrandingController.java`), and only devices get branding, through `/config/bootstrap`
  - added a read-only, non-sensitive `GET /api/v1/branding/theme` (`@PublicEndpoint`) returning only `org_name`, `primary_color` and `logo_url`, so login screens and the anonymous visitor page can be themed
  - rate-limited (new `PublicReadRateLimiter`, in-memory, 30/min/IP — no generic public-read rate limiter existed anywhere in this codebase to reuse, so this is a fresh, minimal, self-contained one); IT added (`BrandingAdminIT`); documented in `docs/api/error-codes.md`
  - staff apps, the visitor app, kiosk and display all call `applyBrand` on start
- [x] Components in `packages/ui/src/components.tsx`, styled only with Tailwind utilities and theme tokens (no hard-coded colours), exported from `index.ts`, each with a vitest and React Testing Library test:
  - `AppShell`:
    - sidebar nav, collapsible at narrow widths into a menu button
    - top bar slots: title, site switcher, user menu, theme toggle
    - `<main id="main">` landmark and a "Skip to content" link as the first focusable element
  - `PageHeader` (title, description, actions)
  - `Button` (primary, secondary, ghost, danger; sizes; loading state with `aria-busy`)
  - `Card` (header and actions)
  - `DataTable` (column headers, optional sort indicator with `aria-sort`, empty slot, loading skeleton rows, horizontal scroll inside the card only)
  - `Badge` (neutral, ok, warn, danger, info)
  - `EmptyState` (icon, title, body, action)
  - `Tabs` (roving tabindex, arrow keys)
  - `ConfirmDialog`:
    - built on `<dialog>`, focus trap, Esc cancels, focus returns to the trigger
    - replaces `window.confirm` everywhere in later tickets
  - `Toast` (`role="status"`, auto-dismiss, pausable)
  - `Skeleton`
- [x] `TextField` and `SelectField` keep their current API and behaviour. They are only restyled: label above, help text, error text with `role="alert"` linked by `aria-describedby`.
- [x] Accessibility baseline:
  - a consistent focus ring (`focus-visible:outline-2 outline-offset-2 outline-primary`) on every interactive component
  - motion only through `motion-safe:` utilities, so `prefers-reduced-motion: reduce` turns transitions and animations off
- [x] New visible strings (skip link, theme names, menu labels, dialog buttons) are added to both `packages/i18n/src/packs/en.json` and `bn.json`
- [x] Verification:
  - `cd frontend && pnpm typecheck && pnpm test && pnpm lint:css && pnpm build` is green (662 tests, 10 workspace packages, all 5 apps build)
  - `cd backend && ./gradlew check`: a full run (702 integration tests) had exactly one failure, `VisitorAppointmentSelfServiceIT#pastTheCutoffAVisitorIsRefusedEvenWithAReasonButStaffMayStillAct` — a pre-existing, already-documented calendar-date-sensitive flake (see ticket 61's own traceability-matrix notes), unrelated to the appointment domain this ticket never touches. The new `BrandingAdminIT` (7/7, real PostgreSQL) passed in every run — see `docs/traceability-matrix.md`'s ticket 62 section for the full account
- [x] Definition of done (SRS §27.5):
  - user-facing strings in both en and bn packs
  - the new endpoint is permission-checked (explicitly public, read-only)
  - traceability-matrix rows added (NFR-USA-003, FR-CFG-030)
