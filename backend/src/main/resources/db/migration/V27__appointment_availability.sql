-- Appointment availability (SRS §9.1, §9.2; FR-APT-001..005, FR-APT-010). Availability is definable at three levels,
-- most specific winning: 'service' (target_id = service.id), 'team' (target_id = team.id) and 'agent'
-- (target_id = users.id). target_id has no single foreign key because which table it points at depends on level.

-- A slot template: a weekday pattern, start/end time, slot duration, concurrent capacity and an optional validity
-- range (FR-APT-002). Replaced wholesale per (level, target_id), like business_hours; a weekday may repeat with a
-- different validity range (e.g. summer vs winter hours).
CREATE TABLE IF NOT EXISTS appointment_slot_template (
    id           uuid PRIMARY KEY,
    level        text     NOT NULL CHECK (level IN ('service', 'team', 'agent')),
    target_id    uuid     NOT NULL,
    weekday      smallint NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    start_time   time     NOT NULL,
    end_time     time     NOT NULL,
    slot_minutes integer  NOT NULL CHECK (slot_minutes > 0),
    capacity     integer  NOT NULL CHECK (capacity > 0),
    valid_from   date,
    valid_to     date,
    CHECK (start_time < end_time),
    CHECK (valid_from IS NULL OR valid_to IS NULL OR valid_from <= valid_to)
);
CREATE INDEX IF NOT EXISTS appointment_slot_template_target_idx ON appointment_slot_template (level, target_id, weekday);

-- A one-off exception for a single date (FR-APT-003), and the admin override of business hours/holiday suppression
-- for that date (FR-APT-004): 'blocked' closes the date outright, 'extra' opens a window of its own regardless of
-- hours or a holiday, 'reduced_capacity' keeps the normal window but caps capacity. At most one exception per
-- (level, target_id, date); the caller replaces it by deleting and re-adding.
CREATE TABLE IF NOT EXISTS appointment_exception (
    id             uuid PRIMARY KEY,
    level          text NOT NULL CHECK (level IN ('service', 'team', 'agent')),
    target_id      uuid NOT NULL,
    exception_date date NOT NULL,
    exception_type text NOT NULL CHECK (exception_type IN ('blocked', 'extra', 'reduced_capacity')),
    start_time     time,
    end_time       time,
    slot_minutes   integer CHECK (slot_minutes IS NULL OR slot_minutes > 0),
    capacity       integer CHECK (capacity IS NULL OR capacity >= 0),
    note_i18n      jsonb,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CHECK (start_time IS NULL OR end_time IS NULL OR start_time < end_time),
    CHECK (exception_type <> 'extra' OR (start_time IS NOT NULL AND end_time IS NOT NULL AND slot_minutes IS NOT NULL AND capacity IS NOT NULL AND capacity > 0)),
    CHECK (exception_type <> 'reduced_capacity' OR capacity IS NOT NULL)
);
CREATE UNIQUE INDEX IF NOT EXISTS appointment_exception_target_date_uq ON appointment_exception (level, target_id, exception_date);

-- A Service's booking horizon and minimum lead time (FR-APT-005); no row means the defaults of 30 days and 2 hours.
CREATE TABLE IF NOT EXISTS appointment_service_settings (
    service_id             uuid PRIMARY KEY REFERENCES service (id),
    booking_horizon_days   integer     NOT NULL CHECK (booking_horizon_days > 0),
    min_lead_time_minutes  integer     NOT NULL CHECK (min_lead_time_minutes >= 0),
    updated_at             timestamptz NOT NULL DEFAULT now()
);
