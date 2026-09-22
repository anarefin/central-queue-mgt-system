-- Configuration versioning and bundle (SRS §7.6, §18.2, CFG-004, ticket 55): every change to a Priority class, a
-- Service group's routing strategy, a numbering rule or a business-hours week is snapshotted here with its author
-- and moment, so it can be listed and reverted (FR-CFG-040). `entity` is one of the four config table names those
-- areas already use (`priority_class`, `routing_strategy`, `numbering_rule`, `business_hours`); `entity_id` is the
-- row's own id for a Priority class, the Service group id for a routing strategy, and the scope id (Service or
-- Service group / Site or Service) for a numbering rule or a business-hours week, so history survives a numbering
-- rule being removed and re-created and always names the thing an admin actually edited.
CREATE TABLE IF NOT EXISTS config_version (
    id         uuid        PRIMARY KEY,
    entity     text        NOT NULL,
    entity_id  uuid        NOT NULL,
    payload    jsonb       NOT NULL,
    changed_by uuid REFERENCES users (id),
    changed_at timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS config_version_entity_idx ON config_version (entity, entity_id, changed_at DESC);
