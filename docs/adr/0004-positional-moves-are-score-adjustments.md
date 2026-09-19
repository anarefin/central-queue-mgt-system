# 0004 — Positional moves are score adjustments; `queued_at` is never rewritten

Status: Accepted · 2026-09-18

## Context
Several requirements move a ticket by "places": visitor delay (FR-MOB-031, back 3), remote forfeit (FR-MOB-022,
back 5), Miss re-entry (FR-QUE-051: front / after N / back) and call timeout (FR-QUE-032, original wait
preserved). Ordering is computed from a score, so "places" have no storage. Rebasing `queued_at` would break
"original wait preserved" and make wait KPIs understate real waiting.

## Decision
The ticket carries a signed `score_adjustment_minutes`. At the moment of a positional move the engine sets it so
the ticket lands at the target position (just behind the ticket N places back, at the front, or at the back).
Because every waiting score grows at the same rate (ADR-0003), the ticket keeps that relative position.
`queued_at` and `wait_seconds` are never rewritten. Max-wait escalation still applies on real wait and overrides
the adjustment. Each move writes a `ticket_event` carrying the adjustment applied.

## Consequences
- Wait KPIs, SLA attainment and "original wait preserved" stay truthful.
- A ticket moved back that later breaches its max wait is escalated anyway — the hard cap stays hard.
- The dry-run endpoint (FR-QUE-023) shows the adjustment as its own term.

SRS refs: FR-QUE-032, FR-QUE-051, FR-MOB-022, FR-MOB-031, FR-QUE-023, §18.3.
