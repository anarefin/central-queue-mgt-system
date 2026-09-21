-- Reporting store and the detailed token report (SRS §16, §18.5, FR-RPT-020, ticket 48). A separate `reporting`
-- schema of fact tables, refreshed from `ticket` and its append-only `ticket_event` log so a heavy report can never
-- slow the queue: reports run against this schema and never touch the live transactional tables (§16 "Reports run
-- against the reporting store, never against the live transactional tables"). Every dimension the detailed token
-- report names (§16.1: site, zone, service group, service, agent, counter, visitor, priority class, outcome) is
-- denormalised onto the one wide row per Ticket at refresh time, the same "copy at the moment that matters, never
-- recompute" rule `ticket` itself already follows for `service_group_id`/`site_id` (V6) and `wait_seconds`/
-- `service_seconds` (V9) — so a report reads this table alone, no join, at any scale.
CREATE SCHEMA IF NOT EXISTS reporting;

-- A stable sort key for a name that is stored per-language (jsonb): English when present, else whichever language
-- happens to be first, else empty — never null, so `ORDER BY` never has to special-case it.
CREATE OR REPLACE FUNCTION reporting.i18n_sort_key(names jsonb) RETURNS text AS $$
    SELECT CASE WHEN names IS NULL THEN NULL
                ELSE coalesce(names ->> 'en', (SELECT value FROM jsonb_each_text(names) LIMIT 1), '') END;
$$ LANGUAGE sql IMMUTABLE;

CREATE TABLE IF NOT EXISTS reporting.ticket_fact (
    ticket_id             uuid PRIMARY KEY,
    token_number          text        NOT NULL,
    site_id               uuid        NOT NULL,
    site_name             text        NOT NULL,
    zone_id               uuid,
    zone_name             text,
    service_group_id      uuid        NOT NULL,
    service_group_name    jsonb       NOT NULL,
    service_group_sort    text        NOT NULL,
    service_id            uuid        NOT NULL,
    service_name          jsonb       NOT NULL,
    service_sort          text        NOT NULL,
    agent_id              uuid,
    agent_name            text,
    counter_id            uuid,
    counter_label         text,
    visitor_id            uuid,
    visitor_code          text,
    visitor_name          text,
    visitor_category      text,
    -- Always the ticket's effective class, defaulted the same way the queue engine itself defaults it (§18.5):
    -- never null, so a filter on one priority class also reaches tickets that carry no class of their own.
    priority_class_id     uuid        NOT NULL,
    priority_class_name   jsonb       NOT NULL,
    priority_class_sort   text        NOT NULL,
    channel               text        NOT NULL,
    outcome_code_id       uuid,
    outcome_code          text,
    outcome_label         jsonb,
    outcome_sort          text,
    -- The ticket's own state at the moment of refresh (§19): a chain's earlier rows settle at `transferred`, the
    -- last at whatever terminal state (or still in progress) it has reached.
    state                 text        NOT NULL,
    predecessor_ticket_id uuid,
    -- `predecessor_ticket_id IS NULL` (ADR-0006): "tickets issued" counts these, never every row (§18.5).
    is_chain_head         boolean     NOT NULL,
    -- How many transfer hops separate this row from its chain head: 0 for the head itself, 1 for its immediate
    -- successor, and so on — the same value on every row of one chain (ADR-0006).
    transfers             integer     NOT NULL,
    issued_at             timestamptz NOT NULL,
    called_at             timestamptz,
    served_at             timestamptz,
    closed_at             timestamptz,
    wait_seconds          integer,
    service_seconds       integer,
    refreshed_at          timestamptz NOT NULL
);
-- The detailed token report's own read path: filtered by Site (and date range) first, sorted by any displayed column.
CREATE INDEX IF NOT EXISTS ticket_fact_site_issued_idx ON reporting.ticket_fact (site_id, issued_at);
CREATE INDEX IF NOT EXISTS ticket_fact_chain_head_idx ON reporting.ticket_fact (site_id, is_chain_head);

-- The refresh sweep's own watermark (a single row): every ticket with a `ticket_event` recorded after this is picked
-- up on the next tick (FR-RPT-020). A null watermark means "never refreshed": the first tick treats every ticket
-- ever issued as changed, since Invariant 3 guarantees `ticket.issued` wrote each of them at least one event.
CREATE TABLE IF NOT EXISTS reporting.refresh_watermark (
    id               boolean PRIMARY KEY DEFAULT true CHECK (id),
    last_recorded_at timestamptz
);
INSERT INTO reporting.refresh_watermark (id, last_recorded_at) VALUES (true, NULL) ON CONFLICT (id) DO NOTHING;
