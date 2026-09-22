// SRS §27.4 / §23.1 (NFR-PERF-001, NFR-PERF-003, NFR-CAP-002): load the running stack at 3x the client's expected
// peak for 30 minutes and confirm the performance targets hold with an error rate at or under 0.1%.
//
// "Expected peak" is NFR-CAP-002's own number: 60 ticket issuances per minute. 3x that is 180/minute = 3/second,
// sustained. Alongside it, a smaller steady stream of console actions (call -> serve -> complete) exercises
// NFR-PERF-003 (console actions acknowledge within 500ms at P95) under the same load, since a real peak has staff
// working the queue while it fills, not an empty queue accumulating tickets nobody serves.
//
// Usage (against a running deploy/compose.yaml stack; QMS_HTTP_PORT defaults to 8080):
//   k6 run deploy/loadtest/token-issuance.js
//   k6 run -e BASE_URL=http://localhost:8080 -e DURATION=30m deploy/loadtest/token-issuance.js
//
// A shorter run for a quick check of the harness itself (not the §27.4 acceptance run, which needs the full 30m):
//   k6 run -e DURATION=2m -e SMOKE=1 deploy/loadtest/token-issuance.js
//
// Setup (run once against an empty database before the load test, or point SITE_ID/SERVICE_ID/etc at an existing
// site): see deploy/loadtest/seed.sh, which creates one Site/Zone/ServiceGroup/Service/Counter and an Org Admin,
// prints their ids, and is meant to be sourced into the environment this script reads them from.
import http from "k6/http";
import { check, sleep } from "k6";
import { Rate, Trend } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const API = `${BASE_URL}/api/v1`;
const DURATION = __ENV.DURATION || "30m";
const SMOKE = __ENV.SMOKE === "1";

// 3x the NFR-CAP-002 peak of 60/minute = 180/minute = 3/second.
const ISSUANCE_RATE_PER_SECOND = Number(__ENV.ISSUANCE_RATE || 3);
// A modest console-action rate alongside it: not the point of this script (NFR-PERF-003 has its own targeted
// integration test, IT#consoleActionsAcknowledgeWithinHalfASecondAtP95AgainstALoadedQueue), but real enough that
// issuance isn't measured against an idle system.
const CONSOLE_RATE_PER_SECOND = Number(__ENV.CONSOLE_RATE || 1);

const SERVICE_ID = __ENV.SERVICE_ID;
const COUNTER_ID = __ENV.COUNTER_ID;
const AGENT_USERNAME = __ENV.AGENT_USERNAME;
const AGENT_PASSWORD = __ENV.AGENT_PASSWORD;
// POST /tickets (the reception adapter, ISSUE permission) is used rather than POST /kiosk/tickets so the harness
// needs only a staff login, not a device-pairing flow first; NFR-CAP-002's 60/minute peak is a total across
// whichever channels a real client issues through, not a claim specific to one adapter — the two share the same
// IssuanceService/engine path underneath (ADR-0001), so which REST adapter fronts the load test does not change
// what NFR-PERF-001 measures.
const RECEPTION_USERNAME = __ENV.RECEPTION_USERNAME;
const RECEPTION_PASSWORD = __ENV.RECEPTION_PASSWORD;

const issuanceLatency = new Trend("qms_issuance_latency_ms", true);
const consoleAckLatency = new Trend("qms_console_ack_latency_ms", true);
const issuanceErrors = new Rate("qms_issuance_errors");
const consoleErrors = new Rate("qms_console_errors");

export const options = {
    scenarios: {
        // NFR-PERF-001 / §27.4: token issuance at 3x peak, arrival-rate driven so the rate holds regardless of how
        // long an individual issuance call takes (a closed-loop VU-count model would silently throttle under load,
        // which is exactly what this test must not hide).
        issuance_at_three_times_peak: {
            executor: "constant-arrival-rate",
            rate: ISSUANCE_RATE_PER_SECOND,
            timeUnit: "1s",
            duration: DURATION,
            preAllocatedVUs: Math.max(20, ISSUANCE_RATE_PER_SECOND * 4),
            maxVUs: Math.max(50, ISSUANCE_RATE_PER_SECOND * 10),
            exec: "issueTicket",
        },
        // NFR-PERF-003: an agent working the console (call -> serve -> complete) throughout the same window.
        console_actions: {
            executor: "constant-arrival-rate",
            rate: CONSOLE_RATE_PER_SECOND,
            timeUnit: "1s",
            duration: DURATION,
            preAllocatedVUs: 5,
            maxVUs: 20,
            exec: "workCounter",
        },
    },
    thresholds: {
        // §27.4: error rate MUST NOT exceed 0.1% across the run.
        qms_issuance_errors: ["rate<0.001"],
        qms_console_errors: ["rate<0.001"],
        // NFR-PERF-001: token issuance completes in under 2s at P95.
        qms_issuance_latency_ms: ["p(95)<2000"],
        // NFR-PERF-003: console actions acknowledge within 500ms at P95.
        qms_console_ack_latency_ms: ["p(95)<500"],
        // §27.4's own error-rate target is measured by the two qms_*_errors metrics above, which know an empty
        // queue and this harness's single-shared-session race (see workCounter) are not errors; k6's built-in
        // http_req_failed treats any non-2xx as failed, so it is not used as a threshold here.
    },
};

function login(username, password) {
    const res = http.post(`${API}/auth/login`, JSON.stringify({ username, password }), {
        headers: { "Content-Type": "application/json" },
    });
    check(res, { "login succeeds": (r) => r.status === 200 });
    return res.json("access_token");
}

// The access token is short-lived (this deployment issues 15-minute tokens; SigningKeyStore's default), so a run
// longer than that needs its own token to outlive it. A real client refreshes silently in the background
// (createAuth/session.ts's own AuthSession); this harness's own re-login is the same idea, minimal:
// re-authenticate once the cached token is close to its 15-minute lifetime rather than trusting one token from
// setup() to survive a 30-minute run. Module-level state is per-VU in k6 (each VU gets its own module instance),
// so this cache is naturally scoped per simulated user, exactly like a real signed-in session.
const TOKEN_LIFETIME_MS = 15 * 60 * 1000;
const REFRESH_MARGIN_MS = 60 * 1000; // re-login a minute early rather than race the exact expiry instant
let receptionTokenCache = null; // { token, obtainedAt }
let agentTokenCache = null;

function freshReceptionToken() {
    const now = Date.now();
    if (!receptionTokenCache || now - receptionTokenCache.obtainedAt > TOKEN_LIFETIME_MS - REFRESH_MARGIN_MS) {
        receptionTokenCache = { token: login(RECEPTION_USERNAME, RECEPTION_PASSWORD), obtainedAt: now };
    }
    return receptionTokenCache.token;
}

function freshAgentToken() {
    const now = Date.now();
    if (!agentTokenCache || now - agentTokenCache.obtainedAt > TOKEN_LIFETIME_MS - REFRESH_MARGIN_MS) {
        agentTokenCache = { token: login(AGENT_USERNAME, AGENT_PASSWORD), obtainedAt: now };
    }
    return agentTokenCache.token;
}

export function setup() {
    if (!SERVICE_ID || !RECEPTION_USERNAME || !RECEPTION_PASSWORD) {
        throw new Error(
            "SERVICE_ID, RECEPTION_USERNAME and RECEPTION_PASSWORD are required (see deploy/loadtest/seed.sh). Example: " +
                "k6 run -e SERVICE_ID=<uuid> -e RECEPTION_USERNAME=... -e RECEPTION_PASSWORD=... " +
                "-e COUNTER_ID=<uuid> -e AGENT_USERNAME=... -e AGENT_PASSWORD=... deploy/loadtest/token-issuance.js"
        );
    }
    // setup() runs once, in its own VU, before the scenarios start; each scenario VU calls freshReceptionToken()/
    // freshAgentToken() itself on its first iteration (its own module-level cache starts empty), so setup()'s own
    // job is only to fail fast on bad credentials and to open the one shared counter Session, not to hand out a
    // token every VU would otherwise have to share past its 15-minute lifetime.
    login(RECEPTION_USERNAME, RECEPTION_PASSWORD);

    let sessionId = null;
    if (COUNTER_ID && AGENT_USERNAME && AGENT_PASSWORD) {
        const agentToken = login(AGENT_USERNAME, AGENT_PASSWORD);
        const session = http.post(`${API}/sessions`, JSON.stringify({ counter_id: COUNTER_ID }), {
            headers: { "Content-Type": "application/json", Authorization: `Bearer ${agentToken}` },
        });
        check(session, { "session opens": (r) => r.status === 201 });
        sessionId = session.json("id");
    }
    return { sessionId, smoke: SMOKE };
}

export function issueTicket() {
    const started = Date.now();
    const res = http.post(`${API}/tickets`, JSON.stringify({ service_id: SERVICE_ID }), {
        headers: {
            "Content-Type": "application/json",
            Authorization: `Bearer ${freshReceptionToken()}`,
            "Idempotency-Key": `loadtest-${__VU}-${__ITER}-${Date.now()}`,
        },
        tags: { name: "issue_ticket" },
    });
    const elapsed = Date.now() - started;
    issuanceLatency.add(elapsed);
    const ok = check(res, { "ticket issued (201)": (r) => r.status === 201 });
    issuanceErrors.add(!ok);
}

export function workCounter(data) {
    if (!data.sessionId) return; // no counter configured: issuance-only run
    const headers = { "Content-Type": "application/json", Authorization: `Bearer ${freshAgentToken()}` };
    const started = Date.now();
    const next = http.post(`${API}/sessions/${data.sessionId}/next`, null, { headers, tags: { name: "call_next" } });
    if (next.status === 200) {
        const serve = http.post(`${API}/sessions/${data.sessionId}/serve`, null, { headers, tags: { name: "serve" } });
        if (serve.status === 200) {
            http.post(`${API}/sessions/${data.sessionId}/complete`, JSON.stringify({ note: "load test" }), {
                headers,
                tags: { name: "complete" },
            });
        }
    }
    const elapsed = Date.now() - started;
    consoleAckLatency.add(elapsed);
    // This harness's console_actions scenario runs several concurrent iterations against the one session `setup()`
    // opened (a real deployment has one agent per session; k6 arrival-rate VUs simulate steady background load, not
    // a second agent) — so two iterations occasionally race the same session's "call next"/"complete", and the
    // loser gets a legitimate conflict response, not a system defect. An empty queue (204/404) and that harness-only
    // race (409) are both expected; only a 5xx or another unexpected 4xx counts against the error-rate threshold.
    const ok = [200, 204, 404, 409].includes(next.status);
    consoleErrors.add(!ok);
    sleep(1);
}
