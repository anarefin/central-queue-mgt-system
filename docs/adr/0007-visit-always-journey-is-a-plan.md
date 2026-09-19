# 0007 — Every ticket belongs to a Visit; a Journey is an optional plan on a Visit

Status: Accepted · 2026-09-18

## Context
SRS v1.2 called a Journey both "an ordered set of services" (§1.4) and "an ordered list of tickets" (§4.2), while
FR-QUE-060 also allows unordered journeys and §18 has only a `visit` table. ADR-0006 requires successor tickets
to share a Visit, so a Visit must exist even for a single walk-in.

## Decision
- **Visit:** one visitor's presence at one Site on one occasion, created implicitly with the first ticket.
  `ticket.visit_id` is NOT NULL. The Visit scopes FR-QUE-063 (not callable at two counters) and `paused`.
- **Journey:** an optional plan on a Visit — ordered or unordered stops (services), from a template or ad hoc,
  stored as `journey_stop (visit_id, service_id, seq, ticket_id NULL)`. Tickets realise stops.

## Consequences
- Anonymous kiosk tickets from the same person cannot be linked into one Visit unless identified; that is
  accepted.
- Journey reports read `journey_stop`; planned-but-unissued stops are visible.

SRS refs: §1.4, §4.2, §4.3, FR-QUE-060..064, §18.1, §18.3.
