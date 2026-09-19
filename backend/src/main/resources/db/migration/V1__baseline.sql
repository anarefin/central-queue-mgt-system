-- Baseline. Migrations are forward-only and idempotent (FR-OPS-020): each statement is safe to re-run.
-- PostgreSQL 14+ core features only, no extensions (NFR-POR-002).
CREATE TABLE IF NOT EXISTS app_metadata (
    key        text PRIMARY KEY,
    value      text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO app_metadata (key, value) VALUES ('schema_baseline', 'qms-phase1')
ON CONFLICT (key) DO NOTHING;
