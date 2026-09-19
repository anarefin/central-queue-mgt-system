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

The SRS names one further code, `service_closed` (§20.3 example, FR-ISS-003), which belongs to token issuance and is
added by ticket 07 with the rest of the issuance rejection reasons.

The client library also synthesises two codes that never come from the server: `network_error` (no response) and
`unexpected_response` (a reply that is not a §20.3 envelope, such as a proxy error page).
