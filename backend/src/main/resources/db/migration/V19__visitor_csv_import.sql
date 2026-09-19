-- Visitor CSV import (SRS §22.2, FR-INT-010, FR-INT-011): upserts into the same `visitor` table a walk-in's pass
-- reference already writes to (V18), keyed by external_code, so an imported visitor is found through the exact same
-- VisitorDirectory a walk-in is. `visitor_import_mapping` is the single, admin-set column mapping both a manual
-- upload and the scheduled folder pickup use; `visitor_import_run` is one CSV file's validation report, kept so a
-- scheduled run's report is still visible after the fact, when nobody was there to see it synchronously.
CREATE TABLE IF NOT EXISTS visitor_import_mapping (
    id                   boolean PRIMARY KEY DEFAULT true,
    external_code_column text        NOT NULL,
    name_column          text        NOT NULL,
    phone_column         text,
    email_column         text,
    category_column      text,
    updated_at           timestamptz NOT NULL,
    updated_by           uuid,
    CONSTRAINT visitor_import_mapping_singleton CHECK (id)
);

CREATE TABLE IF NOT EXISTS visitor_import_run (
    id             uuid PRIMARY KEY,
    source         text        NOT NULL CHECK (source IN ('manual', 'scheduled')),
    filename       text,
    triggered_by   uuid,
    started_at     timestamptz NOT NULL,
    completed_at   timestamptz NOT NULL,
    status         text        NOT NULL CHECK (status IN ('completed', 'failed')),
    total_rows     integer     NOT NULL DEFAULT 0,
    inserted_count integer     NOT NULL DEFAULT 0,
    updated_count  integer     NOT NULL DEFAULT 0,
    failed_count   integer     NOT NULL DEFAULT 0,
    errors         jsonb       NOT NULL DEFAULT '[]'
);

CREATE INDEX IF NOT EXISTS visitor_import_run_started_idx ON visitor_import_run (started_at DESC);
