-- Report exports (ticket 49, SRS §16, FR-RPT-003/004/006/007): CSV, XLSX and PDF export of a report from the same
-- reporting store the on-screen report already runs against (never a live transactional table). A row count under
-- the configured threshold is generated inline; over it, generated in the background and this table is the job's
-- own record: what was asked for (`filter`, `format`, the scope narrowing already resolved at request time so the
-- worker never has to recompute a caller's own reach), its progress, and where the finished file lives until its
-- link expires.
CREATE TABLE IF NOT EXISTS reporting.export_job (
    id             uuid PRIMARY KEY,
    report_key     text        NOT NULL,
    format         text        NOT NULL,
    filter         jsonb       NOT NULL,
    -- The scope (FR-CFG-106) the requester's own reach resolved to at request time, `NULL` meaning unrestricted on
    -- that dimension — baked in here rather than recomputed by the worker, which runs with no security context.
    allowed_sites  uuid[],
    allowed_groups uuid[],
    -- FR-RPT-007: every report this ticket's catalogue can export carries visitor PII, so this is always true today;
    -- kept as its own column (rather than inferred from report_key) for when a later report's catalogue entry does not.
    contains_pii   boolean     NOT NULL,
    requested_by   uuid        NOT NULL REFERENCES users (id),
    status         text        NOT NULL DEFAULT 'queued',
    row_count      bigint,
    file_path      text,
    error          text,
    requested_at   timestamptz NOT NULL,
    started_at     timestamptz,
    completed_at   timestamptz,
    -- FR-RPT-004: the download link's own expiry (default 24h after completion); NULL until the job is done.
    expires_at     timestamptz
);
-- The worker's own sweep: oldest queued job first.
CREATE INDEX IF NOT EXISTS export_job_status_idx ON reporting.export_job (status, requested_at);
-- GET /reports/jobs/{id}: a requester's own jobs, most recent first.
CREATE INDEX IF NOT EXISTS export_job_requested_by_idx ON reporting.export_job (requested_by, requested_at DESC);
