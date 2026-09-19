-- Ticket issuance: visits, tickets, their event log, per-site sequence blocks and the idempotency store
-- (SRS §18.3, §18.4, §18.5, §20.1, FR-ISS-001, FR-QUE-070, FR-QUE-201, ADR-0001, ADR-0006, ADR-0007).
-- Columns that belong to later tickets (visitor, appointment, priority class, session binding, outcome) are added by
-- the ticket that first writes them.

-- One visitor's presence at one site on one occasion, created implicitly with the first ticket (ADR-0007).
CREATE TABLE IF NOT EXISTS visit (
    id         uuid PRIMARY KEY,
    site_id    uuid        NOT NULL REFERENCES site (id),
    started_at timestamptz NOT NULL,
    ended_at   timestamptz
);
CREATE INDEX IF NOT EXISTS visit_site_idx ON visit (site_id, started_at);

CREATE TABLE IF NOT EXISTS ticket (
    id                    uuid PRIMARY KEY,
    token_number          text        NOT NULL,
    sequence_no           bigint      NOT NULL,
    reset_key             text        NOT NULL,
    -- Denormalised at issue so later reconfiguration never rewrites history (§18.5).
    service_id            uuid        NOT NULL REFERENCES service (id),
    service_group_id      uuid        NOT NULL REFERENCES service_group (id),
    site_id               uuid        NOT NULL REFERENCES site (id),
    zone_id               uuid REFERENCES zone (id),
    visit_id              uuid        NOT NULL REFERENCES visit (id),
    predecessor_ticket_id uuid REFERENCES ticket (id),
    origin_channel        text        NOT NULL CHECK (origin_channel IN ('kiosk', 'reception', 'mobile', 'appointment_checkin')),
    state                 text        NOT NULL CHECK (state IN
        ('remote', 'waiting', 'paused', 'called', 'serving', 'held', 'completed', 'transferred', 'no_show', 'cancelled', 'forfeited')),
    issued_at             timestamptz NOT NULL,
    queued_at             timestamptz NOT NULL,
    -- Only the hash of the ticket secret is stored (§20.5); the secret itself is shown once at issue.
    secret_hash           text        NOT NULL,
    version               integer     NOT NULL DEFAULT 0
);
-- A successor ticket reuses its chain head's token number, so uniqueness applies to chain heads only (ADR-0006).
CREATE UNIQUE INDEX IF NOT EXISTS ticket_token_chain_head_uq ON ticket (site_id, reset_key, token_number) WHERE predecessor_ticket_id IS NULL;
-- The queue read path (§18.4).
CREATE INDEX IF NOT EXISTS ticket_queue_idx ON ticket (service_id, state, queued_at, id) WHERE state IN ('waiting', 'paused');
CREATE INDEX IF NOT EXISTS ticket_site_issued_idx ON ticket (site_id, issued_at);
CREATE INDEX IF NOT EXISTS ticket_visit_idx ON ticket (visit_id);

-- Every transition writes exactly one row (Invariant 3). `occurred_at` is the originating device's time and
-- `recorded_at` the server's, so a site edge node can reconcile later (ADR-0001). `seq` is per ticket and increases by
-- one, so a client can detect a gap (FR-QUE-070).
CREATE TABLE IF NOT EXISTS ticket_event (
    id          uuid PRIMARY KEY,
    ticket_id   uuid        NOT NULL REFERENCES ticket (id),
    seq         integer     NOT NULL CHECK (seq >= 1),
    event_type  text        NOT NULL,
    from_state  text,
    to_state    text,
    actor_id    uuid,
    actor_type  text        NOT NULL,
    counter_id  uuid REFERENCES counter (id),
    payload     jsonb,
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    UNIQUE (ticket_id, seq)
);
CREATE INDEX IF NOT EXISTS ticket_event_ticket_idx ON ticket_event (ticket_id, occurred_at);

-- Append-only, like the audit log (§18.4): the database refuses updates and deletes.
CREATE OR REPLACE FUNCTION ticket_event_reject_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ticket_event is append-only (% not allowed)', TG_OP USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS ticket_event_no_update_delete ON ticket_event;
CREATE TRIGGER ticket_event_no_update_delete
    BEFORE UPDATE OR DELETE ON ticket_event
    FOR EACH ROW EXECUTE FUNCTION ticket_event_reject_change();

DROP TRIGGER IF EXISTS ticket_event_no_truncate ON ticket_event;
CREATE TRIGGER ticket_event_no_truncate
    BEFORE TRUNCATE ON ticket_event
    FOR EACH STATEMENT EXECUTE FUNCTION ticket_event_reject_change();

-- A site's reserved block of sequence numbers for one numbering scope and reset period (FR-QUE-201). The core draws
-- from the current block and opens the next one when it is used up; a Phase 2 edge node draws from its own block.
CREATE TABLE IF NOT EXISTS sequence_block (
    id          uuid PRIMARY KEY,
    scope_key   text    NOT NULL,
    site_id     uuid    NOT NULL REFERENCES site (id),
    block_start bigint  NOT NULL,
    block_end   bigint  NOT NULL,
    next_value  bigint  NOT NULL,
    reset_key   text    NOT NULL,
    CHECK (block_end >= block_start)
);
CREATE UNIQUE INDEX IF NOT EXISTS sequence_block_uq ON sequence_block (scope_key, reset_key, block_start);

-- Replay cache for `Idempotency-Key` (§20.1): the same key from the same caller within 24 hours returns the stored
-- response instead of acting twice. It lives in the transaction of the action, so the two commit or roll back together.
CREATE TABLE IF NOT EXISTS idempotency_key (
    scope           text        NOT NULL,
    idem_key        text        NOT NULL,
    fingerprint     text        NOT NULL,
    response_body   text        NOT NULL,
    created_at      timestamptz NOT NULL,
    PRIMARY KEY (scope, idem_key)
);
CREATE INDEX IF NOT EXISTS idempotency_key_created_idx ON idempotency_key (created_at);
