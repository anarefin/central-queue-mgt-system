# 52 — Scheduled report delivery

**What to build:** An admin schedules any report daily, weekly or monthly to be emailed to a named list in a chosen format.

**Blocked by:** 49 — Report exports; 40 — Email channel and appointment messages

**Status:** ready-for-agent

- [ ] Daily / weekly / monthly schedules with recipients and format (FR-RPT-005)
- [ ] Runs once cluster-wide; failures visible in the delivery log
- [ ] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
