-- Threshold alerts (SRS §15.4, §11.3, FR-MON-020..023, FR-AGT-023, ticket 47).

-- Per-Service alert thresholds (FR-MON-020): a threshold left null leaves that metric unmonitored for the Service.
-- group_window_minutes and escalation_delay_minutes override qms.alerts' own defaults for this Service's own alerts;
-- null keeps the default, 0 for escalation_delay_minutes switches escalation off (FR-MON-021's "MAY").
CREATE TABLE IF NOT EXISTS service_alert_threshold (
    service_id                   uuid PRIMARY KEY REFERENCES service (id),
    queue_length_max             integer CHECK (queue_length_max IS NULL OR queue_length_max >= 0),
    longest_wait_minutes_max     integer CHECK (longest_wait_minutes_max IS NULL OR longest_wait_minutes_max >= 0),
    idle_counters_with_queue_max integer CHECK (idle_counters_with_queue_max IS NULL OR idle_counters_with_queue_max >= 0),
    no_show_rate_percent_max     numeric(5, 2) CHECK (no_show_rate_percent_max IS NULL OR (no_show_rate_percent_max >= 0 AND no_show_rate_percent_max <= 100)),
    device_offline_minutes_max   integer CHECK (device_offline_minutes_max IS NULL OR device_offline_minutes_max >= 0),
    group_window_minutes         integer CHECK (group_window_minutes IS NULL OR group_window_minutes >= 1),
    escalation_delay_minutes     integer CHECK (escalation_delay_minutes IS NULL OR escalation_delay_minutes >= 0),
    updated_at                   timestamptz NOT NULL DEFAULT now(),
    updated_by                   uuid REFERENCES users (id)
);

-- A raised threshold alert (FR-MON-021): one row per still-open breach streak of one (site, service, threshold_type,
-- subject) key. Repeated breaches within the grouping window bump breach_count and last_breached_at on the same row
-- instead of a new one (FR-MON-023, "grouped ... not repeated"); once acknowledged, the next breach of that key
-- starts a fresh row. service_id is null for a threshold type with no Service of its own (break_overrun, FR-AGT-023:
-- a break belongs to an Agent's counter session, not a Service). subject_id disambiguates within the key where one
-- Site/Service could have several breaching subjects at once (break_overrun: the counter_session on break).
CREATE TABLE IF NOT EXISTS alert (
    id                   uuid PRIMARY KEY,
    site_id              uuid        NOT NULL REFERENCES site (id),
    service_id           uuid REFERENCES service (id),
    threshold_type       text        NOT NULL
        CHECK (threshold_type IN ('queue_length', 'longest_wait', 'idle_counters', 'no_show_rate', 'device_offline', 'break_overrun')),
    subject_id           uuid,
    state                text        NOT NULL DEFAULT 'open' CHECK (state IN ('open', 'acknowledged')),
    breach_count         integer     NOT NULL DEFAULT 1 CHECK (breach_count >= 1),
    measured_value       numeric(12, 2) NOT NULL,
    threshold_value      numeric(12, 2) NOT NULL,
    first_breached_at    timestamptz NOT NULL,
    last_breached_at     timestamptz NOT NULL,
    escalated_at         timestamptz,
    acknowledged_at      timestamptz,
    acknowledged_by      uuid REFERENCES users (id),
    acknowledgement_note text,
    created_at           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS alert_site_idx ON alert (site_id, created_at DESC);
-- The grouping lookup (FR-MON-023) and the escalation sweep both filter by these columns; service_id and subject_id
-- are matched with `IS NOT DISTINCT FROM` in the query itself since both are nullable.
CREATE INDEX IF NOT EXISTS alert_group_idx ON alert (site_id, threshold_type, state, last_breached_at DESC);
