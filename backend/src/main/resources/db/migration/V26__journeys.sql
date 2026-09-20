-- Journeys and multi-stop Visits (ticket 31, ADR-0007, FR-QUE-060..064, FR-ISS-022, FR-AGT-031). A Journey is an
-- optional plan of Service stops on a Visit, ordered or unordered, from a template (scoped to one Service group) or
-- ad hoc at issuance. `journey_stop` realises each stop as a ticket when it is issued; `ticket_id` is null until then,
-- which is how an unordered journey's still-to-come stops and an ordered journey's not-yet-reached ones are told apart
-- from one that has been issued. Feature-gated, off until an Org Admin turns it on (`journey_settings`).

CREATE TABLE IF NOT EXISTS journey_template (
    id                uuid PRIMARY KEY,
    service_group_id  uuid        NOT NULL REFERENCES service_group (id),
    name_i18n         jsonb       NOT NULL,
    ordered           boolean     NOT NULL,
    display_order     integer     NOT NULL DEFAULT 0,
    active            boolean     NOT NULL DEFAULT true,
    created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS journey_template_group_idx ON journey_template (service_group_id, display_order);

-- A template's stops, in order; `seq` is 1-based (FR-QUE-060).
CREATE TABLE IF NOT EXISTS journey_template_stop (
    id          uuid PRIMARY KEY,
    template_id uuid    NOT NULL REFERENCES journey_template (id),
    service_id  uuid    NOT NULL REFERENCES service (id),
    seq         integer NOT NULL CHECK (seq >= 1)
);
CREATE UNIQUE INDEX IF NOT EXISTS journey_template_stop_seq_uq ON journey_template_stop (template_id, seq);

-- The plan on a Visit (ADR-0007, §18.3): the template it came from, null for ad hoc, and whether its stops are ordered.
ALTER TABLE visit ADD COLUMN IF NOT EXISTS journey_template_id uuid REFERENCES journey_template (id);
ALTER TABLE visit ADD COLUMN IF NOT EXISTS journey_ordered boolean;

-- One planned stop of a Visit's Journey; realised by a ticket when issued. Ordered journeys issue one stop's ticket at a
-- time, on completion of the one before (FR-QUE-061); unordered journeys issue every stop's ticket up front (FR-QUE-062).
CREATE TABLE IF NOT EXISTS journey_stop (
    id         uuid PRIMARY KEY,
    visit_id   uuid    NOT NULL REFERENCES visit (id),
    service_id uuid    NOT NULL REFERENCES service (id),
    seq        integer NOT NULL CHECK (seq >= 1),
    ticket_id  uuid REFERENCES ticket (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS journey_stop_seq_uq ON journey_stop (visit_id, seq);
CREATE INDEX IF NOT EXISTS journey_stop_visit_idx ON journey_stop (visit_id);
CREATE UNIQUE INDEX IF NOT EXISTS journey_stop_ticket_uq ON journey_stop (ticket_id) WHERE ticket_id IS NOT NULL;

-- Deployment-wide switch, one row like `issuance_settings`: journeys are off until an Org Admin turns them on. Phase 1
-- is single-tenant, so one flag serves "per profile" until ticket 56 seeds a default per vertical profile.
CREATE TABLE IF NOT EXISTS journey_settings (
    id         smallint PRIMARY KEY CHECK (id = 1),
    enabled    boolean     NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO journey_settings (id) VALUES (1) ON CONFLICT (id) DO NOTHING;
