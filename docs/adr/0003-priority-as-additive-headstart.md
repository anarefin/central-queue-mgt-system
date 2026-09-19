# 0003 — Priority is an additive head start in minutes

Status: Accepted · 2026-09-18

## Context
SRS v1.2 FR-QUE-020 scored tickets as `wait × priority_weight + bonuses`. A priority visitor who has just
arrived scores ≈ 0 whatever their weight, so with weight 3 they overtake a 20-minute normal ticket only after
~7 minutes. UAT U6 expects them to be "served ahead". A multiplier is also hard for consultants to explain.

## Decision
The default `weighted_wait` strategy becomes:

`score = effective_wait_minutes + headstart_minutes + appointment_bonus + escalation_bonus + score_adjustment_minutes`

- `headstart_minutes` comes from the priority class (normal = 0).
- `appointment_bonus` stays in minutes (default 15).
- `escalation_bonus` still applies once real wait passes the class's maximum wait.
- `score_adjustment_minutes` is defined in ADR-0004.

`strict_priority` and `fifo` remain as named alternatives per service group.

## Consequences
- Priority takes effect on arrival; a normal ticket that has already waited longer than a head start is not
  overtaken — anti-starvation without extra machinery.
- Every waiting ticket's score grows at 1 point per minute, which makes ADR-0004 possible.
- `priority_class.weight` is replaced by `headstart_minutes`.
- Closes §28.4 "priority policy" (default is this formula; `strict_priority` is available by configuration)
  and "appointment ordering" (escalation bounds walk-in waits).

SRS refs: FR-QUE-010, FR-QUE-020..023, FR-APT-032, §18.2, §27.2 U6, §28.4.
