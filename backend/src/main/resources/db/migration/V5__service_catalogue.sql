-- Service catalogue: service groups, services, counter links, teams and outcome codes (SRS §18.2, FR-CFG-010..015,
-- FR-AGT-032, FR-AGT-033). Names are per-language jsonb maps such as {"bn": "...", "en": "..."} (FR-I18N-010). Rows are
-- deactivated rather than deleted; a service may be deleted only while nothing refers to it (FR-CFG-015), so every
-- table that a ticket will point at is referenced by plain foreign keys with no cascade.
CREATE TABLE IF NOT EXISTS service_group (
    id            uuid PRIMARY KEY,
    site_id       uuid        NOT NULL REFERENCES site (id),
    name_i18n     jsonb       NOT NULL,
    token_prefix  text        NOT NULL,
    display_order integer     NOT NULL DEFAULT 0,
    active        boolean     NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS service_group_site_idx ON service_group (site_id, display_order);

CREATE TABLE IF NOT EXISTS service (
    id               uuid PRIMARY KEY,
    service_group_id uuid        NOT NULL REFERENCES service_group (id),
    name_i18n        jsonb       NOT NULL,
    token_prefix     text        NOT NULL,
    expected_minutes integer     NOT NULL CHECK (expected_minutes > 0),
    sla_wait_minutes integer     NOT NULL CHECK (sla_wait_minutes > 0),
    -- issuing channels, e.g. ["kiosk","reception"] (FR-CFG-010)
    channels         jsonb       NOT NULL,
    icon             text,
    display_order    integer     NOT NULL DEFAULT 0,
    requires_visitor_id text     NOT NULL DEFAULT 'not_required' CHECK (requires_visitor_id IN ('not_required', 'optional', 'mandatory')),
    booking_mode     text        NOT NULL DEFAULT 'both' CHECK (booking_mode IN ('appointment_only', 'walk_in_only', 'both')),
    active           boolean     NOT NULL DEFAULT true,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS service_group_idx ON service (service_group_id, display_order);

-- A counter may serve several services and a service may be served at several counters; weight 1 is primary and a
-- higher weight is a fallback (FR-CFG-011).
CREATE TABLE IF NOT EXISTS counter_service (
    counter_id        uuid        NOT NULL REFERENCES counter (id),
    service_id        uuid        NOT NULL REFERENCES service (id),
    preference_weight integer     NOT NULL CHECK (preference_weight >= 1),
    created_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (counter_id, service_id)
);
CREATE INDEX IF NOT EXISTS counter_service_service_idx ON counter_service (service_id);

-- Exactly one team per service group (CONTEXT.md); it is created with the group.
CREATE TABLE IF NOT EXISTS team (
    id               uuid PRIMARY KEY,
    service_group_id uuid NOT NULL UNIQUE REFERENCES service_group (id),
    name             text NOT NULL
);

CREATE TABLE IF NOT EXISTS team_member (
    team_id  uuid        NOT NULL REFERENCES team (id),
    user_id  uuid        NOT NULL REFERENCES users (id),
    added_at timestamptz NOT NULL DEFAULT now(),
    added_by uuid REFERENCES users (id),
    PRIMARY KEY (team_id, user_id)
);
CREATE INDEX IF NOT EXISTS team_member_user_idx ON team_member (user_id);

-- What an agent records when a ticket completes (FR-AGT-032). Deactivated, never deleted, once tickets refer to it.
CREATE TABLE IF NOT EXISTS outcome_code (
    id            uuid PRIMARY KEY,
    service_id    uuid        NOT NULL REFERENCES service (id),
    code          text        NOT NULL,
    label_i18n    jsonb       NOT NULL,
    display_order integer     NOT NULL DEFAULT 0,
    active        boolean     NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS outcome_code_service_code_uq ON outcome_code (service_id, lower(code));
