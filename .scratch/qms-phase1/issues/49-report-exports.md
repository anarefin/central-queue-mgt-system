# 49 — Report exports

**What to build:** Any report can be exported to CSV, XLSX or PDF with a header describing it; big exports run in the background and arrive as an expiring download link; exports containing visitor personal data need a separate permission and are audited (UAT U11).

**Blocked by:** 48 — Reporting store and detailed token report; 38 — Notification pipeline and in-app channel

**Status:** ready-for-agent

- [x] CSV, XLSX (raw values) and PDF (FR-RPT-003)
- [x] Header block: name, filters, generation timestamp with timezone, user (FR-RPT-006)
- [x] Above a row threshold (default 50,000) generated async, notified with an expiring link (default 24 h); `GET /reports/jobs/{id}` (FR-RPT-004)
- [x] 1,000,000-row export within 15 minutes (NFR-PERF-006)
- [x] PII exports separately permission-gated and audited (FR-RPT-007, FR-SEC-040)
- [x] Free-text notes excluded from default exports (FR-SEC-022)
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
