#!/usr/bin/env bash
# Seeds the "Aarong Central Queue Management System" demo (see aarong-onboarding.html) into a running stack
# (deploy/compose.yaml): one central Site with CS-1, CS-2 and Haque Centre as floors, 19 departments with S100-style
# token numbering, desks, Officers, Team Admins, producers, notices, appointments, a live morning and two weeks of
# history for the reports.
#
# Usage:
#   export QMS_BOOTSTRAP_ADMIN_USERNAME=admin QMS_BOOTSTRAP_ADMIN_PASSWORD='...'
#   ./deploy/demo/aarong/seed.sh [all|config|users|producers|appointments|history|live|devices|displays|logins] [--wait]
#
#   all           config + users + producers + appointments + history + live, then prints the logins (default)
#   config        profile, site, floors, desks, departments, services, numbering, breaks, branding, notices
#   users         Super Admin, Reception, a Team Admin per department and the Officers, added to their teams
#   producers     imports data/producers.csv (producer code, name, phone, category)
#   appointments  phone-booking slots for Merchandising and Design, plus a few bookings for today
#   history       ~2 weeks of past tokens, sessions and breaks, written straight to PostgreSQL (needs $PSQL)
#   live          Officers open their desks, Reception issues tokens, some are served, one Officer is on a break
#   devices       prints pairing codes for the 3 kiosks and 9 TV displays (use --wait to configure them once paired)
#   displays      applies the TV layout (serving table + notice board) to every paired display
#   logins        prints the demo logins
#
# Walkthrough helpers for steps the apps have no screen for yet (see aarong-onboarding.html):
#   bookings                  today's phone bookings and their reference codes (needs $PSQL)
#   checkin REFERENCE         Reception checks a producer in for their appointment
#   team-request              design.lead asks for merch.anwar to join the Design team
#   approvals [--approve]     the Super Admin's pending requests, approving them with --approve
#
# Environment:
#   BASE_URL        default http://localhost:8080
#   DEMO_PASSWORD   password of every demo user, default Aarong@2026
#   HOURS           demo (default: open every day, all day, so the demo works whenever you try it)
#                   or realistic (Sun-Thu 09:00-17:00, the Aarong work week)
#   HISTORY_DAYS    how many past days to backfill, default 14
#   PSQL            command that runs psql against the stack's database, default:
#                   docker compose -f deploy/compose.yaml exec -T postgres psql -U qms -d qms
#
# Requires: bash, curl, jq (and docker for the history step). Safe to run more than once: every step looks up what
# already exists by its natural key (site code, floor, token prefix, desk label, username) and creates only the rest.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA="${HERE}/data"
COMPOSE_FILE="$(cd "${HERE}/../.." && pwd)/compose.yaml"

BASE_URL="${BASE_URL:-http://localhost:8080}"
API="${BASE_URL%/}/api/v1"
DEMO_PASSWORD="${DEMO_PASSWORD:-Aarong@2026}"
HOURS="${HOURS:-demo}"
HISTORY_DAYS="${HISTORY_DAYS:-14}"
SITE_CODE="$(jq -r .site.code "${DATA}/site.json")"
SITE_TZ="$(jq -r .site.timezone "${DATA}/site.json")"

ADMIN_TOKEN=""
SITE_ID=""

# ---- output --------------------------------------------------------------------------------------------------------

say() { printf '\033[1;38;5;208m▸\033[0m %s\n' "$*" >&2; }
ok() { printf '  \033[32m✓\033[0m %s\n' "$*" >&2; }
note() { printf '  \033[2m· %s\033[0m\n' "$*" >&2; }
warn() { printf '  \033[33m! %s\033[0m\n' "$*" >&2; }
die() { printf '\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "$1 is required"; }

# ---- HTTP ----------------------------------------------------------------------------------------------------------

# api METHOD PATH [BODY] [TOKEN] -> response body on stdout; exits the script on any HTTP error.
# Set IDEMPOTENCY_KEY in the environment of one call to send an Idempotency-Key header.
api() {
    local out status
    out="$(api_raw "$@")"
    status="${out##*$'\n'}"
    out="${out%$'\n'*}"
    if [ "${status}" -ge 400 ] || [ "${status}" -eq 0 ]; then
        die "$1 $2 -> HTTP ${status}: ${out}"
    fi
    printf '%s' "${out}"
}

# api_try METHOD PATH [BODY] [TOKEN] -> "BODY\nSTATUS" without failing, for calls whose refusal is an answer.
api_try() { api_raw "$@"; }

api_raw() {
    local method="$1" path="$2" body="${3:-}" token="${4-${ADMIN_TOKEN}}"
    local args=(-sS -X "${method}" "${API}${path}" -H 'Accept: application/json' -w $'\n%{http_code}')
    [ -n "${token}" ] && args+=(-H "Authorization: Bearer ${token}")
    [ -n "${body}" ] && args+=(-H 'Content-Type: application/json' --data-binary "${body}")
    [ -n "${IDEMPOTENCY_KEY:-}" ] && args+=(-H "Idempotency-Key: ${IDEMPOTENCY_KEY}")
    curl "${args[@]}" || printf '\n0'
}

status_of() { printf '%s' "${1##*$'\n'}"; }
body_of() { printf '%s' "${1%$'\n'*}"; }

login() {
    api POST /auth/login "$(jq -nc --arg u "$1" --arg p "$2" '{username: $u, password: $p}')" "" | jq -r .access_token
}

login_admin() {
    [ -n "${ADMIN_TOKEN}" ] && return 0
    local user="${QMS_BOOTSTRAP_ADMIN_USERNAME:-}" pass="${QMS_BOOTSTRAP_ADMIN_PASSWORD:-}"
    if [ -z "${user}" ] || [ -z "${pass}" ]; then
        # After the first full run the demo Super Admin works too.
        user="aarong.admin" pass="${DEMO_PASSWORD}"
    fi
    local out
    out="$(api_try POST /auth/login "$(jq -nc --arg u "${user}" --arg p "${pass}" '{username: $u, password: $p}')" "")"
    [ "$(status_of "${out}")" = 200 ] ||
        die "cannot sign in as ${user} (set QMS_BOOTSTRAP_ADMIN_USERNAME / QMS_BOOTSTRAP_ADMIN_PASSWORD): $(body_of "${out}")"
    ADMIN_TOKEN="$(body_of "${out}" | jq -r .access_token)"
}

# ---- lookups -------------------------------------------------------------------------------------------------------

site_id() {
    [ -n "${SITE_ID}" ] && { printf '%s' "${SITE_ID}"; return; }
    SITE_ID="$(api GET /sites | jq -r --arg c "${SITE_CODE}" '.items[] | select((.code | ascii_downcase) == ($c | ascii_downcase)) | .id' | head -n1)"
    [ -n "${SITE_ID}" ] || die "site ${SITE_CODE} not found: run '$0 config' first"
    printf '%s' "${SITE_ID}"
}

# zone_id KEY -> the id of the floor named by data/site.json's zone KEY
zone_id() {
    local building floor
    building="$(jq -r --arg k "$1" '.zones[] | select(.key == $k) | .building_label' "${DATA}/site.json")"
    floor="$(jq -r --arg k "$1" '.zones[] | select(.key == $k) | .floor_label' "${DATA}/site.json")"
    api GET "/sites/$(site_id)/zones" |
        jq -r --arg b "${building}" --arg f "${floor}" '.items[] | select(.building_label == $b and .floor_label == $f) | .id' | head -n1
}

group_id() {
    api GET "/sites/$(site_id)/service-groups" | jq -r --arg p "$1" '.items[] | select(.token_prefix == $p) | .id' | head -n1
}

# all users as one JSON array (the list is paged by cursor, 200 at a time)
all_users() {
    local cursor="" page acc="[]"
    while :; do
        page="$(api GET "/users?limit=200${cursor:+&cursor=${cursor}}")"
        acc="$(jq -c --argjson a "${acc}" '$a + .items' <<<"${page}")"
        cursor="$(jq -r '.next_cursor // empty' <<<"${page}")"
        [ -z "${cursor}" ] && break
    done
    printf '%s' "${acc}"
}

today() { TZ="${SITE_TZ}" date +%Y-%m-%d; }

# ---- config --------------------------------------------------------------------------------------------------------

cmd_config() {
    login_admin

    say "Industry profile: Producer services"
    api POST /setup/profile/reset '{"profile_id":"producer_services"}' >/dev/null
    for flag in appointment visitor_code_lookup journey; do
        api PUT "/setup/feature-flags/${flag}" '{"enabled":true}' >/dev/null
    done
    ok "labels Producer / Department / Desk / Officer, priority classes, feature flags (appointments, producer-code lookup, journeys)"

    say "Site ${SITE_CODE}"
    SITE_ID="$(api GET /sites | jq -r --arg c "${SITE_CODE}" '.items[] | select((.code | ascii_downcase) == ($c | ascii_downcase)) | .id' | head -n1)"
    if [ -z "${SITE_ID}" ]; then
        SITE_ID="$(api POST /sites "$(jq -c .site "${DATA}/site.json")" | jq -r .id)"
        ok "created $(jq -r .site.name "${DATA}/site.json")"
    else
        note "exists"
    fi

    local hours
    if [ "${HOURS}" = realistic ]; then
        hours='{"days":[{"weekday":7,"open":"09:00","close":"17:00"},{"weekday":1,"open":"09:00","close":"17:00"},{"weekday":2,"open":"09:00","close":"17:00"},{"weekday":3,"open":"09:00","close":"17:00"},{"weekday":4,"open":"09:00","close":"17:00"}]}'
    else
        hours="$(jq -nc '{days: [range(1; 8) | {weekday: ., open: "00:00", close: "23:59"}]}')"
    fi
    api PUT "/sites/${SITE_ID}/hours" "${hours}" >/dev/null
    ok "business hours: ${HOURS}"

    say "Buildings and floors"
    local zones
    zones="$(api GET "/sites/${SITE_ID}/zones")"
    while IFS= read -r z; do
        local b f
        b="$(jq -r .building_label <<<"${z}")"
        f="$(jq -r .floor_label <<<"${z}")"
        if jq -e --arg b "${b}" --arg f "${f}" '.items[] | select(.building_label == $b and .floor_label == $f)' <<<"${zones}" >/dev/null; then
            note "${b} ${f} floor exists"
        else
            api POST "/sites/${SITE_ID}/zones" "$(jq -c 'del(.key)' <<<"${z}")" >/dev/null
            ok "${b} ${f} floor"
        fi
    done < <(jq -c '.zones[]' "${DATA}/site.json")

    say "Break types"
    local breaks
    breaks="$(api GET /break-types)"
    while IFS= read -r bt; do
        local name
        name="$(jq -r '.name_i18n.en' <<<"${bt}")"
        if jq -e --arg n "${name}" '.items[] | select(.name_i18n.en == $n)' <<<"${breaks}" >/dev/null; then
            note "${name} exists"
        else
            api POST /break-types "${bt}" >/dev/null
            ok "${name}"
        fi
    done < <(jq -c '.break_types[]' "${DATA}/site.json")

    say "Departments, services, desks and S100-style numbering"
    local groups desk_no=0 order=0
    groups="$(api GET "/sites/${SITE_ID}/service-groups")"
    while IFS= read -r dept; do
        order=$((order + 1))
        local prefix name zkey zid gid body
        prefix="$(jq -r .prefix <<<"${dept}")"
        name="$(jq -r .name_i18n.en <<<"${dept}")"
        zkey="$(jq -r .zone <<<"${dept}")"
        zid="$(zone_id "${zkey}")"
        body="$(jq -c --argjson o "${order}" '{name_i18n, token_prefix: .prefix, display_order: $o, team_selectable: .individual_selectable, individual_selectable}' <<<"${dept}")"

        gid="$(jq -r --arg p "${prefix}" '.items[] | select(.token_prefix == $p) | .id' <<<"${groups}" | head -n1)"
        if [ -z "${gid}" ]; then
            gid="$(api POST "/sites/${SITE_ID}/service-groups" "${body}" | jq -r .id)"
        else
            api PATCH "/service-groups/${gid}" "${body}" >/dev/null
        fi
        # Every service of the department draws from one department-wide sequence: S100, S101, ...
        api PUT "/service-groups/${gid}/numbering-rule" \
            '{"prefix_source":"service_group","sequence_start":100,"padding":3,"reset_boundary":"daily","reset_time":"00:00","separator":""}' >/dev/null

        local services svc_ids="" sorder=0
        services="$(api GET "/service-groups/${gid}/services")"
        while IFS= read -r svc; do
            sorder=$((sorder + 1))
            local sp sid booking
            sp="$(jq -r .prefix <<<"${svc}")"
            sid="$(jq -r --arg p "${sp}" '.items[] | select(.token_prefix == $p) | .id' <<<"${services}" | head -n1)"
            if [ -z "${sid}" ]; then
                booking="$(jq -r 'if .appointments then "both" else "walk_in_only" end' <<<"${dept}")"
                sid="$(api POST "/service-groups/${gid}/services" "$(jq -c --arg b "${booking}" --argjson o "${sorder}" '{
                        name_i18n, token_prefix: .prefix, expected_minutes, sla_wait_minutes, display_order: $o,
                        channels: ["kiosk", "reception", "appointment_checkin"], visitor_identifier: "optional", booking_mode: $b}' <<<"${svc}")" | jq -r .id)"
            fi
            svc_ids="${svc_ids} ${sid}"
        done < <(jq -c '.services[]' <<<"${dept}")

        local counters n i label cid
        counters="$(api GET "/zones/${zid}/counters")"
        n="$(jq -r .counters <<<"${dept}")"
        for ((i = 1; i <= n; i++)); do
            desk_no=$((desk_no + 1))
            label="${desk_no}"
            cid="$(jq -r --arg l "${label}" '.items[] | select(.label == $l) | .id' <<<"${counters}" | head -n1)"
            if [ -z "${cid}" ]; then
                cid="$(api POST "/zones/${zid}/counters" "$(jq -nc --arg l "${label}" --arg n "${name}" '{label: $l, location_note: $n}')" | jq -r .id)"
            fi
            for sid in ${svc_ids}; do
                api PUT "/services/${sid}/counters/${cid}" '{"preference_weight":1}' >/dev/null
            done
        done
        ok "$(printf '%-3s' "${prefix}") ${name} · ${zkey} · $(jq '.services | length' <<<"${dept}") service(s) · ${n} desk(s)"
    done < <(jq -c '.[]' "${DATA}/departments.json")

    say "Branding and printed token"
    api PUT /branding "$(jq -c .branding "${DATA}/site.json")" >/dev/null
    api PUT /print-template "$(jq -c .print_template "${DATA}/site.json")" >/dev/null
    ok "Aarong orange, token slip: token, building, floor, department, producer code/name, service, category, time"

    say "Notice board on every floor"
    local starts ends
    starts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    ends="$(date -u -v+365d +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '+365 days' +%Y-%m-%dT%H:%M:%SZ)"
    while IFS= read -r zkey; do
        local zid
        zid="$(zone_id "${zkey}")"
        if api GET "/zones/${zid}/notices" | jq -e '.items | map(select(.active)) | length > 0' >/dev/null; then
            note "${zkey} has a notice"
        else
            api POST /notices "$(jq -c --arg z "${zid}" --arg s "${starts}" --arg e "${ends}" \
                '{zone_id: $z, type: "rich_text", content_i18n: .notice, starts_at: $s, ends_at: $e, sort_order: 0}' "${DATA}/site.json")" >/dev/null
            ok "${zkey}"
        fi
    done < <(jq -r '.zones[].key' "${DATA}/site.json")
}

# ---- users ---------------------------------------------------------------------------------------------------------

# ensure_user USERNAME DISPLAY_NAME ROLES_JSON USERS_JSON -> user id
ensure_user() {
    local id
    id="$(jq -r --arg u "$1" '.[] | select((.username | ascii_downcase) == ($u | ascii_downcase)) | .id' <<<"$4" | head -n1)"
    if [ -n "${id}" ]; then
        note "$1 exists"
    else
        id="$(api POST /users "$(jq -nc --arg u "$1" --arg p "${DEMO_PASSWORD}" --arg d "$2" --argjson r "$3" \
            '{username: $u, password: $p, display_name: $d, preferred_language: "en", roles: $r}')" | jq -r .id)"
        ok "$1 ($2)"
    fi
    printf '%s' "${id}"
}

cmd_users() {
    login_admin
    local sid users
    sid="$(site_id)"
    users="$(all_users)"

    say "Super Admin and Reception"
    ensure_user aarong.admin "Aarong Super Admin" '[{"role":"system_admin","site_ids":[],"group_ids":[]},{"role":"org_admin","site_ids":[],"group_ids":[]}]' "${users}" >/dev/null
    ensure_user aarong.reception "CS-1 Reception" "$(jq -nc --arg s "${sid}" '[{role: "reception_operator", site_ids: [$s], group_ids: []}]')" "${users}" >/dev/null

    say "Team Admins and Officers"
    while IFS= read -r dept; do
        local gid team
        gid="$(group_id "$(jq -r .prefix <<<"${dept}")")"
        [ -n "${gid}" ] || die "department $(jq -r .prefix <<<"${dept}") missing: run '$0 config' first"
        team="$(api GET "/service-groups/${gid}/team")"

        ensure_user "$(jq -r .team_admin.username <<<"${dept}")" "$(jq -r .team_admin.display_name <<<"${dept}")" \
            "$(jq -nc --arg s "${sid}" --arg g "${gid}" '[{role: "team_admin", site_ids: [$s], group_ids: [$g]}]')" "${users}" >/dev/null

        while IFS= read -r agent; do
            local uname uid
            uname="$(jq -r .username <<<"${agent}")"
            uid="$(ensure_user "${uname}" "$(jq -r .display_name <<<"${agent}")" \
                "$(jq -nc --arg s "${sid}" '[{role: "agent", site_ids: [$s], group_ids: []}]')" "${users}")"
            if ! jq -e --arg u "${uid}" '.members[]? | select(.user_id == $u)' <<<"${team}" >/dev/null; then
                api POST "/service-groups/${gid}/team/members" "$(jq -nc --arg u "${uid}" '{user_id: $u}')" >/dev/null
                ok "${uname} joined the $(jq -r .name_i18n.en <<<"${dept}") team"
            fi
        done < <(jq -c '.agents[]' <<<"${dept}")
    done < <(jq -c '.[]' "${DATA}/departments.json")
}

# ---- producers -----------------------------------------------------------------------------------------------------

cmd_producers() {
    login_admin
    say "Producer master list (data/producers.csv)"
    local report
    report="$(api POST /visitors/import "$(jq -Rs '{filename: "aarong-producers.csv", content: .}' "${DATA}/producers.csv")")"
    ok "$(jq -r '"\(.total_rows) rows: \(.inserted_count) new, \(.updated_count) updated, \(.failed_count) failed"' <<<"${report}")"
}

# ---- appointments --------------------------------------------------------------------------------------------------

# visitor_id PRODUCER_CODE TOKEN -> the producer's id, or nothing when the code is unknown
visitor_id() {
    local out
    out="$(api_try GET "/visitors/lookup?q=$1" "" "$2")"
    [ "$(status_of "${out}")" = 200 ] && body_of "${out}" | jq -r '.id // empty'
    return 0
}

cmd_appointments() {
    login_admin
    local days reception
    if [ "${HOURS}" = realistic ]; then days='[7,1,2,3,4]'; else days='[1,2,3,4,5,6,7]'; fi
    reception="$(login aarong.reception "${DEMO_PASSWORD}")"

    say "Phone-booking slots (Merchandising and Design)"
    local codes=(0062 0071 0117 0136 0194 0258) k=0 existing
    # There is no staff API that lists bookings, so ask the database whether an earlier run already booked some.
    existing="$(psql_cmd -tAX -c "SELECT count(*) FROM appointment WHERE purpose_note = 'Booked by phone (Aarong demo)' AND state IN ('booked', 'rescheduled', 'checked_in')" 2>/dev/null || echo 0)"
    while IFS= read -r dept; do
        local gid
        gid="$(group_id "$(jq -r .prefix <<<"${dept}")")"
        while IFS= read -r sid; do
            local sname
            sname="$(api GET "/services/${sid}" | jq -r .name_i18n.en)"
            api PUT "/services/${sid}/appointment-settings" '{"booking_horizon_days":14,"min_lead_time_minutes":0,"waitlist_enabled":false}' >/dev/null
            api PUT "/appointment-templates/service/${sid}" "$(jq -nc --argjson d "${days}" \
                '{items: [$d[] | {weekday: ., start: "10:00", end: "16:00", slot_minutes: 30, capacity: 2}]}')" >/dev/null
            ok "${sname}: 30-minute slots 10:00-16:00, 2 producers per slot"

            # Book two producers into the next free slots today (or tomorrow once today's slots are gone).
            local date slots booked=0
            [ "${existing:-0}" -gt 0 ] && booked=2
            for date in "$(today)" "$(TZ="${SITE_TZ}" date -v+1d +%Y-%m-%d 2>/dev/null || TZ="${SITE_TZ}" date -d tomorrow +%Y-%m-%d)"; do
                [ "${booked}" -ge 2 ] && break
                slots="$(api GET "/services/${sid}/appointments/availability?date=${date}" "" "${reception}")"
                while IFS= read -r slot; do
                    [ "${booked}" -ge 2 ] && break
                    local code vid out
                    code="${codes[$((k % ${#codes[@]}))]}"
                    k=$((k + 1))
                    vid="$(visitor_id "${code}" "${reception}")"
                    [ -n "${vid}" ] || { warn "producer ${code} not found: run '$0 producers' first"; continue; }
                    out="$(api_try POST /appointments "$(jq -nc --arg s "${sid}" --arg d "${date}" --arg v "${vid}" --argjson slot "${slot}" \
                        '{service_id: $s, date: $d, start: $slot.start, end: $slot.end, source: "phone", visitor_id: $v, purpose_note: "Booked by phone (Aarong demo)", language: "en"}')" "${reception}")"
                    if [ "$(status_of "${out}")" -lt 300 ]; then
                        booked=$((booked + 1))
                        ok "  producer ${code} booked ${date} $(jq -r .start <<<"${slot}") · ref $(body_of "${out}" | jq -r .reference_code)"
                    else
                        note "  producer ${code} not booked ($(body_of "${out}" | jq -r '.code // .error // "refused"' 2>/dev/null)) — likely already booked on an earlier run"
                    fi
                done < <(jq -c '.slots[]' <<<"${slots}")
            done
        done < <(api GET "/service-groups/${gid}/services" | jq -r '.items[].id')
    done < <(jq -c '.[] | select(.appointments)' "${DATA}/departments.json")
    [ "${existing:-0}" -gt 0 ] && note "${existing} demo bookings already exist, so none were added"
    return 0
}

# ---- history -------------------------------------------------------------------------------------------------------

psql_cmd() {
    if [ -n "${PSQL:-}" ]; then
        # shellcheck disable=SC2086
        ${PSQL} "$@"
    else
        # compose needs QMS_DB_PASSWORD only to read the file; deploy/.env or the shell usually provides it.
        QMS_DB_PASSWORD="${QMS_DB_PASSWORD:-unused}" docker compose -f "${COMPOSE_FILE}" exec -T postgres psql -U qms -d qms "$@"
    fi
}

cmd_history() {
    say "Backfilling ${HISTORY_DAYS} days of history (Sun-Thu, 09:00-17:00)"
    {
        echo '\set ON_ERROR_STOP on'
        echo 'CREATE TEMP TABLE demo_params (site_code text, days int, seed double precision);'
        printf "INSERT INTO demo_params VALUES ('%s', %d, 0.42);\n" "${SITE_CODE}" "${HISTORY_DAYS}"
        echo 'CREATE TEMP TABLE demo_volume (prefix text, per_hour int);'
        jq -r '.[] | "INSERT INTO demo_volume VALUES (\(.prefix | @sh), \(.hourly_volume));"' "${DATA}/departments.json"
        cat "${HERE}/history.sql"
    } | psql_cmd -v ON_ERROR_STOP=1 -q -X
    ok "done; the Reports pages pick it up within 15 seconds"
}

# ---- live ----------------------------------------------------------------------------------------------------------

cmd_live() {
    login_admin
    local reception day
    reception="$(login aarong.reception "${DEMO_PASSWORD}")"
    day="$(today)"

    say "Officers open their desks"
    local fresh=""
    while IFS= read -r dept; do
        while IFS= read -r uname; do
            local tok cur options cid sids out
            tok="$(login "${uname}" "${DEMO_PASSWORD}")"
            cur="$(api_try GET /sessions/current "" "${tok}")"
            if [ "$(status_of "${cur}")" = 200 ]; then
                note "${uname} already at $(body_of "${cur}" | jq -r .counter.label)"
                continue
            fi
            options="$(api GET /sessions/options "" "${tok}")"
            cid="$(jq -r '[.items[] | select(.occupied | not)] | sort_by(.counter.label | tonumber? // 0) | .[0].counter.id // empty' <<<"${options}")"
            [ -n "${cid}" ] || { warn "${uname}: no free desk"; continue; }
            sids="$(jq -c --arg c "${cid}" '[.items[] | select(.counter.id == $c) | .services[].id]' <<<"${options}")"
            out="$(api POST /sessions "$(jq -nc --arg c "${cid}" --argjson s "${sids}" '{counter_id: $c, service_ids: $s}')" "${tok}")"
            ok "${uname} opened $(jq -r .counter.label <<<"${out}")"
            fresh="${fresh} ${uname}"
        done < <(jq -r '.agents[].username' <<<"${dept}")
    done < <(jq -c '.[]' "${DATA}/departments.json")

    say "Producers arrive: Reception issues tokens"
    local codes=(0062 0084 0095 0103 0128 0149 0152 0167 0173 0188 0205 0216 0229 0244 0263 0277 0282 0296 0301 0315 0328 0334 0347 0359)
    local k=0 distant
    distant="$(api GET /priority-classes | jq -r '.items[] | select(.name_i18n.en == "Distant-district producer") | .id' | head -n1)"
    while IFS= read -r dept; do
        local prefix gid count i
        prefix="$(jq -r .prefix <<<"${dept}")"
        gid="$(group_id "${prefix}")"
        count="$(jq -r '[(.hourly_volume / 2 | ceil), 1] | max | [., 4] | min' <<<"${dept}")"
        local sids=()
        while IFS= read -r s; do sids+=("${s}"); done < <(api GET "/service-groups/${gid}/services" | jq -r '.items[].id')
        for ((i = 1; i <= count; i++)); do
            local code vid body issued pri=""
            code="${codes[$((k % ${#codes[@]}))]}"
            k=$((k + 1))
            vid="$(visitor_id "${code}" "${reception}")"
            [ $((k % 7)) -eq 0 ] && pri="${distant}"
            body="$(jq -nc --arg s "${sids[$(((i - 1) % ${#sids[@]}))]}" --arg v "${vid}" --arg p "${pri}" \
                '{service_id: $s, visitor_id: (if $v == "" then null else $v end), priority_class_id: (if $p == "" then null else $p end)}')"
            issued="$(IDEMPOTENCY_KEY="aarong-live-${day}-${prefix}-${i}" api POST /tickets "${body}" "${reception}")"
            note "$(jq -r '"\(.token_number)  \(.service_group.name_i18n.en // .service_group.name // "") · \(.service.name_i18n.en // .service.name // "")"' <<<"${issued}") · producer ${code}"
        done
    done < <(jq -c '.[]' "${DATA}/departments.json")

    if [ -z "${fresh}" ]; then
        note "desks were already open, so no tokens were served this run"
        return
    fi

    say "Some Officers serve their first producers"
    local tea n=0
    tea="$(api GET /break-types | jq -r '.items[] | select(.name_i18n.en == "Tea") | .id' | head -n1)"
    for uname in ${fresh}; do
        local tok sess sid
        tok="$(login "${uname}" "${DEMO_PASSWORD}")"
        sess="$(api GET /sessions/current "" "${tok}")"
        sid="$(jq -r .id <<<"${sess}")"
        n=$((n + 1))
        case "$((n % 4))" in
            1) # called, served and completed one; the next is being served now
                if [ "$(jq -r .can_call <<<"${sess}")" = true ]; then
                    local called
                    called="$(api_try POST "/sessions/${sid}/next" "" "${tok}")"
                    if [ "$(status_of "${called}")" = 200 ] && [ "$(body_of "${called}" | jq -r '.ticket.id // empty')" != "" ]; then
                        api POST "/sessions/${sid}/serve" "" "${tok}" >/dev/null
                        api POST "/sessions/${sid}/complete" '{}' "${tok}" >/dev/null
                        note "${uname} completed $(body_of "${called}" | jq -r .ticket.token_number)"
                        called="$(api_try POST "/sessions/${sid}/next" "" "${tok}")"
                        if [ "$(status_of "${called}")" = 200 ] && [ "$(body_of "${called}" | jq -r '.ticket.id // empty')" != "" ]; then
                            api POST "/sessions/${sid}/serve" "" "${tok}" >/dev/null
                            note "${uname} is serving $(body_of "${called}" | jq -r .ticket.token_number)"
                        fi
                    fi
                fi
                ;;
            2) # just called a token: it is on the TV now
                local called
                called="$(api_try POST "/sessions/${sid}/next" "" "${tok}")"
                if [ "$(status_of "${called}")" = 200 ] && [ "$(body_of "${called}" | jq -r '.ticket.id // empty')" != "" ]; then
                    note "${uname} called $(body_of "${called}" | jq -r .ticket.token_number)"
                fi
                ;;
            *) ;;
        esac
    done
    # One Officer on a tea break, so the dashboard and break report show it.
    local tok sid
    tok="$(login finance.kabir "${DEMO_PASSWORD}")"
    sid="$(api GET /sessions/current "" "${tok}" | jq -r 'select(.break == null and .ticket == null) | .id')"
    if [ -n "${sid}" ] && [ -n "${tea}" ]; then
        api POST "/sessions/${sid}/break" "$(jq -nc --arg b "${tea}" '{break_type_id: $b}')" "${tok}" >/dev/null
        note "finance.kabir is on a Tea break"
    fi
}

# ---- devices -------------------------------------------------------------------------------------------------------

cmd_devices() {
    login_admin
    local sid devices wait="${1:-}"
    sid="$(site_id)"
    devices="$(api GET /devices)"

    say "Pairing codes (valid 10 minutes, each works once)"
    printf '\n  %-32s %-8s %-10s %s\n' DEVICE KIND CODE "OPEN THIS ON THE DEVICE" >&2
    local pending=0
    while IFS= read -r dev; do
        local label kind zkey zid body code
        label="$(jq -r .label <<<"${dev}")"
        kind="$(jq -r .kind <<<"${dev}")"
        if jq -e --arg l "${label}" '.items[] | select(.label == $l and .active)' <<<"${devices}" >/dev/null; then
            printf '  %-32s %-8s %-10s %s\n' "${label}" "${kind}" "paired" "" >&2
            continue
        fi
        zkey="$(jq -r '.zone // empty' <<<"${dev}")"
        if [ -n "${zkey}" ]; then
            zid="$(zone_id "${zkey}")"
            body="$(jq -nc --arg k "${kind}" --arg s "${sid}" --arg z "${zid}" --arg l "${label}" '{kind: $k, site_id: $s, zone_id: $z, label: $l}')"
        else
            body="$(jq -nc --arg k "${kind}" --arg s "${sid}" --arg l "${label}" '{kind: $k, site_id: $s, label: $l}')"
        fi
        code="$(api POST /devices/pairing-codes "${body}" | jq -r .code)"
        printf '  %-32s %-8s \033[1m%-10s\033[0m %s\n' "${label}" "${kind}" "${code}" "${BASE_URL%/}/${kind}/" >&2
        pending=$((pending + 1))
    done < <(jq -c '(.kiosks[] | . + {kind: "kiosk"}), (.displays[] | . + {kind: "display"})' "${DATA}/devices.json")
    echo >&2

    if [ "${wait}" = --wait ] && [ "${pending}" -gt 0 ]; then
        say "Waiting for the devices to pair (Ctrl-C to stop)..."
        local before paired
        before="$(jq '[.items[] | select(.active)] | length' <<<"${devices}")"
        while :; do
            sleep 5
            paired="$(api GET /devices | jq '[.items[] | select(.active)] | length')"
            if [ "${paired}" -gt "${before}" ]; then
                ok "$((paired - before)) newly paired"
                cmd_displays
                before="${paired}"
            fi
        done
    elif [ "${pending}" -gt 0 ]; then
        note "after pairing the TVs, run: $0 displays"
    fi
}

cmd_displays() {
    login_admin
    say "TV layout: serving table + notice board, next-token strip, English/Bangla"
    local any=0
    while IFS= read -r did; do
        any=1
        api PUT "/devices/${did}/display-config" '{
            "layout": "split_media", "layout_config": {"split_percent": 60},
            "columns": ["token", "counter", "service", "staff"], "next_n": 6,
            "language_cycle": ["en", "bn"], "language_cycle_seconds": 10, "highlight_seconds": 10,
            "assignment": {"scope": "zone", "ids": []}}' >/dev/null
        ok "$(api GET "/devices/${did}" | jq -r .label)"
    done < <(api GET /devices | jq -r '.items[] | select(.kind == "display" and .active) | .id')
    [ "${any}" = 1 ] || note "no paired display yet: run '$0 devices', pair a TV, then run this again"
}

# ---- walkthrough helpers (steps with no screen in the apps yet) ----------------------------------------------------

cmd_bookings() {
    say "Today's demo phone bookings"
    psql_cmd -X -P pager=off -c "SELECT a.reference_code AS reference, to_char(a.slot_start, 'HH24:MI') AS slot, s.name_i18n ->> 'en' AS service,
               v.external_code AS producer, v.name, a.state
        FROM appointment a JOIN service s ON s.id = a.service_id LEFT JOIN visitor v ON v.id = a.visitor_id
        WHERE a.slot_date = (now() AT TIME ZONE '${SITE_TZ}')::date ORDER BY a.slot_start, s.name_i18n ->> 'en'"
}

cmd_checkin() {
    local ref="${1:-}" reception out
    [ -n "${ref}" ] || die "usage: $0 checkin REFERENCE_CODE (see '$0 bookings')"
    reception="$(login aarong.reception "${DEMO_PASSWORD}")"
    out="$(api_try POST /appointments/check-in "$(jq -nc --arg r "${ref}" '{reference_code: $r}')" "${reception}")"
    [ "$(status_of "${out}")" -lt 300 ] || die "check-in refused: $(body_of "${out}")"
    body_of "${out}" | jq -r '"\(.outcome // "checked in"): token \(.ticket.token_number // "-")"' >&2
}

cmd_team_request() {
    login_admin
    local lead gid uid out
    lead="$(login design.lead "${DEMO_PASSWORD}")"
    gid="$(group_id D)"
    uid="$(all_users | jq -r '.[] | select(.username == "merch.anwar") | .id')"
    [ -n "${gid}" ] && [ -n "${uid}" ] || die "run '$0 config' and '$0 users' first"
    say "design.lead asks for merch.anwar (Merchandising) to join the Design team"
    out="$(api_try POST /approvals "$(jq -nc --arg g "${gid}" --arg u "${uid}" '{type: "team_member", payload: {group_id: $g, user_id: $u}}')" "${lead}")"
    [ "$(status_of "${out}")" -lt 300 ] || die "request refused: $(body_of "${out}")"
    ok "request $(body_of "${out}" | jq -r .id) is $(body_of "${out}" | jq -r .status); approve it with: $0 approvals --approve"
}

cmd_approvals() {
    login_admin
    local pending
    pending="$(api GET "/approvals?status=pending")"
    say "Pending approval requests: $(jq length <<<"${pending}")"
    jq -r '.[] | "  \(.id)  \(.type)  \(.payload | tostring)  requested \(.created_at)"' <<<"${pending}" >&2
    if [ "${1:-}" = --approve ]; then
        while IFS= read -r id; do
            api POST "/approvals/${id}/approve" '{"reason":"Approved in the Aarong demo"}' >/dev/null
            ok "approved ${id}"
        done < <(jq -r '.[].id' <<<"${pending}")
    fi
}

# ---- logins --------------------------------------------------------------------------------------------------------

cmd_logins() {
    say "Demo logins (password for every account: ${DEMO_PASSWORD})"
    {
        printf 'ROLE\tUSERNAME\tNAME\tDEPARTMENT\tOPENS\n'
        printf 'Super Admin\taarong.admin\tAarong Super Admin\tall\t/admin/\n'
        printf 'Reception\taarong.reception\tCS-1 Reception\tall\t/admin/reception/\n'
        jq -r '.[] | . as $d
            | ("Team Admin\t\(.team_admin.username)\t\(.team_admin.display_name)\t\(.name_i18n.en)\t/admin/"),
              (.agents[] | "Officer\t\(.username)\t\(.display_name)\t\($d.name_i18n.en)\t/console/")' "${DATA}/departments.json"
    } | column -t -s $'\t' >&2
    echo >&2
    note "Admin ${BASE_URL%/}/admin/ · Console ${BASE_URL%/}/console/ · Kiosk ${BASE_URL%/}/kiosk/ · TV ${BASE_URL%/}/display/"
}

# ---- main ----------------------------------------------------------------------------------------------------------

main() {
    need curl
    need jq
    local cmd="${1:-all}"
    shift || true
    case "${cmd}" in
        all)
            cmd_config
            cmd_users
            cmd_producers
            cmd_appointments
            cmd_history
            cmd_live
            cmd_logins
            say "Next: pair the kiosks and TVs with '$0 devices --wait' (see aarong-onboarding.html, step 6)"
            ;;
        config) cmd_config ;;
        users) cmd_users ;;
        producers) cmd_producers ;;
        appointments) cmd_appointments ;;
        history) cmd_history ;;
        live) cmd_live ;;
        devices) cmd_devices "${1:-}" ;;
        displays) cmd_displays ;;
        logins) cmd_logins ;;
        bookings) cmd_bookings ;;
        checkin) cmd_checkin "${1:-}" ;;
        team-request) cmd_team_request ;;
        approvals) cmd_approvals "${1:-}" ;;
        -h | --help | help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0" ;;
        *) die "unknown command '${cmd}' (try --help)" ;;
    esac
}

main "$@"
