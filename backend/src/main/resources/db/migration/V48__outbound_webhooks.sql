-- Outbound webhooks (ticket 57, SRS §22.3, FR-INT-020..022): an admin subscribes an external endpoint to any §21.4
-- event type; delivery is HMAC-signed, retried with backoff, logged and replayable, and entirely decoupled from
-- queue operation (a failing endpoint can never block or slow a transition).

CREATE TABLE IF NOT EXISTS webhook_endpoint (
    id          uuid PRIMARY KEY,
    description text        NOT NULL,
    url         text        NOT NULL,
    secret      text        NOT NULL, -- application-layer encrypted (configuration.privacy.PiiCipher), never plaintext at rest
    event_types jsonb       NOT NULL, -- array of §21.4 event type wire strings this endpoint subscribes to
    active      boolean     NOT NULL DEFAULT true,
    created_by  uuid,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL
);

-- One row per distinct business event actually observed, deduplicated across the several realtime topics one
-- transition can fan out to (e.g. queue:/ticket:/counter:/zone: for a single ticket.called, queue.TicketEvents):
-- dedup_key is a hash of event type + occurred_at + data, so the same underlying event becomes one webhook_event no
-- matter how many topics carried it.
CREATE TABLE IF NOT EXISTS webhook_event (
    id          uuid PRIMARY KEY,
    dedup_key   text        NOT NULL UNIQUE,
    event_type  text        NOT NULL,
    occurred_at timestamptz NOT NULL,
    data        jsonb       NOT NULL,
    recorded_at timestamptz NOT NULL
);

-- One row per (webhook_event, webhook_endpoint) that was actively subscribed to the event's type when it arrived.
CREATE TABLE IF NOT EXISTS webhook_delivery (
    id              uuid PRIMARY KEY,
    event_id        uuid        NOT NULL REFERENCES webhook_event (id),
    endpoint_id     uuid        NOT NULL REFERENCES webhook_endpoint (id),
    event_type      text        NOT NULL,
    status          text        NOT NULL, -- queued | sent | failed
    attempt_count   int         NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    last_error      text,
    created_at      timestamptz NOT NULL,
    delivered_at    timestamptz
);
CREATE INDEX IF NOT EXISTS webhook_delivery_due_idx ON webhook_delivery (status, next_attempt_at);
CREATE INDEX IF NOT EXISTS webhook_delivery_endpoint_idx ON webhook_delivery (endpoint_id, created_at DESC);
CREATE INDEX IF NOT EXISTS webhook_delivery_event_idx ON webhook_delivery (event_id);

-- The per-attempt delivery log (FR-INT-021), including a manual replay's own attempts, continuing the same numbering.
CREATE TABLE IF NOT EXISTS webhook_delivery_attempt (
    id              uuid PRIMARY KEY,
    delivery_id     uuid        NOT NULL REFERENCES webhook_delivery (id),
    attempt_no      int         NOT NULL,
    success         boolean     NOT NULL,
    response_status int,
    error           text,
    attempted_at    timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS webhook_delivery_attempt_delivery_idx ON webhook_delivery_attempt (delivery_id, attempt_no);
