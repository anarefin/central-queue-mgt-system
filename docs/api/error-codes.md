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
| `service_closed` | 409 | The Service is not taking tickets right now: outside hours, a holiday, past the channel's cut-off, or its daily cap is reached (`details.reason`). |
| `rate_limited` | 429 | Too many requests; honour `Retry-After`. |
| `internal_error` | 500 | Unexpected failure. The message never contains internal detail; use `trace_id` to find it in the logs. |
| `unavailable` | 503 | A dependency is down; `details.dependency` names it. |

`POST /tickets` (issuance rules, ticket 21, FR-CFG-020..023, FR-ISS-003, FR-ISS-004, API-090, FR-OPS-043) refuses with
`service_closed` and `details.reason` `outside_hours`, `holiday` (`details.holiday` names it), `past_cutoff`
(`details.cutoff_at`) or `cap_reached` (`details.daily_cap`; an administrator's own cap message, per language, replaces
the built-in text where they set one). It refuses with `unavailable` and `details.reason` `maintenance` while
maintenance mode is on (an administrator's own message, per language, replaces the built-in text where set); queued
tickets keep being served. It refuses with `conflict` and `details.reason` `service_inactive`, `channel_not_allowed` or
`appointment_only` (the Service, its group or site is inactive, does not offer the channel, or takes appointments
only), `no_agent_rostered` (the Service requires a rostered Agent and has none) or `duplicate_ticket`
(`details.policy`; the visitor already holds an active ticket for the Service under a `warn` or `block` duplicate
policy — `warn` is passed by resubmitting with `confirm_duplicate: true`). An unknown `visitor_id` is
`validation_failed` naming `visitor_id`. It refuses with `rate_limited` and `details.reason` `rate_limited`
(`details.limit`, `details.window_seconds`) once the actor's device (30/minute) or visitor (5/hour) rate limit is hit,
honouring `Retry-After`; staff and system actors are not rate-limited. `POST /tickets` also requires an
`Idempotency-Key` header (`validation_failed` naming `Idempotency-Key` when it is missing); reusing a key for a
different request is a `conflict` with `details.reason` `idempotency_key_reused`.

The issuance rules themselves (`GET`/`PUT /sites/{id}/hours`, `/services/{id}/hours`, `GET`/`POST`/`DELETE
/sites/{id}/holidays[/…]`, `GET`/`PUT /sites/{id}/issuance-cutoffs`, `GET`/`PUT /services/{id}/issuance-rule`, `GET`/`PUT
/issuance-settings`) need `config:org_sites_zones` for a Site's hours, holidays, cut-offs and the deployment-wide
settings, and `config:service_catalogue` for a Service's hours and rule; a caller without it, or whose sites do not
include the target (the deployment-wide settings need an organisation-wide caller with no site restriction at all), is
`forbidden`. An unknown Site, holiday or Service is `not_found`. `validation_failed` covers: `days` (an out-of-range or
repeated weekday, a missing open/close time, or open not before close); `date`, `name` or `close_time` on a holiday
(bad format, blank name over 100 characters, a close time only for a half-day); `minutes_before_close` (an unknown
channel, or outside 0 to 1440); `daily_cap` (outside 1 to 1,000,000); `duplicate_policy` (not `allow`, `warn` or
`block`); `device_limit_per_minute` / `visitor_limit_per_hour` (outside 1 to 100,000); and `cap_message_i18n` /
`maintenance_message_i18n` (a language not installed, or text over 500 characters). Adding a holiday on a date the Site
already has one is `conflict` with `details.reason` `holiday_exists`.

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

A change of a waiting ticket's class (`POST /tickets/{id}/priority`, ticket 18) refuses with `conflict` and `ticket_not_waiting` (the ticket is
not waiting), `same_priority_class` (it already has that class) or `version_mismatch` (a stale `If-Match`); a missing `priority_class_id` or
`reason`, a blank or over-long `reason` (1000 characters), or an unknown or deactivated class is `validation_failed` naming the field; a role
without `ticket:reprioritise` (an Agent), or a ticket outside the caller's sites or service groups, is `forbidden`. A staff cancel
(`POST /tickets/{id}/cancel`) refuses with `conflict` and `ticket_not_active` (the ticket has already closed) or `version_mismatch`; an Agent
cancelling a ticket that is not their own is `forbidden`. The default classes (`PUT /priority-defaults/...`) answer an unknown channel or
service with `not_found` and an unknown or deactivated class with `validation_failed` naming `priority_class_id`.

The client library also synthesises two codes that never come from the server: `network_error` (no response) and
`unexpected_response` (a reply that is not a §20.3 envelope, such as a proxy error page).
