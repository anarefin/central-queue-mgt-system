# 04 — Roles, scopes, user administration and audit log

**What to build:** An Org Admin creates staff users, assigns them fixed roles scoped to sites or service groups, and disables them; every protected endpoint enforces the §5.2 permission matrix server-side; and every sensitive action lands in an append-only audit log the Org Admin can search and export.

**Blocked by:** 03 — Staff login with stateless JWT

**Status:** done

- [ ] Fixed, code-defined roles matching §5.2; only display names localisable (FR-CFG-101)
- [ ] Users created, edited and disabled; role assignments name the sites / service groups they cover, carried as token claims (§5)
- [ ] Authorisation enforced with method security at the service layer, not only controllers (FR-CFG-103, API-016)
- [ ] Client-supplied site/service-group ids intersected with the principal's scope claims (FR-CFG-106)
- [ ] Build-time test asserts every controller method is secured or explicitly public (FR-CFG-108)
- [ ] Disabling a user invalidates their refresh tokens (session and socket effects follow in ticket 12) (FR-CFG-104 partial)
- [ ] Approval requests for Team Admin team-membership and counter-allocation requests are pending rows an Org Admin approves; no temporary role elevation (FR-CFG-102, FR-CFG-107)
- [ ] Append-only audit log with actor, role, source IP/device, timestamp, entity, before/after and reason; no application path edits or deletes it (FR-SEC-040, FR-SEC-041, FR-SEC-042)
- [ ] Authentication and permission-change events audited
- [ ] Org Admin can search and export the audit log (`GET /audit`) (FR-SEC-042)
- [ ] Role assignment designed so it can later be mapped from an external group claim (FR-INT-002 seam)
- [ ] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
