# API error codes

Every API error uses the SRS §20.3 envelope. The `code` is drawn from the closed set below. Clients branch on `code`,
never on `message`. Adding a code means adding it here, to `ErrorCode.java`, to `packages/api-client/src/errors.ts`,
and a message under `error.<code>` / `errors.<code>` in both language packs (tests enforce the last three).

```json
{
  "error": {
    "code": "conflict",
    "message": "The request conflicts with the current state.",
    "message_i18n": { "en": "…", "bn": "…" },
    "details": { "optional": "structured, code-specific" },
    "trace_id": "018f…"
  }
}
```

- `message` is in the language resolved for the request: user preference → `Accept-Language` → site default → system
  default (FR-I18N-003).
- `message_i18n` carries every enabled language, so a client can re-render without another request.
- `trace_id` equals the `X-Trace-Id` response header and the `trace_id` member of the JSON log lines for that request.
- Base path `/api/v1`; bodies are UTF-8 JSON.

| Code | HTTP | Meaning |
|---|---|---|
| `unauthenticated` | 401 | No credentials were supplied for a protected endpoint. |
| `invalid_credentials` | 401 | Username or password is wrong. Deliberately the same for unknown users. |
| `token_invalid` | 401 | Access or refresh token is malformed, expired, revoked, already used, or fails validation. |
| `forbidden` | 403 | Authenticated, but the role or scope does not allow the action. |
| `account_locked` | 423 | Too many failed sign-ins; `details.retry_after_seconds` says how long. |
| `validation_failed` | 400 | Malformed JSON or invalid fields; `details.fields[]` lists `{field, code}`. |
| `not_found` | 404 | No such route or resource, or the caller may not know it exists. |
| `method_not_allowed` | 405 | The route exists but not for this HTTP method. |
| `unsupported_media_type` | 415 | The request `Content-Type` or `Accept` cannot be served. |
| `conflict` | 409 | The request conflicts with current state (duplicate username, already-decided approval, …). |
| `rate_limited` | 429 | Too many requests; honour `Retry-After`. |
| `internal_error` | 500 | Unexpected failure. The message never contains internal detail; use `trace_id` to find it in the logs. |
| `unavailable` | 503 | A dependency is down; `details.dependency` names it. |

The SRS names one further code, `service_closed` (§20.3 example, FR-ISS-003), which belongs to the issuance rules
(ticket 21). Until then `POST /tickets` refuses with `conflict` and `details.reason` set to `service_inactive`,
`channel_not_allowed` or `appointment_only`. `POST /tickets` also requires an `Idempotency-Key` header (`validation_failed`
naming `Idempotency-Key` when it is missing); reusing a key for a different request is a `conflict` with
`details.reason` `idempotency_key_reused`.

Counter sessions (`/sessions`, ticket 10) refuse with `conflict` and one `details.reason`: `counter_occupied`,
`agent_has_open_session`, `counter_inactive` (opening); `session_not_open` (the session is closing, closed or on a break; a call while on a break is refused with it);
`ticket_in_progress` (call next or close while a ticket is called or serving, or resume while one is); `held_tickets_remaining` (close
with only held tickets left, ticket 13); `no_ticket_waiting` (nothing to call);
`no_ticket_called` / `no_ticket_serving` (start, re-announce, miss or return with nothing called, or complete with nothing
serving); `call_not_timed_out` (return a called ticket before the call timeout has passed) and `ticket_not_callable` (call a specific
ticket that is not waiting, is in a Service the session does not serve, or waits in another agent's or counter's queue), ticket 17;
`ticket_in_progress` also refuses a call, an out-of-order call or a resume when the counter has as many tickets in progress as its
Services allow; `reannounce_limit_reached` (a ticket already re-announced as often as allowed, ticket 12); `hold_limit_reached` (the session holds as
many tickets as allowed) and `no_ticket_held` (resume a ticket this session does not hold), ticket 13; `session_not_open` also refuses
a force-close of a session that is already closed; `version_mismatch` (a
stale `If-Match`). A transfer (`POST /tickets/{id}/transfer`, ticket 15) also refuses with `conflict` and `no_ticket_serving` (the ticket is not in
service), `transfer_target_inactive` (the Service, its group or site, the counter or the agent is not active), `transfer_cross_site`
(a Service or counter of another site; transfers are intra-site, ADR-0002) or `transfer_target_mismatch` (the counter does not serve the
Service, or the agent is not on its team or does not work at the site). A missing or blank `note`, no target, the ticket's own Service
without a counter or agent, a counter and an agent together, an unknown target, or a note over 1000 characters is `validation_failed`
naming the field; another agent's ticket, or a role without the permission, is `forbidden`. A break (`POST /sessions/{id}/break`, and `PUT /agents/{id}/availability`, ticket 16) refuses with `conflict` and `already_on_break` (a break is running), `not_on_break` (ending
one when there is none), `ticket_in_progress` (a ticket is called or serving; a held ticket does not block a break), `session_not_open` (the session is closing, or a call is made
while on a break) or, for an admin setting availability, `no_live_session` (the agent has no session open); a missing or unknown, or deactivated, `break_type_id`, an unknown `status`, or a
break type with no name in the default language or a `max_minutes` outside 1 to 1440 is `validation_failed` naming the field; an agent is `forbidden` from another agent's session and
from setting availability, and an admin outside the agent's site or Service groups is `forbidden`. A session that is not the caller's is `forbidden`, an unknown one `not_found`; a chosen service the counter
does not offer, a missing or unknown outcome, a missing or blank `reason` or missing `ticket_id` on an out-of-order call, or a malformed `If-Match` is `validation_failed` naming the field;
a `parallel_limit` outside 1 to 20, or below 2 while `parallel_serving` is on, is `validation_failed` naming `parallel_limit`.

The client library also synthesises two codes that never come from the server: `network_error` (no response) and
`unexpected_response` (a reply that is not a §20.3 envelope, such as a proxy error page).
