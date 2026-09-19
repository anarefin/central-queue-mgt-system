# 38 — Notification pipeline and in-app channel

**What to build:** Queue and appointment events trigger notifications asynchronously through pluggable channels — starting with in-app realtime and staff alerts — using editable per-language templates, with throttling, quiet hours, retries, fallback, opt-out and a delivery log admins can inspect.

**Blocked by:** 11 — Realtime hub; 02 — Language packs and i18n foundation

**Status:** ready-for-agent

- [ ] `NotificationChannel` interface with in-app realtime and staff alert as first adapters; new channels register without changing triggers, templates or callers (FR-NTF-005, FR-INT-040)
- [ ] Trigger catalogue §14.2 with defaults; each trigger enableable per Site and Service (FR-NTF-010)
- [ ] Sending is asynchronous in the job worker and never blocks a queue operation (FR-NTF-003)
- [ ] Per-trigger channel preference order with fallback (FR-NTF-001); retries with exponential backoff then next channel (FR-NTF-033)
- [ ] Templates per trigger × channel × language with fixed variables, preview, unknown-variable rejection at save (FR-NTF-020, FR-NTF-021)
- [ ] Visitor's preferred language, falling back to site default (FR-NTF-022)
- [ ] Throttling per ticket (default 4) and per day (default 10) (FR-NTF-030)
- [ ] Quiet hours per Site suppress non-urgent messages with no later batch (FR-NTF-031)
- [ ] No sensitive fields; service name inclusion is a per-Service flag (FR-NTF-034)
- [ ] Opt-out of non-essential notifications persists across visits; consent recorded with timestamp and text version (FR-NTF-035, FR-SEC-030)
- [ ] Delivery attempts, provider response and status per message, admin view filterable by ticket, visitor, status (FR-NTF-032)
- [ ] SMS/native push remain Phase 2; Phase 1 visitor channels limited to Web Push, in-app and email (FR-NTF-004)
- [ ] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
