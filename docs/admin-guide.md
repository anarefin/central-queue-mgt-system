# Administrator guide (Phase 1 foundation)

Covers what tickets 01–04 deliver: running the system, signing in, users and roles, approvals, the audit log, keys and
language packs. It grows as later tickets add screens; today the Admin app has sign-in, sign-out and a health panel, and
user, approval and audit administration is available through the API (`/api/v1`).

## 1. Run the system

One command on one machine (Docker required):

```
QMS_DB_PASSWORD=<choose one> \
QMS_BOOTSTRAP_ADMIN_USERNAME=admin \
QMS_BOOTSTRAP_ADMIN_PASSWORD=<a strong password> \
docker compose -f deploy/compose.yaml up --build
```

Open `http://localhost:8080/admin/`. All five apps and the API share one origin, which the refresh cookie needs:
`/admin/`, `/console/`, `/kiosk/`, `/display/`, `/visitor/` and `/api/v1`.

- **First System Administrator.** With no users yet, the backend creates one from `QMS_BOOTSTRAP_ADMIN_USERNAME` and
  `QMS_BOOTSTRAP_ADMIN_PASSWORD`. The password must satisfy the password policy or the backend refuses to start. Once a
  user exists these variables are ignored; remove them from your environment after the first start.
- **Migrations run as a separate step.** The `migrate` service applies pending database migrations and exits before the
  backend starts. Run it alone with `docker compose run --rm migrate`. Migrations are forward-only and safe to re-run.
- **Health.** `/api/v1/health/live` (process is up), `/health/ready` (database answers) and `/health/dependencies`
  (per-dependency state). The Admin app shows the last one.
- **Secure cookie.** The refresh cookie is `Secure`. Browsers accept it on `localhost` and on HTTPS. For plain-HTTP
  evaluation on another host set `QMS_REFRESH_COOKIE_SECURE=false`, and never in production.

## 2. Configuration

Everything is an environment variable (or a Spring property). Secrets are never in source control (NFR-SEC-013).

| Setting | Default | Meaning |
|---|---|---|
| `QMS_DB_URL`, `QMS_DB_USER`, `QMS_DB_PASSWORD` | local Postgres | Database connection |
| `QMS_ISSUER` | `https://qms.local` | The `iss` claim of access tokens; set it per installation |
| `QMS_KEY_DIR` | `./keys` | Where the signing keys live. Keep it out of backups you share and out of source control |
| `QMS_SECURITY_LOCKOUT_MAX_ATTEMPTS` / `_DURATION` | `5` / `PT15M` | Failed sign-ins before a lock, and how long it lasts |
| `QMS_SECURITY_IDLE_ADMIN` / `_AGENT` | `PT30M` / `PT12H` | Idle timeout for admin roles and for agent consoles |
| `QMS_SECURITY_PASSWORD_MIN_LENGTH` | `12` | Password policy: minimum length |
| `QMS_SECURITY_PASSWORD_MIN_CHARACTER_CLASSES` | `3` | Of lower case, upper case, digit, other |
| `QMS_SECURITY_PASSWORD_HISTORY_COUNT` | `5` | A new password may not repeat one of the last N |
| `QMS_SECURITY_PASSWORD_MAX_AGE_DAYS` | `0` (never) | When set, sign-in reports `password_expired` after that many days |
| `QMS_SECURITY_KEY_ROTATION_OVERLAP` | `P1D` | How long a retired key still validates tokens |
| `QMS_I18N_PACK_DIR` | none | Extra or overriding language packs, see section 6 |
| `QMS_AUDIT_EXPORT_MAX_ROWS` | `100000` | Cap on one CSV export |

Two limits are enforced at startup and cannot be raised: access tokens last at most 15 minutes, and bcrypt cost is at
least 12.

## 3. Signing in

Staff sign in at `/admin/login/` with a username and password. The access token lives only in the browser's memory and
is renewed silently through an HttpOnly cookie a minute before it expires, so a page reload keeps you signed in until
the idle timeout. After the configured number of wrong passwords the account locks for 15 minutes; the message says how
long. Signing out ends the session on the server as well as in the browser.

Changing a password (`POST /api/v1/auth/password`) ends every existing session of that user.

## 4. Users, roles and approvals

The roles are fixed in code and match SRS §5.2: System Administrator, Organisation Admin, Team Admin, Agent and Reception
Operator. What each role may do cannot be edited; which users hold which role can. A role assignment names the sites or
service groups it covers; leaving both empty means organisation-wide.

- **Create, edit, disable, enable and assign roles** (`/api/v1/users`) are open to Organisation Admin and System
  Administrator. Nobody can grant more than they hold: only a System Administrator can create or manage one, and an admin
  scoped to particular sites can only give out roles scoped inside those sites. You cannot disable your own account.
- **Disabling a user** ends their sessions at once: their refresh tokens are revoked so no new access token can be minted.
  A token already issued keeps working for REST until it expires, at most 15 minutes. Closing their counter session and
  returning their tickets to the queue arrives with ticket 12.
- **Approvals** (`/api/v1/approvals`). A Team Admin asks to add a team member or allocate a counter; the request sits as
  *pending* until an Organisation Admin or System Administrator approves or rejects it, with an optional reason. Nothing
  changes before approval, and a Team Admin is never granted a higher role to make it happen.

## 5. Audit log

Every sign-in outcome, lockout, refresh-token reuse, password change, user change, role change, approval and audit
export is recorded with who, their role, source address, device, time, the thing changed, before and after values and
the reason. The log is append-only: the application has no way to edit or delete an entry and the database refuses it.

`GET /api/v1/audit` (Organisation Admin, System Administrator) searches by `actor_id`, `action` (exact, or a prefix
ending in `*` such as `auth.*`), `entity`, `entity_id` and a `from`/`to` time range, 50 to 200 rows a page with
`next_cursor`. Add `format=csv` to export; the export is itself an audited action. Cells that a spreadsheet could run as
a formula are neutralised. Passwords, tokens and OTPs are never stored in the log.

## 6. Language packs

English and Bangla ship complete. To add a language or change wording without a new release, put
`messages_<code>.properties` files (backend) in the directory `QMS_I18N_PACK_DIR` points at, and `<code>.json` plus an
`index.json` listing `{code, locale, numerals}` (web apps) under `deploy/i18n/web`. Files in that directory win over the
shipped text. A missing translation falls back to the site default language, never to a raw key. Token numbers always
show in Western Arabic digits.

## 7. Rotating the signing key

```
docker compose -f deploy/compose.yaml run --rm backend --spring.profiles.active=rotate-keys
```

adds a new key and retires the old one. Tokens signed with the old key keep validating for
`QMS_SECURITY_KEY_ROTATION_OVERLAP`; running backends pick up the new key within 30 seconds.
