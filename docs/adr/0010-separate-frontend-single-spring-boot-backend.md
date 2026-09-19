# 0010 — Separate frontend deployment; one Spring Boot backend

Status: Accepted · 2026-09-18

## Context
SRS v1.2 §6.1 listed a Core API, Realtime hub, Job worker and Reporting store as separate components. The launch
client is Medium tier (2 API nodes). The installer must work air-gapped and single-node, and every separate
service multiplies installer, upgrade and diagnostics effort in a single-tenant estate.

## Decision
- Frontend applications (kiosk, console, display, admin, visitor PWA) deploy separately from the backend and talk
  to it over REST for commands and queries, plus WebSocket push (§21) for live state.
- The backend is a single Spring Boot application. Bounded contexts are separate packages — configuration,
  issuance, queue, appointment, session, notification, reporting, identity, audit — with no modular-monolith
  framework. The realtime hub and job worker run inside this application.
- With more than one backend node: cross-node realtime fan-out uses PostgreSQL `LISTEN/NOTIFY` (no broker), and
  scheduled jobs run once cluster-wide via a PostgreSQL-backed lock (ShedLock).
- The reporting store is a separate schema in the same PostgreSQL database.

## Consequences
- One artefact to install, upgrade, back up and diagnose; no extra stateful component.
- Package boundaries are enforced by convention and review, not by a framework.
- If fan-out or job volume outgrows PostgreSQL, a broker can be introduced behind the same publish interface.

SRS refs: §6.1, §6.2, NFR-SCL-001, §21, §26.1.
