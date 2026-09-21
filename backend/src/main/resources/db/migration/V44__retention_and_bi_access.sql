-- Retention, purge and BI access (SRS §16.3, §25.4-25.5; FR-RPT-021/022/023, FR-SEC-032/043; ticket 53). Detail
-- rows in `reporting.ticket_fact` are purged or reduced to an anonymised aggregate on a per-data-class schedule;
-- the aggregate outlives detail by design (FR-RPT-022). A stable `bi` schema and a NOLOGIN reporting role let a
-- client's BI tool read the warehouse directly, without ever touching a live transactional table.

-- One row per data class (FR-SEC-032 "configurable per data class"): `ticket_detail` is the PII-bearing row in
-- `reporting.ticket_fact`, `ticket_aggregate` is what that same row becomes once anonymised, `audit` is
-- `audit_log`. `mode` only has meaning for `ticket_detail` (purge outright, or reduce to an anonymised aggregate
-- that keeps counting towards every report); the other two classes are always eventually purged outright.
CREATE TABLE IF NOT EXISTS reporting.retention_policy (
    data_class       text        PRIMARY KEY CHECK (data_class IN ('ticket_detail', 'ticket_aggregate', 'audit')),
    retention_months integer     NOT NULL CHECK (retention_months BETWEEN 1 AND 1200),
    mode             text        NOT NULL DEFAULT 'anonymize' CHECK (mode IN ('purge', 'anonymize')),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    updated_by       uuid REFERENCES users (id)
);
INSERT INTO reporting.retention_policy (data_class, retention_months, mode) VALUES
    ('ticket_detail', 24, 'anonymize'),   -- FR-RPT-021 default
    ('ticket_aggregate', 84, 'purge'),    -- FR-RPT-022 default (7 years)
    ('audit', 24, 'purge')                -- FR-SEC-043 default, independent of the two above
ON CONFLICT (data_class) DO NOTHING;

-- The purge job's own flag on a row it has already reduced to an aggregate: `ticket_aggregate`'s own retention
-- (FR-RPT-022) is measured from `issued_at` like `ticket_detail`'s, so an anonymised row simply keeps living in
-- the same table under the longer period rather than needing a second, separate aggregate table to survive into.
ALTER TABLE reporting.ticket_fact ADD COLUMN IF NOT EXISTS anonymized_at timestamptz;
CREATE INDEX IF NOT EXISTS ticket_fact_retention_idx ON reporting.ticket_fact (issued_at) WHERE anonymized_at IS NULL;

-- V3's append-only trigger rejected every UPDATE/DELETE/TRUNCATE unconditionally, with its own comment already
-- anticipating this: "The retention purge (SRS FR-SEC-043) is a separate, privileged operation added with its own
-- ticket." A session-local flag - set only inside RetentionPurgeRunner's own transaction (SET LOCAL, so it is
-- unset again the instant that transaction commits or rolls back) - lets that one targeted DELETE through; every
-- other DELETE, every UPDATE, and TRUNCATE even from that same job, is still refused exactly as before.
CREATE OR REPLACE FUNCTION audit_log_reject_change() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('qms.audit_purge', true) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'audit_log is append-only (% not allowed)', TG_OP USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

-- A stable read layer for a client's BI tool (FR-RPT-023): versioned views only (`_v1`), so a later minor release
-- adds a `_v2` rather than changing a column a warehouse job already depends on. Direct visitor identifiers
-- (`visitor_id`, `visitor_code`, `visitor_name`) are left out; `visitor_category` alone is enough for a BI
-- cross-tab, and it is also the one row shape both a still-detailed and an already-anonymised row share.
CREATE SCHEMA IF NOT EXISTS bi;

CREATE OR REPLACE VIEW bi.ticket_fact_v1 AS
SELECT ticket_id, token_number, site_id, site_name, zone_id, zone_name, service_group_id, service_group_name,
       service_id, service_name, agent_id, agent_name, counter_id, counter_label, visitor_category,
       priority_class_id, priority_class_name, channel, outcome_code, outcome_label, state, is_chain_head,
       transfers, issued_at, called_at, served_at, closed_at, wait_seconds, service_seconds, anonymized_at
FROM reporting.ticket_fact;

-- Provisionable (FR-RPT-023): a client's BI tool connects as its own LOGIN role granted membership in this one,
-- never as the application's own database user. This migration only creates the NOLOGIN group role and grants it
-- read access to the stable view layer above; a consultant provisions the client's own login on top of it, e.g.
-- `CREATE ROLE bi_client LOGIN PASSWORD '...' IN ROLE qms_bi_reader;` (documented in docs/bi-access.md), so no
-- password ever needs to live in a migration or in source control.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'qms_bi_reader') THEN
        CREATE ROLE qms_bi_reader NOLOGIN;
    END IF;
END $$;

GRANT USAGE ON SCHEMA bi TO qms_bi_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA bi TO qms_bi_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA bi GRANT SELECT ON TABLES TO qms_bi_reader;
