# 0005 — "Recall" is retired: Re-announce vs Miss

Status: Accepted · 2026-09-18

## Context
SRS v1.2 used "recall" for two different things: F3 "Recall" re-announces a called ticket (§11.2, FR-DSP-028),
while FR-QUE-050/051 "recall" re-queues a missed ticket and marks it `no_show` after a limit. One
`recall_count` column served both, so it was unclear whether pressing F3 twice made a visitor a no-show.

## Decision
- **Re-announce (F3):** replay the call. Ticket stays `called`, Session binding kept. Counted in
  `announce_count`, capped by the repeat limit (FR-DSP-028).
- **Miss (F6):** the agent declares the visitor absent. The ticket returns to `waiting` at the configured
  re-entry position (ADR-0004), `miss_count` increments and the counter is freed. When `miss_count` exceeds the
  limit (default 2), Miss yields `no_show` instead.

Agents never choose `no_show` directly; it is the result of a Miss past the limit. The word "recall" is not used.

## Consequences
- `ticket.recall_count` → `announce_count` + `miss_count`.
- Realtime `ticket.recalled` → `ticket.reannounced` and `ticket.missed`; display dedupe key becomes
  `ticket_id` + `announce_count`.
- Notification trigger "Recalled" becomes "Missed — back in queue".

SRS refs: §11.2, FR-QUE-050, FR-QUE-051, FR-DSP-028, §19.1, §21.4, FR-QUE-083, §14.2.
