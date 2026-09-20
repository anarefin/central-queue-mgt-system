-- Web Push channel (ticket 39, FR-INT-040, §14.1, §18.3, NFR-SEC-013): the VAPID key pair itself lives outside
-- source control and the database (a file in qms.notification.web-push.key-dir, VapidKeyStore); this table only
-- holds what a browser's own PushSubscription hands back, keyed by its own endpoint so a device that re-subscribes
-- (a new ticket, or a re-issued key) upserts rather than accumulating duplicates. revoked_at/revoked_reason record a
-- push service's own 404/410 (subscription gone), so a later send skips it without retrying.
CREATE TABLE IF NOT EXISTS push_subscription (
    id             uuid PRIMARY KEY,
    ticket_id      uuid        NOT NULL REFERENCES ticket (id),
    visitor_id     uuid REFERENCES visitor (id),
    endpoint       text        NOT NULL,
    p256dh         text        NOT NULL,
    auth           text        NOT NULL,
    created_at     timestamptz NOT NULL,
    revoked_at     timestamptz,
    revoked_reason text
);
CREATE UNIQUE INDEX IF NOT EXISTS push_subscription_endpoint_uq ON push_subscription (endpoint);
CREATE INDEX IF NOT EXISTS push_subscription_ticket_idx ON push_subscription (ticket_id) WHERE revoked_at IS NULL;
