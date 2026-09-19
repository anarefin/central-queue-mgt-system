# 0001 — Defer the site edge node to Phase 2

Status: Accepted · 2026-09-18

## Context
SRS v1.2 §6.3 requires a site edge node that keeps issuing, calling and completing tickets when the core is
unreachable, reconciling last-writer-wins on reconnect (FR-QUE-203) and validating JWTs offline (FR-QUE-204).
That is effectively a second queue engine, and it drags in the unresolved "offline login" question (§28.4).
The launch client is a single on-premises campus: the core server sits on the same LAN as every counter, so
internet loss never stops queuing.

## Decision
The edge node is Phase 2. Phase 1 keeps three seams so it is an added component later, not a rewrite:
- Sequence-block allocation per site (FR-QUE-201).
- Every `ticket_event` carries both device time and server-recorded time.
- The queue engine has no dependency on transport (HTTP, WebSocket) so it can be embedded in an edge process.

## Consequences
- NFR-AVL-004, FR-QUE-203 and FR-QUE-204 move to Phase 2. FR-QUE-202 (visible degradation of internet-dependent
  features) stays in Phase 1.
- §28.4 "Offline login" is closed for Phase 1.
- UAT U7 becomes "site loses internet, LAN stays up".
- A client whose sites reach the core over a WAN cannot be served in Phase 1 without accepting WAN outages.

SRS refs: §1.1, §6.3, NFR-AVL-004, FR-QUE-201..204, §27.2 U7, §28.4.
