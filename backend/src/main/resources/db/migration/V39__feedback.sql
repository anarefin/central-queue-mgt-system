-- Post-service feedback (ticket 45, SRS §13.4 FR-MOB-033, §14.2, §18.3): an optional 1-5 rating and comment against a
-- completed Ticket. The rating (and the Agent it is against, via ticket.agent_id, kept as history past a terminal
-- state per ticket 9) feeds the aggregate Feedback report (§18.2) and is never gated; the comment is a different
-- story — it is never shown to the Agent individually until a Team Admin approves it (FR-MOB-033). One feedback per
-- Ticket, offered once after completion.
CREATE TABLE IF NOT EXISTS feedback (
    id                  uuid PRIMARY KEY,
    ticket_id           uuid        NOT NULL REFERENCES ticket (id),
    rating              smallint    NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment             text,
    -- Both null until a Team Admin approves the comment; a feedback row with no comment has nothing to approve.
    comment_approved_by uuid REFERENCES users (id),
    comment_approved_at timestamptz,
    submitted_at        timestamptz NOT NULL
);
-- One feedback per Ticket; also the "mine" read's own join key (ticket.agent_id -> feedback.ticket_id).
CREATE UNIQUE INDEX IF NOT EXISTS feedback_ticket_uq ON feedback (ticket_id);
-- A Team Admin's own review queue: comments still waiting for a decision.
CREATE INDEX IF NOT EXISTS feedback_pending_comment_idx ON feedback (submitted_at) WHERE comment IS NOT NULL AND comment_approved_at IS NULL;
