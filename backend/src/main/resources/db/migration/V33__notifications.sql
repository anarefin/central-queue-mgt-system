-- Notification pipeline and its in-app channel (ticket 38, SRS §14, §22.5, FR-NTF-*, FR-INT-040, FR-SEC-030). Phase 1
-- registers two adapters, in-app realtime and staff alert; Web Push (ticket 39) and email (ticket 40) add their own
-- subscription/config tables when they register, with no change to the tables below (FR-NTF-005).

-- A visitor's own language preference (FR-NTF-022); messages fall back to the Site's default when this is null.
ALTER TABLE visitor ADD COLUMN IF NOT EXISTS preferred_language text;

-- Per-site quiet hours (FR-NTF-031). Null start means no quiet hours configured. A window that wraps past midnight
-- (start > end, e.g. 22:00-06:00) is honoured by the service that reads it, not by a CHECK: both orderings are
-- valid site-local windows.
ALTER TABLE site ADD COLUMN IF NOT EXISTS quiet_hours_start time;
ALTER TABLE site ADD COLUMN IF NOT EXISTS quiet_hours_end time;

-- Whether a trigger's message may name the Service, or only its Service group and token (FR-NTF-034): a per-service
-- flag, on by default, so a medical client can suppress it.
ALTER TABLE service ADD COLUMN IF NOT EXISTS include_service_name_in_notifications boolean NOT NULL DEFAULT true;

-- Whether a trigger fires at all for a Site, or for one Service under it (FR-NTF-010, §14.2): service_id null is the
-- site-wide row; a service-level row overrides it for that one Service. channel_order null means "use the trigger
-- catalogue's own default order" (FR-NTF-001); given, it replaces it for this scope.
CREATE TABLE IF NOT EXISTS notification_trigger_setting (
    id            uuid PRIMARY KEY,
    site_id       uuid        NOT NULL REFERENCES site (id),
    service_id    uuid REFERENCES service (id),
    trigger_key   text        NOT NULL,
    enabled       boolean     NOT NULL,
    channel_order jsonb,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    updated_by    uuid REFERENCES users (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS notification_trigger_setting_site_uq
    ON notification_trigger_setting (site_id, trigger_key) WHERE service_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS notification_trigger_setting_service_uq
    ON notification_trigger_setting (site_id, service_id, trigger_key) WHERE service_id IS NOT NULL;

-- One editable template per trigger x channel x language (FR-NTF-020): {{variable}} placeholders, validated against
-- the trigger's own fixed variable set at save time (FR-NTF-021).
CREATE TABLE IF NOT EXISTS notification_template (
    id          uuid PRIMARY KEY,
    trigger_key text        NOT NULL,
    channel     text        NOT NULL,
    language    text        NOT NULL,
    subject     text,
    body        text        NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    updated_by  uuid REFERENCES users (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS notification_template_uq ON notification_template (trigger_key, channel, language);

-- Consent for notifications, recorded per visitor with a timestamp and the version of the consent text shown
-- (FR-SEC-030). Keyed on the visitor, not the ticket, so an opt-out persists across visits (FR-NTF-035).
CREATE TABLE IF NOT EXISTS notification_consent (
    visitor_id           uuid PRIMARY KEY REFERENCES visitor (id),
    opted_out            boolean NOT NULL DEFAULT false,
    consent_text_version text,
    recorded_at          timestamptz
);

-- One message: queued synchronously by the trigger (a single fast insert, never the send itself, FR-NTF-003) and
-- sent by the job worker. variables is the fixed snapshot of substitution values taken when the trigger fired, so a
-- retry or a fallback to the next channel re-renders against the moment it happened, not against however things
-- have changed since. channel_order/channel_index carry the fallback plan (FR-NTF-001, FR-NTF-033); status moves
-- queued -> sent, or queued -> failed once every channel in the order is exhausted (queued also covers "waiting to
-- retry", via next_attempt_at) -- or straight to suppressed, which is terminal and never retried.
CREATE TABLE IF NOT EXISTS notification_message (
    id                uuid PRIMARY KEY,
    trigger_key       text        NOT NULL,
    channel           text        NOT NULL,
    channel_order     jsonb       NOT NULL,
    channel_index     integer     NOT NULL DEFAULT 0,
    language          text        NOT NULL,
    urgent            boolean     NOT NULL DEFAULT false,
    site_id           uuid REFERENCES site (id),
    service_id        uuid REFERENCES service (id),
    ticket_id         uuid REFERENCES ticket (id),
    visitor_id        uuid REFERENCES visitor (id),
    variables         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    rendered_subject  text,
    -- Null only for a message suppressed before it could be rendered (no template for its first channel, FR-NTF-020).
    rendered_body     text,
    status            text        NOT NULL CHECK (status IN ('queued', 'sent', 'failed', 'suppressed')),
    attempt_count     integer     NOT NULL DEFAULT 0,
    next_attempt_at   timestamptz,
    suppressed_reason text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    sent_at           timestamptz
);
CREATE INDEX IF NOT EXISTS notification_message_ticket_idx ON notification_message (ticket_id) WHERE ticket_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS notification_message_visitor_idx ON notification_message (visitor_id) WHERE visitor_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS notification_message_due_idx ON notification_message (status, next_attempt_at);

-- Every delivery attempt of a message, with the channel it was tried on and the provider's response (FR-NTF-032),
-- visible in the admin delivery log alongside the message it belongs to.
CREATE TABLE IF NOT EXISTS notification_delivery_attempt (
    id                uuid PRIMARY KEY,
    message_id        uuid        NOT NULL REFERENCES notification_message (id),
    attempt_no        integer     NOT NULL,
    channel           text        NOT NULL,
    status            text        NOT NULL CHECK (status IN ('sent', 'failed')),
    provider_response text,
    attempted_at      timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS notification_delivery_attempt_message_idx ON notification_delivery_attempt (message_id, attempt_no);
