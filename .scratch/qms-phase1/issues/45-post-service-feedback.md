# 45 — Post-service feedback

**What to build:** After their Ticket is completed, a visitor can rate the service 1–5 and leave a comment from the ticket page; feedback is stored against the Ticket and Agent and is never shown to the Agent individually without Team Admin approval.

**Blocked by:** 37 — Visitor ticket page (PWA)

**Status:** ready-for-agent

- [x] Optional 1–5 rating and comment after completion, stored against Ticket and Agent (FR-MOB-033)
- [x] Individual comments visible to the Agent only after Team Admin approval (FR-MOB-033)
- [x] Feedback-request trigger available (default off) (§14.2)
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
