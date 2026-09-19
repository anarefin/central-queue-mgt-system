-- Issuance rules (SRS §7.3, §8.1, §20.6, §26.5; FR-CFG-020..023, FR-ISS-003, FR-ISS-004, API-090, FR-OPS-043): when a Site and
-- its Services take new tickets and when issuance refuses. Forward-only and re-runnable (FR-OPS-020).

-- Weekly hours (FR-CFG-020). A Site with no rows takes tickets at any time; a Service with no rows uses its Site's hours, and a
-- Service with rows uses only those. A weekday with no row is a closed day. `weekday` is ISO: 1 Monday .. 7 Sunday.
CREATE TABLE IF NOT EXISTS business_hours (
    id         uuid PRIMARY KEY,
    scope_type text     NOT NULL CHECK (scope_type IN ('site', 'service')),
    scope_id   uuid     NOT NULL,
    weekday    smallint NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    open_time  time     NOT NULL,
    close_time time     NOT NULL,
    CHECK (open_time < close_time)
);
CREATE UNIQUE INDEX IF NOT EXISTS business_hours_scope_day_uq ON business_hours (scope_type, scope_id, weekday);

-- The holiday calendar of a Site (FR-CFG-021). A half-day closes at `close_time` instead of the day's usual closing time.
CREATE TABLE IF NOT EXISTS holiday (
    id           uuid PRIMARY KEY,
    site_id      uuid    NOT NULL REFERENCES site (id),
    holiday_date date    NOT NULL,
    name         text    NOT NULL,
    half_day     boolean NOT NULL DEFAULT false,
    close_time   time,
    CHECK (NOT half_day OR close_time IS NOT NULL)
);
CREATE UNIQUE INDEX IF NOT EXISTS holiday_site_date_uq ON holiday (site_id, holiday_date);

-- Issuance stops this many minutes before closing, separately per channel (FR-CFG-022). A channel with no row has no cut-off.
CREATE TABLE IF NOT EXISTS channel_cutoff (
    site_id             uuid    NOT NULL REFERENCES site (id),
    channel             text    NOT NULL CHECK (channel IN ('kiosk', 'reception', 'mobile', 'appointment_checkin')),
    minutes_before_close integer NOT NULL CHECK (minutes_before_close BETWEEN 0 AND 1440),
    PRIMARY KEY (site_id, channel)
);

-- What refuses a Service's issuance beyond its hours: the daily cap and the message shown when it is reached (FR-CFG-023), what
-- to do when the visitor already has an active ticket for the Service (FR-ISS-004), and whether an Agent must be rostered
-- (FR-ISS-003). No row means no cap, duplicates allowed, no roster check.
CREATE TABLE IF NOT EXISTS service_issuance_rule (
    service_id       uuid PRIMARY KEY REFERENCES service (id),
    daily_cap        integer CHECK (daily_cap > 0),
    cap_message_i18n jsonb,
    duplicate_policy text        NOT NULL DEFAULT 'allow' CHECK (duplicate_policy IN ('allow', 'warn', 'block')),
    require_agent    boolean     NOT NULL DEFAULT false,
    updated_at       timestamptz NOT NULL DEFAULT now()
);

-- Deployment-wide issuance settings, one row: maintenance mode and its message (FR-OPS-043) and the rate limits of API-090.
CREATE TABLE IF NOT EXISTS issuance_settings (
    id                        smallint PRIMARY KEY CHECK (id = 1),
    maintenance_enabled       boolean     NOT NULL DEFAULT false,
    maintenance_message_i18n  jsonb,
    device_limit_per_minute   integer     NOT NULL DEFAULT 30 CHECK (device_limit_per_minute > 0),
    visitor_limit_per_hour    integer     NOT NULL DEFAULT 5 CHECK (visitor_limit_per_hour > 0),
    updated_at                timestamptz NOT NULL DEFAULT now()
);
INSERT INTO issuance_settings (id) VALUES (1) ON CONFLICT (id) DO NOTHING;

-- The rate limits count each actor's recent issuances (API-090).
CREATE INDEX IF NOT EXISTS ticket_event_issued_actor_idx ON ticket_event (actor_id, recorded_at) WHERE event_type = 'ticket.issued';
