-- Scheduled report delivery (ticket 52, SRS §16, FR-RPT-005): a report from the same catalogue `POST
-- /reports/{key}/run` already answers, delivered by email on a daily/weekly/monthly cadence to a named list of
-- recipients in a chosen format (CSV/XLSX/PDF, the same three FR-RPT-003 already defines). The scope this schedule
-- reaches (`allowed_sites`/`allowed_groups`) and the creating admin's own roles are captured at creation time —
-- like `reporting.export_job` (V42) already captures a requester's own reach — because a scheduled run has no
-- request and no signed-in caller of its own to derive either from; `creator_roles` lets the worker replay the
-- exact permission set (including any PII or audit-only gate) the schedule's own creator held, never more.
CREATE TABLE IF NOT EXISTS reporting.report_schedule (
    id             uuid PRIMARY KEY,
    report_key     text        NOT NULL,
    cadence        text        NOT NULL CHECK (cadence IN ('daily', 'weekly', 'monthly')),
    format         text        NOT NULL,
    recipients     text[]      NOT NULL,
    filter         jsonb       NOT NULL,
    -- The scope (FR-CFG-106) the creator's own reach resolved to at creation time, `NULL` meaning unrestricted on
    -- that dimension — baked in here rather than recomputed by the worker, which runs with no request of its own.
    allowed_sites  uuid[],
    allowed_groups uuid[],
    creator_roles  text[]      NOT NULL,
    enabled        boolean     NOT NULL DEFAULT true,
    created_by     uuid        NOT NULL REFERENCES users (id),
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    -- When this schedule next fires; advanced by one cadence period every tick, whether that tick's delivery
    -- succeeded or failed (a persistently broken schedule retries at its own cadence, not every sweep).
    next_run_at    timestamptz NOT NULL,
    last_run_at    timestamptz
);
-- The worker's own sweep (ADR-0010: runs once cluster-wide behind a JobLock advisory lock): due schedules, soonest first.
CREATE INDEX IF NOT EXISTS report_schedule_due_idx ON reporting.report_schedule (enabled, next_run_at);
-- An admin's own view of the schedules they manage, most recently created first.
CREATE INDEX IF NOT EXISTS report_schedule_created_by_idx ON reporting.report_schedule (created_by, created_at DESC);

-- One row per attempted delivery ("failures visible in the delivery log"): per recipient once the report itself
-- generated, or one row with a NULL recipient when generation failed before any send was attempted — the same
-- `notification_delivery_attempt` shape (ticket 38, V33) applied to a schedule's own run instead of a message.
CREATE TABLE IF NOT EXISTS reporting.report_schedule_delivery (
    id           uuid PRIMARY KEY,
    schedule_id  uuid        NOT NULL REFERENCES reporting.report_schedule (id),
    run_at       timestamptz NOT NULL,
    recipient    text,
    status       text        NOT NULL CHECK (status IN ('sent', 'failed')),
    row_count    bigint,
    error        text,
    attempted_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS report_schedule_delivery_schedule_idx ON reporting.report_schedule_delivery (schedule_id, attempted_at DESC);
