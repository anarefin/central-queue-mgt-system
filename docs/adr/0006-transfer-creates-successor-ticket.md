# 0006 — A transfer creates a successor ticket with the same token number

Status: Accepted · 2026-09-18

## Context
SRS v1.2 modelled transfer as a pass-through state (`serving → transferred → waiting`) on one ticket row,
while FR-QUE-053 requires the original queue's wait to stop at transfer and new waiting to be attributed to the
receiving queue. With one `service_id` and one `wait_seconds`, that attribution would have to be recomputed from
events, which §18.5 forbids. The glossary already defines a Ticket as one visitor waiting for one service.

## Decision
A transfer closes the current ticket in terminal state `transferred` and creates a successor ticket in the
target service (optionally targeted at a counter or agent). The successor:
- links via `predecessor_ticket_id`, shares the Visit and the token number;
- inherits the priority class;
- starts its own wait, with a configurable transfer head start (default: the predecessor's accrued wait) so
  the visitor is not sent to the back.

Token uniqueness moves to the chain head: unique (site_id, reset_key, token_number) WHERE
predecessor_ticket_id IS NULL.

## Consequences
- Per-service wait and service time fall out of ordinary ticket rows; `transfer_count` is derivable and dropped.
- Reports that count "tickets issued" must count chain heads, not rows.
- The visitor-facing token number never changes across transfers.

SRS refs: FR-QUE-052, FR-QUE-053, §18.3, §18.4, §19.1, §27.2 U5.
