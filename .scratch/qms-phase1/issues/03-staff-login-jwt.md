# 03 — Staff login with stateless JWT

**What to build:** A staff user signs in to the admin app with username and password, stays signed in through silent refresh, and is locked out after repeated failures. The backend authenticates every request from a short-lived signed JWT alone, with refresh tokens as the only persisted auth state.

**Blocked by:** 01 — Walking skeleton

**Status:** done

- [ ] Local password provider behind a pluggable identity provider interface (FR-INT-001)
- [ ] Stateless resource server with a locally configured JWT decoder; no HTTP session (API-010)
- [ ] Access tokens signed Ed25519 (or ES256) with exactly sub, jti, iss, aud, iat, exp, roles (+ sites/groups when scoped) (API-011)
- [ ] Algorithm pinned; iss/aud/exp verified; test proves `alg: none` is rejected (API-012)
- [ ] Access tokens expire within 15 minutes (API-013)
- [ ] Opaque, hashed, single-use rotating refresh tokens; reuse revokes the whole family and raises an audit event (API-014)
- [ ] Signing key pair generated per installation at first run, outside source control, rotatable with an overlap window (API-015, NFR-SEC-013)
- [ ] Browser clients keep the access token in memory only; refresh token in an HttpOnly, Secure, SameSite=Strict cookie (API-017)
- [ ] Passwords stored with Argon2id or bcrypt ≥ 12; configurable password policy (NFR-SEC-001)
- [ ] Configurable failed-login lockout (default 5 attempts / 15 minutes), logged (NFR-SEC-002)
- [ ] Idle timeout via refresh-token inactivity (defaults 30 min admin, 12 h agent) (NFR-SEC-004)
- [ ] Login carries a step-up hook so MFA can be added later without client change (NFR-SEC-003 seam)
- [ ] Authorization and X-Ticket-Secret headers, tokens and OTPs redacted from logs; authorisation denials logged (API-018)
- [ ] Admin app login and logout screens
- [ ] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
