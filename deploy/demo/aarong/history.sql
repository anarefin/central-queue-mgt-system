-- Aarong demo: backfills about two weeks of past queue activity so Reports and KPIs have something to show.
--
-- Run by `seed.sh history`, which prepends the demo_volume and demo_params temp tables built from data/*.json.
-- Past-dated activity cannot go through the API (the server stamps every ticket with its own clock), so this writes
-- the same rows the application would have written: counter_session, break_record, visit, ticket and ticket_event.
-- reporting.ticket_fact is NOT written here; the backend's own 15-second refresh sweep builds it from ticket_event.
--
-- Idempotent: a day that already has tickets at the site is skipped, and today is never touched, so it never
-- collides with live numbering. ticket_event is append-only (a trigger refuses UPDATE/DELETE), so the only way to
-- undo this is a fresh database.
--
-- Expects (created by seed.sh in the same psql session):
--   demo_params(site_code text, days int, seed double precision)
--   demo_volume(prefix text, per_hour int)

\set ON_ERROR_STOP on
BEGIN;

SELECT setseed((SELECT seed FROM demo_params)) \g /dev/null

-- The site and its time zone.
CREATE TEMP TABLE demo_site ON COMMIT DROP AS
SELECT s.id AS site_id, s.timezone AS tz, clock_timestamp() AS batch_recorded_at
FROM site s JOIN demo_params p ON lower(s.code) = lower(p.site_code);

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM demo_site) THEN
        RAISE EXCEPTION 'Aarong demo site not found: run "seed.sh config" first';
    END IF;
END $$;

-- Past working days (Sun-Thu, the Aarong work week) that have no tickets yet.
CREATE TEMP TABLE demo_day ON COMMIT DROP AS
SELECT d::date AS day
FROM demo_site ds,
     demo_params p,
     generate_series((now() AT TIME ZONE ds.tz)::date - p.days, (now() AT TIME ZONE ds.tz)::date - 1, interval '1 day') AS d
WHERE extract(isodow FROM d) IN (7, 1, 2, 3, 4)
  AND NOT EXISTS (SELECT 1 FROM ticket t WHERE t.site_id = ds.site_id AND t.reset_key = to_char(d, 'YYYY-MM-DD'));

-- Departments with their hourly volume, services and the zone of their desks.
CREATE TEMP TABLE demo_dept ON COMMIT DROP AS
SELECT g.id AS group_id, g.token_prefix AS prefix, v.per_hour,
       array_agg(DISTINCT sv.id) AS service_ids,
       (SELECT c.zone_id FROM counter_service cs JOIN counter c ON c.id = cs.counter_id JOIN service s2 ON s2.id = cs.service_id
         WHERE s2.service_group_id = g.id ORDER BY c.label LIMIT 1) AS zone_id
FROM service_group g
JOIN demo_site ds ON ds.site_id = g.site_id
JOIN demo_volume v ON v.prefix = g.token_prefix
JOIN service sv ON sv.service_group_id = g.id AND sv.active
WHERE g.active
GROUP BY g.id, g.token_prefix, v.per_hour;

-- Each Officer on a department's team sits at "their" desk: the n-th Officer (by username) at the n-th desk (by label).
CREATE TEMP TABLE demo_seat ON COMMIT DROP AS
WITH agents AS (
    SELECT dd.group_id, tm.user_id, row_number() OVER (PARTITION BY dd.group_id ORDER BY u.username) AS n
    FROM demo_dept dd
    JOIN team tm0 ON tm0.service_group_id = dd.group_id
    JOIN team_member tm ON tm.team_id = tm0.id
    JOIN users u ON u.id = tm.user_id AND u.active
), desks AS (
    SELECT dd.group_id, c.id AS counter_id, row_number() OVER (PARTITION BY dd.group_id ORDER BY length(c.label), c.label) AS n
    FROM demo_dept dd
    JOIN (SELECT DISTINCT s.service_group_id, cs.counter_id FROM counter_service cs JOIN service s ON s.id = cs.service_id) link
      ON link.service_group_id = dd.group_id
    JOIN counter c ON c.id = link.counter_id AND c.active
)
SELECT a.group_id, a.user_id AS agent_id, d.counter_id, a.n
FROM agents a JOIN desks d ON d.group_id = a.group_id AND d.n = a.n;

-- One closed counter session per Officer per day, 09:00-17:00 site time.
CREATE TEMP TABLE demo_session ON COMMIT DROP AS
SELECT gen_random_uuid() AS id, dy.day, st.group_id, st.agent_id, st.counter_id,
       (dy.day + time '09:00' + (random() * interval '10 minutes')) AT TIME ZONE ds.tz AS opened_at,
       (dy.day + time '17:00' + (random() * interval '15 minutes')) AT TIME ZONE ds.tz AS closed_at
FROM demo_day dy CROSS JOIN demo_seat st CROSS JOIN demo_site ds;

INSERT INTO counter_session (id, counter_id, agent_id, opened_at, closed_at, services, state)
SELECT s.id, s.counter_id, s.agent_id, s.opened_at, s.closed_at, dd.service_ids, 'closed'
FROM demo_session s JOIN demo_dept dd ON dd.group_id = s.group_id;

-- Breaks: everyone takes lunch; most take a prayer break and some a tea break.
INSERT INTO break_record (id, counter_session_id, break_type_id, started_at, ended_at, started_by, ended_by)
SELECT gen_random_uuid(), s.id, bt.id, b.started_at, b.started_at + b.len, s.agent_id, s.agent_id
FROM demo_session s
CROSS JOIN demo_site ds
CROSS JOIN LATERAL (
    VALUES ('Lunch', (s.day + time '13:00' + random() * interval '20 minutes') AT TIME ZONE ds.tz, interval '30 minutes' + random() * interval '15 minutes', true),
           ('Prayer', (s.day + time '15:40' + random() * interval '10 minutes') AT TIME ZONE ds.tz, interval '10 minutes' + random() * interval '8 minutes', random() < 0.7),
           ('Tea', (s.day + time '11:00' + random() * interval '15 minutes') AT TIME ZONE ds.tz, interval '8 minutes' + random() * interval '7 minutes', random() < 0.4)
) AS b(type_name, started_at, len, taken)
JOIN break_type bt ON bt.name_i18n ->> 'en' = b.type_name AND bt.active
WHERE b.taken;

-- The producers and the priority class a distant-district producer gets.
CREATE TEMP TABLE demo_producer ON COMMIT DROP AS
SELECT array_agg(id ORDER BY external_code) AS ids FROM visitor WHERE external_code ~ '^\d{4}$' AND anonymized_at IS NULL;

CREATE TEMP TABLE demo_priority ON COMMIT DROP AS
SELECT (SELECT id FROM priority_class WHERE name_i18n ->> 'en' = 'Distant-district producer' AND active LIMIT 1) AS distant_id;

-- Every ticket, with all its random draws taken once so its columns and events agree.
CREATE TEMP TABLE demo_ticket ON COMMIT DROP AS
WITH arrivals AS (
    SELECT dy.day, dd.group_id, dd.prefix, dd.service_ids, dd.zone_id, h.hour,
           (dy.day + make_time(h.hour, 0, 0) + random() * interval '59 minutes') AT TIME ZONE ds.tz AS issued_at
    FROM demo_day dy
    CROSS JOIN demo_dept dd
    CROSS JOIN demo_site ds
    CROSS JOIN generate_series(9, 16) AS h(hour)
    CROSS JOIN LATERAL generate_series(1, greatest(0, round(dd.per_hour * (0.6 + random() * 0.8) * CASE WHEN h.hour IN (11, 14) THEN 1.3 WHEN h.hour = 13 THEN 0.6 ELSE 1 END)::int)) AS n(i)
), drawn AS (
    SELECT a.*,
           gen_random_uuid() AS id,
           gen_random_uuid() AS visit_id,
           a.service_ids[1 + floor(random() * array_length(a.service_ids, 1))::int] AS service_id,
           random() AS r_outcome,
           random() AS r_channel,
           random() AS r_visitor,
           random() AS r_priority,
           random() AS r_seat,
           (60 + random() * random() * 1500)::int AS wait_s,
           (20 + random() * 60)::int AS walk_s,
           random() AS r_service
    FROM arrivals a
)
SELECT d.*,
       row_number() OVER (PARTITION BY d.day, d.group_id ORDER BY d.issued_at) + 99 AS seq_no,
       CASE WHEN d.r_outcome < 0.90 THEN 'completed' WHEN d.r_outcome < 0.95 THEN 'no_show' ELSE 'cancelled' END AS final_state,
       CASE WHEN d.prefix IN ('M', 'D') AND d.r_channel < 0.15 THEN 'appointment_checkin'
            WHEN d.r_channel < 0.65 THEN 'kiosk' ELSE 'reception' END AS channel,
       CASE WHEN d.r_visitor < 0.75 THEN (SELECT ids[1 + floor(d.r_visitor / 0.75 * array_length(ids, 1))::int] FROM demo_producer) END AS visitor_id,
       CASE WHEN d.r_priority < 0.08 THEN (SELECT distant_id FROM demo_priority) END AS priority_class_id,
       (SELECT s.agent_id FROM demo_session s WHERE s.day = d.day AND s.group_id = d.group_id
         ORDER BY s.agent_id OFFSET floor(d.r_seat * (SELECT count(*) FROM demo_session s2 WHERE s2.day = d.day AND s2.group_id = d.group_id))::int LIMIT 1) AS agent_id
FROM drawn d;

ALTER TABLE demo_ticket ADD COLUMN counter_id uuid, ADD COLUMN called_at timestamptz, ADD COLUMN served_at timestamptz,
    ADD COLUMN closed_at timestamptz, ADD COLUMN wait_seconds int, ADD COLUMN service_seconds int, ADD COLUMN token_number text;

UPDATE demo_ticket t SET
    token_number = t.prefix || lpad(t.seq_no::text, 3, '0'),
    counter_id = (SELECT s.counter_id FROM demo_session s WHERE s.day = t.day AND s.agent_id = t.agent_id);

-- Completed: waited, walked to the desk, was served for about the service's expected time.
UPDATE demo_ticket t SET
    called_at = t.issued_at + make_interval(secs => t.wait_s),
    served_at = t.issued_at + make_interval(secs => t.wait_s + t.walk_s),
    closed_at = t.issued_at + make_interval(secs => t.wait_s + t.walk_s + round(sv.expected_minutes * 60 * (0.5 + t.r_service))::int),
    wait_seconds = t.wait_s,
    service_seconds = round(sv.expected_minutes * 60 * (0.5 + t.r_service))::int
FROM service sv
WHERE sv.id = t.service_id AND t.final_state = 'completed' AND t.agent_id IS NOT NULL;

-- No-show: called, never came, marked missed after a few minutes.
UPDATE demo_ticket t SET
    called_at = t.issued_at + make_interval(secs => t.wait_s),
    closed_at = t.issued_at + make_interval(secs => t.wait_s + 180),
    wait_seconds = t.wait_s
WHERE t.final_state = 'no_show' AND t.agent_id IS NOT NULL;

-- Cancelled: left the queue before being called; nobody served it.
UPDATE demo_ticket t SET
    agent_id = NULL, counter_id = NULL,
    closed_at = t.issued_at + make_interval(secs => greatest(60, t.wait_s / 2))
WHERE t.final_state = 'cancelled' OR t.agent_id IS NULL;
UPDATE demo_ticket SET final_state = 'cancelled' WHERE agent_id IS NULL;

INSERT INTO visit (id, site_id, started_at, ended_at)
SELECT t.visit_id, ds.site_id, t.issued_at, t.closed_at FROM demo_ticket t CROSS JOIN demo_site ds;

INSERT INTO ticket (id, token_number, sequence_no, reset_key, service_id, service_group_id, site_id, zone_id, visit_id,
                    origin_channel, state, issued_at, queued_at, secret_hash, version,
                    priority_class_id, visitor_id, counter_id, agent_id, called_at, served_at, closed_at,
                    wait_seconds, service_seconds)
SELECT t.id, t.token_number, t.seq_no, to_char(t.day, 'YYYY-MM-DD'), t.service_id, t.group_id, ds.site_id, t.zone_id, t.visit_id,
       t.channel, t.final_state, t.issued_at, t.issued_at, encode(sha256(convert_to(gen_random_uuid()::text, 'UTF8')), 'hex'),
       CASE t.final_state WHEN 'completed' THEN 3 WHEN 'no_show' THEN 2 ELSE 1 END,
       t.priority_class_id, t.visitor_id, t.counter_id, t.agent_id, t.called_at, t.served_at, t.closed_at,
       t.wait_seconds, t.service_seconds
FROM demo_ticket t CROSS JOIN demo_site ds;

-- The event log, exactly one row per transition. occurred_at is the backdated time; recorded_at is now, which is
-- what the reporting sweep keys on.
INSERT INTO ticket_event (id, ticket_id, seq, event_type, from_state, to_state, actor_id, actor_type, counter_id, payload, occurred_at, recorded_at)
SELECT gen_random_uuid(), t.id, e.seq, e.event_type, e.from_state, e.to_state, e.actor_id, e.actor_type, e.counter_id, e.payload, e.occurred_at, ds.batch_recorded_at
FROM demo_ticket t
CROSS JOIN demo_site ds
CROSS JOIN LATERAL (
    VALUES
        (1, 'ticket.issued', NULL, 'waiting', NULL::uuid, CASE WHEN t.channel = 'kiosk' THEN 'device' ELSE 'staff' END, NULL::uuid,
            jsonb_build_object('origin_channel', t.channel, 'token_number', t.token_number), t.issued_at, true),
        (2, 'ticket.called', 'waiting', 'called', t.agent_id, 'staff', t.counter_id, NULL::jsonb, t.called_at, t.final_state IN ('completed', 'no_show')),
        (3, 'ticket.serving', 'called', 'serving', t.agent_id, 'staff', t.counter_id, NULL::jsonb, t.served_at, t.final_state = 'completed'),
        (4, 'ticket.completed', 'serving', 'completed', t.agent_id, 'staff', t.counter_id,
            jsonb_build_object('wait_seconds', t.wait_seconds, 'service_seconds', t.service_seconds), t.closed_at, t.final_state = 'completed'),
        (3, 'ticket.no_show', 'called', 'no_show', t.agent_id, 'staff', t.counter_id, NULL::jsonb, t.closed_at, t.final_state = 'no_show'),
        (2, 'ticket.cancelled', 'waiting', 'cancelled', NULL::uuid, 'visitor', NULL::uuid, NULL::jsonb, t.closed_at, t.final_state = 'cancelled')
) AS e(seq, event_type, from_state, to_state, actor_id, actor_type, counter_id, payload, occurred_at, wanted)
WHERE e.wanted;

-- Make sure the next reporting sweep (every 15 s) picks this batch up even if a live event raced past it.
UPDATE reporting.refresh_watermark w
SET last_recorded_at = ds.batch_recorded_at - interval '1 second'
FROM demo_site ds
WHERE w.last_recorded_at IS NOT NULL AND w.last_recorded_at >= ds.batch_recorded_at;

SELECT count(DISTINCT day) AS days_added,
       count(*) AS tickets_added,
       count(*) FILTER (WHERE final_state = 'completed') AS completed,
       count(*) FILTER (WHERE final_state = 'no_show') AS no_show,
       count(*) FILTER (WHERE final_state = 'cancelled') AS cancelled
FROM demo_ticket;

COMMIT;
