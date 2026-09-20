# 27 — Branding and printed token template

**What to build:** An Org Admin sets the organisation's logo, colour and name once and sees them on kiosk, display, printed token, visitor app and reports; and edits the printed token layout from a fixed field set, with a preview and a test print that issues no real Ticket.

**Blocked by:** 25 — Kiosk common path

**Status:** ready-for-agent

- [x] Logo, primary colour and organisation name configurable and applied across surfaces (FR-CFG-030)
- [x] Token template editor with fields: token number, building, floor, service group, service, visitor code, name, category, counter, issue time, estimated wait, QR, notice line (FR-CFG-031)
- [x] Preview and test print from admin without issuing a Ticket (FR-CFG-032)
- [x] Printed fields respect the per-surface visitor field set defaults (FR-SEC-020)
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
