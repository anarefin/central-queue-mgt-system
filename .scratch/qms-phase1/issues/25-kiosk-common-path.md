# 25 — Kiosk: walk-in token on the common path

**What to build:** A first-time walk-in picks a language, a Service group and a Service on a touch kiosk and gets a printed token in under 30 seconds and 4 taps. If the printer fails the Ticket still exists and the screen shows the number and a QR to follow it on a phone. The kiosk resets itself after inactivity.

**Blocked by:** 24 — Device pairing and fleet management; 21 — Issuance rules; 02 — Language packs and i18n foundation

**Status:** ready-for-agent

- [x] Idle screen with language choice when more than one language is enabled; choice lasts only for the session (FR-I18N-004)
- [x] Group → Service selection with single-option steps skipped (§8.2)
- [x] Confirm and print through a single `TokenPrinter` interface, browser/OS print implementation (FR-INT-050 seam)
- [x] Printer failure still creates the Ticket and shows the Token number large with a QR linking to the visitor ticket page (FR-ISS-016)
- [x] Inactivity timeout (default 45 s) returns to idle and discards partial selection (FR-ISS-015)
- [x] Touch-only, 48×48 px targets, high-contrast/larger-text mode, WCAG 2.1 AA contrast (FR-ISS-017, NFR-USA-003)
- [x] Common path ≤ 30 s and ≤ 4 taps; idle-to-first-touch < 300 ms (NFR-USA-001, NFR-PERF-007)
- [x] Issuance P95 < 2 s to print payload (NFR-PERF-001)
- [x] Recovers after power loss with no staff action (§2.4, NFR-AVL-006)
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
