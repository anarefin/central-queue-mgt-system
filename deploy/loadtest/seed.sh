#!/usr/bin/env bash
# Seeds one Site/Zone/ServiceGroup/Service/Counter, a Reception Operator and an Agent, against a running stack
# (deploy/compose.yaml), then prints the environment variables deploy/loadtest/token-issuance.js needs.
#
# Usage:
#   QMS_BOOTSTRAP_ADMIN_USERNAME=admin QMS_BOOTSTRAP_ADMIN_PASSWORD='...' \
#     ./deploy/loadtest/seed.sh > deploy/loadtest/.env.loadtest
#   set -a; source deploy/loadtest/.env.loadtest; set +a
#   k6 run deploy/loadtest/token-issuance.js
#
# Requires: curl, jq. Idempotent to run more than once (each run makes a fresh Site so it never collides with an
# earlier run's data), but not needed more than once per database.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
API="${BASE_URL}/api/v1"
ADMIN_USERNAME="${QMS_BOOTSTRAP_ADMIN_USERNAME:?set QMS_BOOTSTRAP_ADMIN_USERNAME (the system_admin created on first boot)}"
ADMIN_PASSWORD="${QMS_BOOTSTRAP_ADMIN_PASSWORD:?set QMS_BOOTSTRAP_ADMIN_PASSWORD}"
SUFFIX="lt-$(date +%s)"

post() {
    local path="$1" token="$2" body="$3"
    curl -sS -f -X POST "${API}${path}" -H "Content-Type: application/json" ${token:+-H "Authorization: Bearer ${token}"} -d "${body}"
}

put() {
    local path="$1" token="$2"
    curl -sS -f -X PUT "${API}${path}" -H "Content-Type: application/json" ${token:+-H "Authorization: Bearer ${token}"} -d '{}'
}

echo "Logging in as the bootstrap System Administrator..." >&2
ADMIN_TOKEN=$(post "/auth/login" "" "{\"username\":\"${ADMIN_USERNAME}\",\"password\":\"${ADMIN_PASSWORD}\"}" | jq -r .access_token)

echo "Creating Site/Zone/ServiceGroup/Service/Counter..." >&2
SITE_ID=$(post "/sites" "$ADMIN_TOKEN" "{\"name\":\"Load test site\",\"code\":\"${SUFFIX}\",\"timezone\":\"Asia/Dhaka\",\"address\":\"Load test\",\"default_language\":\"en\",\"enabled_languages\":[\"en\",\"bn\"]}" | jq -r .id)
ZONE_ID=$(post "/sites/${SITE_ID}/zones" "$ADMIN_TOKEN" '{"name":"Hall","floor_label":"1st"}' | jq -r .id)
GROUP_ID=$(post "/sites/${SITE_ID}/service-groups" "$ADMIN_TOKEN" '{"name_i18n":{"en":"Load test group","bn":"লোড টেস্ট"},"token_prefix":"L"}' | jq -r .id)
SERVICE_ID=$(post "/service-groups/${GROUP_ID}/services" "$ADMIN_TOKEN" '{"name_i18n":{"en":"Load test service","bn":"লোড টেস্ট"},"token_prefix":"L","expected_minutes":5,"sla_wait_minutes":30,"channels":["reception","kiosk"],"booking_mode":"walk_in_only"}' | jq -r .id)
COUNTER_ID=$(post "/zones/${ZONE_ID}/counters" "$ADMIN_TOKEN" '{"label":"Load test desk"}' | jq -r .id)
put "/services/${SERVICE_ID}/counters/${COUNTER_ID}" "$ADMIN_TOKEN" >/dev/null

echo "Creating a Reception Operator and an Agent..." >&2
RECEPTION_USERNAME="reception-${SUFFIX}"
RECEPTION_PASSWORD="LoadTest-Reception-9"
post "/users" "$ADMIN_TOKEN" "{\"username\":\"${RECEPTION_USERNAME}\",\"password\":\"${RECEPTION_PASSWORD}\",\"display_name\":\"Load test reception\",\"preferred_language\":\"en\",\"roles\":[{\"role\":\"reception_operator\",\"site_ids\":[\"${SITE_ID}\"],\"group_ids\":[]}]}" >/dev/null

AGENT_USERNAME="agent-${SUFFIX}"
AGENT_PASSWORD="LoadTest-Agent-9"
AGENT_ID=$(post "/users" "$ADMIN_TOKEN" "{\"username\":\"${AGENT_USERNAME}\",\"password\":\"${AGENT_PASSWORD}\",\"display_name\":\"Load test agent\",\"preferred_language\":\"en\",\"roles\":[{\"role\":\"agent\",\"site_ids\":[\"${SITE_ID}\"],\"group_ids\":[]}]}" | jq -r .id)
post "/service-groups/${GROUP_ID}/team/members" "$ADMIN_TOKEN" "{\"user_id\":\"${AGENT_ID}\"}" >/dev/null

echo "Done. Export these before running k6:" >&2
cat <<ENV
export BASE_URL="${BASE_URL}"
export SERVICE_ID="${SERVICE_ID}"
export COUNTER_ID="${COUNTER_ID}"
export RECEPTION_USERNAME="${RECEPTION_USERNAME}"
export RECEPTION_PASSWORD="${RECEPTION_PASSWORD}"
export AGENT_USERNAME="${AGENT_USERNAME}"
export AGENT_PASSWORD="${AGENT_PASSWORD}"
ENV
