# 0008 — Ticket ownership is a session binding held through called, serving and held

Status: Accepted · 2026-09-18

## Context
SRS v1.2 Invariant 2 released the counter lock on every exit from `called` and `serving`, so a `held` ticket
had no lock. FR-CFG-105 defined an agent's "own" ticket by that lock — meaning an agent who held a ticket
could no longer be authorised to resume it.

## Decision
`ticket.counter_session_id` (the Session binding) is set when a ticket is called and kept through `called`,
`serving` and `held`. It is cleared on any return to `waiting` and on closure.
- FR-CFG-105 "own record" = bound to the agent's current open counter session.
- The call lock (FR-QUE-031) = a non-null Session binding, set with optimistic concurrency on `ticket.version`.
- Normal session close requires the agent to resolve held tickets; force-close and user disable (FR-CFG-104)
  return held tickets to `waiting` at the front (ADR-0004) and clear the binding.
- Hold limit per session: configurable, default 3.

## Consequences
- One column answers "who may act on this ticket" for every non-waiting active state.

SRS refs: FR-CFG-104, FR-CFG-105, FR-QUE-031, FR-AGT-005, FR-AGT-013, §18.3, §19.1, §19.3.
