# 0009 — Realtime sockets re-authenticate before expiry and react to a revocation event

Status: Accepted · 2026-09-18

## Context
FR-QUE-080 requires permission revocation to drop affected subscriptions within 30 s. A WebSocket is
authenticated once at connect, so a socket opened with a JWT would keep streaming (including visitor PII to
consoles) after the token expired or the user was disabled. REST, by contrast, accepts up to 15 minutes of
revocation latency (API-013) and must stay stateless (API-010).

## Decision
- The hub closes a socket at the token's `exp` unless the client first sends a `reauth` frame carrying a fresh
  access token.
- Disabling a user, changing their roles or scopes, or revoking a device publishes an internal
  `principal.changed(sub)` event. The hub drops that subject's sockets immediately; the client reconnects with a
  fresh token and topics are re-authorised against the new claims.

## Consequences
- REST stays purely stateless; only the hub, which holds connection state anyway, acts on revocation.
- Across nodes, `principal.changed` travels over the same fan-out as queue events (ADR-0010).

SRS refs: §21.1, FR-QUE-080, FR-CFG-104, FR-DSP-013, API-010, API-013.
