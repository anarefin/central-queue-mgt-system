-- Append-only audit log (FR-SEC-040..042) and approval requests (FR-CFG-102, FR-CFG-107).
-- Columns follow SRS §18.3 plus `device`, `reason` and `trace_id` that FR-SEC-041 needs. `actor_id` is nullable so
-- failed sign-ins for unknown usernames can be recorded.
CREATE TABLE IF NOT EXISTS audit_log (
    id          uuid PRIMARY KEY,
    actor_id    uuid,
    actor_role  text,
    action      text        NOT NULL,
    entity      text        NOT NULL,
    entity_id   uuid,
    before      jsonb,
    after       jsonb,
    ip          text,
    device      text,
    reason      text,
    trace_id    text,
    occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS audit_log_time_idx   ON audit_log (occurred_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS audit_log_actor_idx  ON audit_log (actor_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS audit_log_action_idx ON audit_log (action, occurred_at DESC);
CREATE INDEX IF NOT EXISTS audit_log_entity_idx ON audit_log (entity, entity_id, occurred_at DESC);

-- No application path may edit or delete audit rows, so the database refuses too. The retention purge (SRS
-- FR-SEC-043) is a separate, privileged operation added with its own ticket.
CREATE OR REPLACE FUNCTION audit_log_reject_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only (% not allowed)', TG_OP USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS audit_log_no_update_delete ON audit_log;
CREATE TRIGGER audit_log_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_reject_change();

DROP TRIGGER IF EXISTS audit_log_no_truncate ON audit_log;
CREATE TRIGGER audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit_log_reject_change();

-- A pending row an Org Admin acts on. Never implemented as a temporary role elevation (FR-CFG-107).
CREATE TABLE IF NOT EXISTS approval_requests (
    id              uuid PRIMARY KEY,
    type            text        NOT NULL,
    requested_by    uuid        NOT NULL REFERENCES users (id),
    payload         jsonb       NOT NULL DEFAULT '{}',
    status          text        NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected')),
    decided_by      uuid REFERENCES users (id),
    decided_at      timestamptz,
    decision_reason text,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS approval_requests_status_idx ON approval_requests (status, created_at DESC);
