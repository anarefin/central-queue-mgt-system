# 65 — Kiosk, display and visitor app redesign

**What to build:**
- A walk-in visitor gets a token from a large, touch-first kiosk that stays readable in high-contrast and large-text modes.
- The public display shows now-serving in a modern, legible layout that respects reduced motion, and it still announces calls when the browser has no speech synthesis.
- A remote visitor follows their ticket on a mobile-first page.

**Blocked by:** 62 — Design system foundation

**Status:** done

- [x] Kiosk, display and visitor are migrated to Tailwind:
  - `apps/kiosk/src/app/kiosk.css` and `apps/display/src/app/display.css` are deleted, their rules rebuilt as Tailwind utilities and theme tokens
  - no `qms-` class names or hard-coded colours remain in the three apps (vitest scan)
  - kiosk high-contrast and large-text modes become `data-contrast="high"` / `data-text="large"` attributes on the root, with matching token overrides in `packages/ui/src/theme.css` and `@custom-variant`s where a utility must change
  - the legacy `--qms-*` aliases and the old `packages/ui/src/styles.css` are removed once no app references them
- [x] **Kiosk** (`apps/kiosk`, `KioskFlow.tsx`):
  - touch targets of at least 64px, one decision per screen, a progress indicator for the steps
  - brand header with logo, org or site name and brand colour
  - high-contrast and large-text toggles still work and are still saved in localStorage (`qms-kiosk-accessibility`)
  - the 45 s inactivity reset (`lib/inactivity.ts`) is unchanged
- [x] Kiosk flow states keep their behaviour:
  - idle with language buttons, then group, service, identify, individual agent, custom level, confirm, issuing, error (retry reuses the same `Idempotency-Key`), result
  - the print slip (`lib/token-printer.ts`, `@media print`) and its admin-configured fields and order are unchanged
  - the print-failure QR fallback still links `/visitor/?t=<id>#s=<secret>`
  - the QR element exposes `data-qr-value` with that URL, which is needed by E2E U8 (ticket 70)
- [x] Kiosk and display each render exactly one `<main>` landmark around the flow or board.
- [x] **Display** (`apps/display`, `DisplayBoard.tsx`):
  - redesign the four layouts (now_serving_table, split_media, single_counter, summary_board) for 3–5 m viewing distance, with large tabular token numerals and clear column hierarchy
  - brand header; the stale banner (`role="status"`) is restyled but behaves the same
  - the highlight pulse for a newly called token uses `motion-safe:animate-*`, so under `prefers-reduced-motion: reduce` it is a static highlight
  - the now-serving table renders token numbers with Western Arabic digits via the shared formatter (currently it prints them raw)
- [x] Audio fallback (`lib/speaker.ts`, `lib/announcementQueue.ts`):
  - the `/sounds/*` clips the fallback expects are not shipped
  - ship a small, licence-clean chime asset in `apps/display/public/sounds/chime.*`
  - when `speechSynthesis` is unavailable or has no voice for the language, play the chime and keep the visual highlight, instead of requesting missing digit clips
  - log a single console warning per session
  - quiet hours, queue-depth limit, no overlap and no repeat after reconnect are unchanged
  - unit tests cover the fallback path
- [x] **Visitor** (`apps/visitor`, `VisitorTicketStatus.tsx`, `RemoteJoin.tsx`, `VisitorLogin.tsx`, `VisitorDashboard.tsx`):
  - mobile-first cards
  - the token and position are the hero element
  - the live / "last known as of" badge
  - wayfinding card
  - actions as full-width buttons
  - the cancel `confirm()` is replaced by `ConfirmDialog`
  - dark mode follows the system setting
  - brand themed through `GET /branding/theme` (ticket 62)
- [x] All refusal messages (remote join's ~15 reasons, OTP errors, cancel `ticket_already_called`) are shown in a consistent inline alert component.
- [x] Existing kiosk (2), display (7) and visitor (10) vitest files stay green. Add tests for:
  - the `<main>` landmark
  - `data-qr-value`
  - reduced-motion class switching
  - the audio fallback
- [x] New strings in en and bn packs.
- [ ] Verification:
  - `pnpm --filter kiosk --filter display --filter visitor typecheck test build`
  - with the stack up, pair a kiosk and a display, issue a token and watch it called on the display (speech and chime-fallback paths), in both the kiosk accessibility modes and at a 375px visitor width
- [x] Definition of done (SRS §27.5): strings in en and bn; traceability rows for FR-ISS-*, FR-DSP-*, FR-MOB-* and NFR-USA-* updated
