-- Priority classes, the queue ordering strategy per Service group and what a ticket carries into the score
-- (SRS §10.2, §10.3, §18.2, §18.3, FR-QUE-010, FR-QUE-011, FR-QUE-020, FR-QUE-021, ADR-0003, ADR-0004).

-- A Priority class grants a Head start (minutes of virtual waiting on arrival) and may set a maximum wait after which
-- a ticket is escalated (FR-QUE-010, FR-QUE-022). Classes are organisation-wide. Exactly one is the default class
-- (normal, Head start 0): it is what a ticket without a class of its own belongs to. It cannot be deactivated or given
-- a Head start, but its maximum wait can be set so that ordinary tickets are protected from starvation too (UAT U6).
CREATE TABLE IF NOT EXISTS priority_class (
    id                    uuid PRIMARY KEY,
    name_i18n             jsonb       NOT NULL,
    headstart_minutes     integer     NOT NULL DEFAULT 0 CHECK (headstart_minutes >= 0),
    max_wait_minutes      integer CHECK (max_wait_minutes IS NULL OR max_wait_minutes >= 1),
    -- Replaces the Service's own prefix when a numbering rule takes its prefix from the priority class (FR-CFG-018).
    token_prefix_override text,
    is_default            boolean     NOT NULL DEFAULT false,
    active                boolean     NOT NULL DEFAULT true,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    CHECK (NOT is_default OR (headstart_minutes = 0 AND active))
);
CREATE UNIQUE INDEX IF NOT EXISTS priority_class_one_default_uq ON priority_class (is_default) WHERE is_default;

INSERT INTO priority_class (id, name_i18n, headstart_minutes, is_default, active, created_at, updated_at)
SELECT gen_random_uuid(), '{"en": "Normal", "bn": "সাধারণ"}'::jsonb, 0, true, true, now(), now()
WHERE NOT EXISTS (SELECT 1 FROM priority_class WHERE is_default);

-- The ordering strategy of a Service group: weighted_wait (the default), strict_priority or fifo (FR-QUE-021). A group
-- without a row uses weighted_wait. `params` is reserved for strategy tuning.
CREATE TABLE IF NOT EXISTS routing_strategy (
    service_group_id uuid PRIMARY KEY REFERENCES service_group (id),
    strategy         text        NOT NULL CHECK (strategy IN ('weighted_wait', 'strict_priority', 'fifo')),
    params           jsonb,
    updated_at       timestamptz NOT NULL
);

-- The class a ticket was given (null means the default class) and the signed offset a positional move sets (ADR-0004;
-- nothing writes it yet, so it is 0 and the engine already reads it).
ALTER TABLE ticket ADD COLUMN IF NOT EXISTS priority_class_id uuid REFERENCES priority_class (id);
ALTER TABLE ticket ADD COLUMN IF NOT EXISTS score_adjustment_minutes integer NOT NULL DEFAULT 0;
