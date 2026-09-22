# Administrator guide (Phase 1 foundation)

Covers what tickets 01–06 deliver: running the system, signing in, users and roles, approvals, the audit log, keys,
language packs, sites, zones and counters, and the service catalogue. It grows as later tickets add screens; today the
Admin app has sign-in, sign-out, a health panel, the site, zone and counter screen and the service catalogue screen. User, approval and audit administration is available
through the API (`/api/v1`).

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
| `QMS_QUEUE_PRIMARY_TOLERANCE_MINUTES` | `5` | How many minutes of score a fallback counter link may trail the best ticket by and still lose to a primary link, section 12 |
| `QMS_QUEUE_ANNOUNCE_REPEAT_LIMIT` | `3` | How many times an agent may Re-announce one ticket (F3); `0` switches it off, section 12 |
| `QMS_QUEUE_MISS_LIMIT` | `2` | How many times a ticket may be missed (F6) and return to the queue; the next Miss closes it as `no_show`, section 12 |
| `QMS_QUEUE_MISS_REENTRY_POSITION` | `after-n` | Where a missed ticket re-enters the queue: `front`, `after-n` or `back`, section 12 |
| `QMS_QUEUE_MISS_REENTRY_AFTER` | `3` | With `after-n`: how many tickets stay ahead of the missed one (at least 1), section 12 |
| `QMS_QUEUE_HOLD_LIMIT` | `3` | How many tickets one session may hold (F8) at once; `0` switches Hold off, section 12 |
| `QMS_QUEUE_CALL_TIMEOUT_SECONDS` | `90` | How long a called ticket may wait for its agent to act before the agent is prompted and may return it to the queue; `0` switches the prompt off, section 12 |
| `QMS_QUEUE_TRANSFER_HEADSTART_MINUTES` | empty | The Head start, in minutes, of the successor a transfer (F7) creates; empty means the predecessor's accrued wait, section 12 |

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

## 7. Sites, zones and counters

The physical hierarchy is **Site > Zone > Counter** (ADR-0002). An Organisation Admin or System Administrator sets it
up at `/admin/sites/` (linked from the Admin home page), with no code change and no restart, so a new site is usable the
moment it is saved.

- **Site**: name, a short unique code, an IANA timezone such as `Asia/Dhaka`, an address, a default language and an
  ordered list of enabled languages. The default language must be one of the enabled ones; every language must be
  installed (section 6). Timestamps are stored in UTC and the screen shows them in the site's own timezone.
- **Zone** (a waiting area in a site): a name, a floor label such as `Ground` or `3rd`, and an optional building label
  such as `Block B`. Buildings of one campus are zones' building labels, not separate sites.
- **Counter** (a serving position in a zone): a short display label of at most 30 characters, such as `Counter 3`, and an
  optional location note.
- **Deactivation is soft.** Nothing can be deleted, so tickets and reports keep resolving to a deactivated record.
  Deactivating a site also deactivates its zones and counters, and deactivating a zone also deactivates its counters.
  Reactivating a parent does not bring its children back; reactivate each one deliberately. A zone or counter cannot be
  added to, or reactivated under, an inactive parent.
- **Scope.** An Organisation Admin whose role is limited to some sites sees and changes only those sites, and cannot add
  a site. Every change is checked on the server, never only on the screen.
- **Audit.** Each create, edit, deactivation and reactivation is recorded (`site.*`, `zone.*`, `counter.*`) with before
  and after values; a deactivation carries its reason, and the entries for what it took with it say so.

API, all under `/api/v1` and needing the `config:org_sites_zones` permission: `GET|POST /sites`,
`GET|PATCH /sites/{id}`, `POST /sites/{id}/deactivate|activate`, `GET|POST /sites/{id}/zones`, `GET|PATCH /zones/{id}`,
`POST /zones/{id}/deactivate|activate`, `GET|POST /zones/{id}/counters`, `GET|PATCH /counters/{id}` and
`POST /counters/{id}/deactivate|activate`. `PATCH` leaves absent fields unchanged; an empty `building_label` or
`location_note` clears it.

## 8. Service catalogue

An Organisation Admin or System Administrator defines what visitors can queue for at `/admin/catalogue/` (linked from
the Admin home page): **Service group > Service**, the counters that serve each service, one team per group, and the
outcome codes agents record. Pick a site first; a group belongs to one site.

- **Service group** (a department or clinic): a name in each of the site's enabled languages, a token prefix of up to
  eight letters or digits, a display order and an active flag. Each group gets its one **team** when it is created.
- **Service**: a name per language, a token prefix, expected handling minutes, an SLA wait target in minutes, the
  channels it can be issued through (kiosk, reception, mobile app, appointment check-in), an optional kiosk icon and a
  display order, whether a visitor identifier is not required, optional or mandatory, and whether it takes appointments
  only, walk-ins only or both. Deactivating a group deactivates its services; reactivating a group does not bring them
  back. Nothing can be added to, or reactivated under, an inactive parent.
- **Counters.** Open a service's Counters to link the counters of the same site that serve it, each with a
  preference weight: 1 is the primary counter and a higher number a fallback. Weights can be changed and links removed.
- **Outcome codes.** Open a service's Outcome codes to add the results an agent can record on completion: a code of
  lower-case letters, digits and underscores (fixed once created, so reports keep their meaning) with a label per
  language. They can be relabelled, reordered and deactivated at any time without a release; none can be deleted.
- **Translations.** Every name and label has one input per enabled language. A blank translation only warns: it is saved
  and the site's default language is shown in its place until you add it, and the list marks the record "Missing
  translation". The site's default language must always have a text.
- **Deleting.** A service can be deleted only while no ticket refers to it; once tickets exist, only deactivation is
  allowed and the screen says so. An unused service is deleted together with its counter links and outcome codes.
- **Team.** An Organisation Admin adds and removes team members directly on the Team screen. A **Team Admin's**
  change is a request: they ask through `POST /api/v1/approvals` with `type` `team_member` and a payload of `group_id`
  and `user_id` (add `"action": "remove"` to remove), it does nothing while pending, and an Organisation Admin
  approving it applies it. A request that cannot be applied (for example the user has since been disabled) stays
  pending and the approver sees why.
- **Scope.** An Organisation Admin limited to some sites sees and changes only the catalogue of those sites. Every
  change is checked on the server, never only on the screen.
- **Audit.** Every change is recorded (`service_group.*`, `service.*`, `outcome_code.*`, `team.*`) with before and after
  values; deactivations carry their reason and say when a parent caused them.

API, all under `/api/v1` and needing the `config:service_catalogue` permission: `GET|POST /sites/{id}/service-groups`,
`GET|PATCH /service-groups/{id}`, `POST /service-groups/{id}/deactivate|activate`, `GET|POST /service-groups/{id}/services`,
`GET /service-groups/{id}/counters`, `GET|PATCH|DELETE /services/{id}` (delete is refused with `conflict` once tickets
exist), `POST /services/{id}/deactivate|activate`,
`GET /services/{id}/counters`, `PUT|DELETE /services/{id}/counters/{counterId}`, `GET|POST /services/{id}/outcome-codes`,
`GET|PATCH /outcome-codes/{id}`, `POST /outcome-codes/{id}/deactivate|activate` and `GET /service-groups/{id}/team`.
Direct team changes, `POST /service-groups/{id}/team/members` and `DELETE /service-groups/{id}/team/members/{userId}`,
need `team_member:approve`. Names are objects keyed by language, `{"bn": "…", "en": "…"}`; responses list the enabled
languages still missing under `missing_translations`. `PATCH` leaves absent fields unchanged and replaces `name_i18n`
and `label_i18n` as a whole.

## 9. Token numbering

An Organisation Admin (or System Admin) opens **Token numbering** from the home screen, picks a site, and sets a rule
for a service group or for one service. A service's own rule wins over its group's; with neither, the default applies
(the service's prefix, separator `-`, 3 digits, restarting every day at 00:00). Each rule has:

- **Prefix comes from**: the service, the service group, the priority class (its prefix override, section 10; a class
  without one, and the default class, leave the service's prefix) or a fixed text of up to 8 letters and digits.
- **Separator**: any text up to 8 characters, or none. **Digits**: the least number of digits, 0 to 6; a longer number
  is never cut. **First number**: where each new sequence begins.
- **Restart the sequence**: every day, every week (Monday), every month, or never, at a **reset time** in the site's
  own time zone. A daily rule at 04:00 keeps counting past midnight and starts over at 04:00.

Two prefixes that produce the same text share one counter, so a Token number is never repeated within a site and reset
period. Token numbers always use Western Arabic digits, in every language.

- **Changing a rule never renumbers a ticket that was already issued.** The screen says how many tickets are waiting;
  they keep their numbers and only tickets issued afterwards follow the new rule. Removing a rule sends the scope back
  to its group's rule or the default. A change to the boundary or reset time made part-way through a period continues
  after the numbers already used in it.
- **Preview next number** shows what the next ticket would be called, and when the sequence next starts over,
  without issuing anything or using a number up.
- **Resets.** The backend checks every minute, on every node, and the node that takes the database lock opens the new
  period at the site-local reset time and writes a `numbering.reset` audit entry. If the backend was down at that
  moment, the next check opens the period late and records it as replayed; even with no check at all, the first ticket
  after the reset time opens the period, so a sequence never carries on into a new day. Earlier days' tickets and
  numbers are untouched.
- **Sequence blocks.** Each site reserves numbers for a sequence in blocks of 100, and asks for the next block once 80
  of the current one are used.
- **Audit.** Every rule change is recorded as `numbering_rule.created`, `.updated` or `.deleted` with before and after
  values.

API, all under `/api/v1` and needing the `config:service_catalogue` permission: `GET /sites/{id}/numbering-rules`,
`GET|PUT|DELETE /services/{id}/numbering-rule`, `GET|PUT|DELETE /service-groups/{id}/numbering-rule`,
`GET /services/{id}/numbering-preview` and `GET /service-groups/{id}/numbering-preview`. `PUT` replaces the rule as a
whole (a field left out takes its default: prefix from the service group, first number 1, 3 digits, daily, 00:00, `-`),
and `PUT` and `DELETE` answer with `affected_waiting_tickets`.

## 10. Priority classes and queue ordering

An Organisation Admin (or System Admin) opens **Priority and queue ordering** from the home screen. Everything on it
needs the `config:priority_routing` permission, and every change is audited with before and after values.

**Priority classes** are organisation-wide. Each has a name per language (English is required), a **head start** in
minutes, an optional **maximum wait** in minutes and an optional **token prefix override** (up to 8 letters and digits,
used when a numbering rule takes its prefix from the priority class). A head start is virtual waiting credited on
arrival: a visitor with a 20 minute head start is served ahead of everyone who has waited less than 20 minutes, and
behind everyone who has waited longer. The **default class** (Normal) always has a head start of 0, cannot be
deactivated and takes no prefix; give it a maximum wait to protect ordinary tickets from waiting too long. A class is
deactivated, never deleted: it is no longer offered at issue, and tickets that already carry it keep it.

**The order.** Waiting tickets are ordered by score, highest first:
`effective wait + head start + appointment bonus + escalation bonus + score adjustment`, all in minutes, so every
ticket's score grows at the same rate. Ties go to the earlier ticket, then the lower id. A ticket whose real wait passes
its class's maximum is **escalated**: it goes before every ticket that is not, however large their head start, and an
escalated ticket's negative score adjustment is set aside. Among escalated tickets the one furthest past its maximum goes
first. Escalated tickets are flagged in the queue snapshot (`escalated`), which the dashboard will use.

**Ordering strategy** is chosen per service group and applies from the next read of the queue; no waiting ticket is
changed. `weighted_wait` (the default) is the score above. `strict_priority` serves escalated tickets, then the class
with the larger head start, then first come first served. `fifo` is creation order only, with no escalation. There is
one logical queue per site and service: a ticket never moves between tables, only its state changes.

**Dry run.** Pick a service and run the dry run to see every waiting ticket in the order the engine computes, with each
term of its score, its class, its maximum wait and whether it is escalated or had its adjustment set aside. Another
strategy can be tried without saving it. Nothing is called or changed.

**Reception** chooses a priority class when it issues a ticket; leaving it on the default issues a normal ticket. The
queue at the desk shows a waiting ticket's class and flags one past its maximum wait.

API, all under `/api/v1`: `GET /priority-classes` (also for Reception, `ticket:issue`), `POST /priority-classes`,
`PUT /priority-classes/{id}` (replaces the whole class), `POST /priority-classes/{id}/deactivate|activate`,
`GET|PUT /service-groups/{id}/routing-strategy` and `GET /queues/{serviceId}/dry-run[?strategy=]`. `POST /tickets`
takes an optional `priority_class_id`; an unknown or deactivated class is refused with `validation_failed`.

**Where a new ticket's class comes from** (FR-QUE-011). When staff choose no class, the ticket takes the class of the first of
these that names one: the appointment's class, the class mapped to the visitor's category, the default of the issuing
channel, the default of the service; otherwise it is a normal ticket. Staff's own choice beats them all. The channel and
service defaults are set on the same screen under **Default classes**; the appointment and visitor category sources arrive
with the tickets that build appointments and visitor categories. A default whose class is later deactivated is passed
over. A default applies to tickets issued **from then on**: tickets already waiting keep the class they have, however
a default is changed afterwards (FR-CFG-041).

**Changing a waiting ticket's class** (FR-QUE-012, UAT U9). At the reception desk, beside each waiting ticket in the queue,
**Change priority** opens a form with the new class and a **mandatory reason**. Reception, Team Admins, Org Admins and System
Admins have the `ticket:reprioritise` permission, within their sites and service groups; Agents do not. The ticket keeps its
wait and only its class changes, so it moves by the difference of the head starts and the queue shows the new order from its
next read; consoles and displays are told by a `ticket.position_changed` event. Only a waiting ticket can change class.
The change is audited as `ticket.priority_changed` with the old and new class and the reason (FR-SEC-040).

**Cancelling a ticket** (SRS §19.1, §5.2). **Cancel ticket** beside a ticket in the queue closes it as `cancelled` (an
optional reason is kept in the audit log as `ticket.cancelled`). Any active ticket can be cancelled: waiting, paused, called,
serving or held. Reception, Team Admins, Org Admins and System Admins may cancel any ticket in their sites and service groups;
an Agent only their own, meaning a ticket their session has called, is serving or holds, or one meant for them. A ticket
that was in a session leaves it, and a session that was closing on that ticket closes. The wait, and the service time if
service had started, are stored on the ticket.

API additions, under `/api/v1`: `POST /tickets/{id}/priority` (body `priority_class_id` and `reason`), `POST /tickets/{id}/cancel`
(body `reason`, optional), both with an optional `If-Match` ticket version; `GET /priority-defaults`,
`PUT /priority-defaults/channels/{channel}` and `PUT /priority-defaults/services/{serviceId}` (body `priority_class_id`, `null`
clears the default), all with `config:priority_routing`.

## 11. Rotating the signing key

```
docker compose -f deploy/compose.yaml run --rm backend --spring.profiles.active=rotate-keys
```

adds a new key and retires the old one. Tokens signed with the old key keep validating for
`QMS_SECURITY_KEY_ROTATION_OVERLAP`; running backends pick up the new key within 30 seconds.

## 12. Counter sessions: calling, serving and completing

Agents work in the **console** (`/console/`). Nothing here is configured on a screen; it follows from the catalogue.

**Who may occupy a counter.** An agent (or Team Admin) may open a session on a counter if their team serves at least one
active service that is linked to it: the counter is linked to the service (section 8), the service's group has a team,
and the user is a member of that team. The sites in the user's token limit the counters further. The services the agent
may serve in a session are those links; they choose which of them to serve when they open it (all by default).

**One session per counter, one per agent.** A counter has at most one live session (`open`, `on_break` or `closing`),
enforced by the database as well as the API, and an agent sits at one counter at a time. Opening a counter that is
taken is refused with `conflict` and reason `counter_occupied`.

**Call next** (F2) picks, among the first waiting ticket of every queue the session serves, the highest score, so a
priority class or an escalation counts across queues. A counter's links have a preference weight (1 is primary). When the
best ticket on a fallback link beats the best on a primary link by no more than `QMS_QUEUE_PRIMARY_TOLERANCE_MINUTES`
minutes of score, the primary link is served; set it to 0 to always take the highest score. The ticket is bound to the
session in the same step, under a check on its version, so two counters calling at once never get the same ticket: one
of them is given the next. **Start service** (F4) and **Complete** (F5) act on the ticket this session holds. Completing
records an outcome from the ticket's service (required when the service has outcome codes; section 8) and an optional
note, and stores the ticket's `wait_seconds` (from joining the queue to being called) and `service_seconds`.

**Re-announce and Miss.** A called ticket can be re-announced (F3) or missed (F6); the word "recall" is not used. Re-announce
replays the call: the ticket stays `called` and bound to the session, `announce_count` goes up by one, and the event
`ticket.reannounced` is written and published. It is refused with `reannounce_limit_reached` once `announce_count` reaches
`QMS_QUEUE_ANNOUNCE_REPEAT_LIMIT`; the count belongs to the ticket and is not reset by a Miss, so that a display can tell each
announcement of a ticket apart. Miss declares the visitor absent: `miss_count` goes up by one and the counter is free at
once. While `miss_count` stays within `QMS_QUEUE_MISS_LIMIT` the ticket returns to `waiting`, its session binding is cleared
and `ticket.missed` is written; the Miss that would take `miss_count` past the limit closes it as `no_show` instead
(`ticket.no_show`). Agents never choose `no_show` themselves, and Re-announce does not count towards the miss limit. The
console warns before the Miss that would close a ticket. A returning ticket re-enters at `QMS_QUEUE_MISS_REENTRY_POSITION`:
at the `front`, `after-n` (behind that many waiting tickets, or at the back when fewer are waiting) or at the `back`. This is
done with a Score adjustment on the ticket, in whole minutes (section 10); `queued_at` is never rewritten, so the ticket's
original wait still counts and the waiting KPIs stay truthful. Every waiting score grows at the same rate, so the ticket
keeps that place as time passes. A ticket past its class's maximum wait is still served before every ticket that is not, so a
missed ticket cannot jump an escalated one. Each `ticket.missed` event records the adjustment applied
(`score_adjustment_minutes`) and the position that produced it. Under the `fifo` and `strict_priority` strategies the score
does not order the queue, so the position has no effect there (section 10). A ticket that is called again after a Miss stores
a `wait_seconds` that adds up only the time it spent in `waiting`, not the time it was called.

**Call timeout.** A called ticket that nobody acts on prompts its agent. After `QMS_QUEUE_CALL_TIMEOUT_SECONDS` (default 90, `0`
switches it off) a check that runs every few seconds on every node claims the call in the database, so it prompts once per call
however many nodes run: the agent's console gets `ticket.call_timeout` on the counter's topic and, after a refresh, the same
prompt from `ticket.call_timed_out` in the session. The ticket itself does not change and a prompt writes no ticket event. The
agent then keeps the ticket, or returns it to the queue (`POST /sessions/{id}/return`, the "Return to queue" button). A return is
refused with `call_not_timed_out` before the timeout has passed: an absent visitor before that is a Miss (F6), which counts. A
returned ticket goes back to `waiting` with its session binding cleared, `queued_at` untouched and no Miss counted, so its
original wait is preserved. Its place is restored with a Score adjustment (section 10): the adjustment the ticket had before the
call is set again, every waiting score grows at the same rate, and the ticket lands where it stood. `ticket.position_changed` is
written with `reason: call_timeout` and the `score_adjustment_minutes` applied. Re-announce does not reset the timeout. Returning
the last ticket of a closing session closes it.

**Calling a specific ticket out of order.** An agent can call a specific waiting ticket instead of the next one
(`POST /sessions/{id}/call` with `ticket_id` and a mandatory `reason`; the console's "Call a specific ticket", which lists the
waiting tickets of a chosen Service). The ticket must be `waiting`, in a Service the session serves and its counter still links, in
the agent's site and not meant for another counter or agent; anything else is refused with `ticket_not_callable`. The same
permission as any call is needed (`ticket:call_serve_complete`, own session only), and the desk must have room (below). The ticket
is bound to the session and `ticket.called` is written with `out_of_order: true` and the reason, and every call is audited as
`ticket.called_out_of_order` with the reason, the place the ticket had in its queue and the counter. A missing or blank reason,
or one over 1000 characters, is `validation_failed` naming `reason`. Everyone else keeps their place.

**Parallel serving.** A Service can be marked as one a counter serves to several visitors at once (`/admin/catalogue/`, the
service form: "A counter serves several visitors of this service at once" and "Most visitors a counter serves at once"; API
`parallel_serving` and `parallel_limit`, 1 to 20 and at least 2 while it is on; switching it on without a maximum allows two).
Without it, and by default, a counter has one ticket called or serving at a time and Call next is off meanwhile. With it, a
counter may have up to the maximum of that Service in progress; when the tickets in progress and the one to call are of
different Services, every one of them must have room, so a counter busy with a Service that is not parallel takes no second
ticket. Call next then skips the heads it cannot take yet and calls the best one it can; when the desk is full it is refused with
`ticket_in_progress`. An out-of-order call and a resume of a held ticket follow the same limit. With several tickets in progress
the session response lists them all in `tickets` (`ticket` is the first, `can_call` says whether a call would be taken) and each
action (`serve`, `complete`, `reannounce`, `miss`, `hold`, `return`) names its ticket with `?ticket_id=`; without it the action
is for the first. The console lists the tickets in progress and acts on the one chosen. Changes to the flag and the maximum are
audited with `service.updated` (before and after).

**Hold and held by me.** An agent can Hold (F8) the ticket in service to call the next visitor and come back to it later. The
ticket moves `serving` to `held`, stays bound to the session (so only that session can resume it), leaves the general queue,
and `ticket.held` is written and published; the counter is free to call next. Held tickets show in the console under "Held by
me", each with a Resume button; a resume is refused while another ticket is called or serving at the desk, and puts the ticket
back to `serving` (`ticket.serving`). A session may hold at most `QMS_QUEUE_HOLD_LIMIT` tickets at once (default 3, 0 switches
Hold off); one more is refused with `hold_limit_reached`. Hold is only offered on an `open` session.

**Closing** (F10) needs everything in progress to be resolved: the ticket called or serving, and every held ticket. With any
of them left the session becomes `closing`, takes no new calls and the request is refused with `ticket_in_progress` (a ticket
called or serving) or `held_tickets_remaining` (only held tickets left); the agent resumes and completes each one, and the
last completion closes the session. A session on a break (section 13) closes too: the break ends first and its time is kept.

**Transfer (F7).** An agent sends the visitor being served to another Service, to one of that Service's counters, or to one of
its agents, with a note the next agent reads; the note is mandatory (`validation_failed` naming `note`). The panel offers the
active Services of the session's site and, for the Service chosen, the counters that serve it and the agents on its team; a
counter or agent without a Service means the ticket's own Service. The ticket in service closes as `transferred`, which is
terminal, with its wait and service time stored and the note kept; its binding is cleared and the counter is free to call
next. In the same transaction a successor ticket is created in the target queue with the same token number and Visit, the same
Priority class, channel and ticket secret, and `predecessor_ticket_id` pointing at the ticket it replaces. The visitor never
sees a new number, and each wait is attributed to its own ticket's Service. The predecessor's wait stops at the transfer; the
successor's own wait starts there, and it is given a transfer Head start so the visitor is not sent to the back: by default
the predecessor's accrued wait, rounded to whole minutes and stored as the successor's Score adjustment, or the fixed number of
minutes in `QMS_QUEUE_TRANSFER_HEADSTART_MINUTES` (0 means no Head start). It stacks with the Priority class's own Head start.
A ticket sent to an agent waits in that agent's personal queue, and one sent to a counter in that counter's: no other counter
or agent draws it (call next skips it), and it stays there if the ticket is missed and returns to `waiting`. It is still
counted in the Service's queue and shown in its order. Reassigning a targeted ticket to someone else is not available yet, so a
ticket left waiting for an agent who is away stays until that agent calls it. Agents transfer only the ticket their own
session is serving; an Org or Team Admin, within their site and Service group scope, can transfer any ticket in service.
Transfers are intra-site: the target Service, counter and agent must belong to the ticket's site and be active, and a counter
or agent must be able to serve the Service. A refused transfer changes nothing. `ticket.transferred` is written on the
predecessor and published on its queue and counter, the successor's first event is `ticket.issued` (its payload names the
predecessor and the Head start) and is published on the target queue, and the audit log records `ticket.transferred` with the
note as its reason. Reports that count "tickets issued" count chain heads (`predecessor_ticket_id IS NULL`), not rows.

**Force-close.** When a device is stale (an agent walked away, a tablet died) an Org Admin or Team Admin, or a System Admin,
who holds the "open/close a counter session" permission for everyone's sessions, can force-close the session with
`POST /sessions/{id}/force-close` and an optional `reason`. Their token's sites and Service groups limit which sessions they
can reach; agents cannot, not even their own. The session becomes `force_closed` and its counter is free at once. Every ticket
it was calling, serving or holding returns to `waiting` at the front of its queue: the ticket's Score adjustment is set so it
lands ahead of every ticket that is not escalated (the ticket that joined the queue first ends up first), its binding is
cleared, `queued_at` and `miss_count` are untouched, and each ticket gets one `ticket.position_changed` event naming the
adjustment and `reason: session_force_closed`. The action is audited as `session.force_closed` (before, after with the tickets
returned, and the reason), and `session.closed` with state `force_closed` is published so the agent's console drops out of the
session. The time a ticket spent held or called never counts as wait (Invariant 1).

**Refresh and restart.** The session lives on the server and does not expire: after a browser refresh, a network loss or
a restart the console asks `GET /sessions/current` and shows the ticket the agent was serving.

API, all under `/api/v1`: `GET /sessions/options`, `GET /sessions/current` (`not_found` when there is none),
`POST /sessions` (`counter_id`, optional `service_ids`), `DELETE /sessions/{id}`, and `POST /sessions/{id}/next|call|reannounce|serve|complete|miss|return|hold|force-close`,
`GET /sessions/{id}/transfer-targets` (where the ticket in service may go) and `POST /tickets/{id}/transfer` (`service_id`, optional
`counter_id` or `agent_id`, and the mandatory `note`; it answers with the predecessor, the successor and the session).
`POST /sessions/{id}/break` starts a break (`{"break_type_id": ...}`) or, with no body, ends the one the session is on (section 13). `hold` holds the serving ticket, or resumes a held one when given `{"ticket_id": ...}`. The session response carries `ticket`
(the first in progress), `tickets` (all of them), `held` (the held-by-me list), `hold_limit`, `break` (the break being taken, or null),
`can_call` and `call_timeout_seconds`.
`reannounce`, `serve`, `complete`, `miss`, `return`, `hold` and `transfer` take the ticket's `version` as `If-Match`; a stale version is a `conflict` with reason
`version_mismatch`. Conflict reasons: `counter_occupied`, `agent_has_open_session`, `counter_inactive`, `session_not_open`,
`ticket_in_progress`, `held_tickets_remaining`, `no_ticket_waiting`, `no_ticket_called`, `no_ticket_serving`, `no_ticket_held`,
`reannounce_limit_reached`, `hold_limit_reached`, `transfer_target_inactive`, `transfer_cross_site`, `transfer_target_mismatch`,
`already_on_break`, `not_on_break`, `no_live_session`, `ticket_not_callable`, `call_not_timed_out`, `version_mismatch`. Events written per transition: `ticket.called`,
`ticket.reannounced`, `ticket.missed`, `ticket.no_show`, `ticket.serving`, `ticket.held`, `ticket.position_changed`,
`ticket.completed`, `ticket.transferred` (and `ticket.issued` for the successor); audit entries `session.opened`, `session.closed`, `session.force_closed`, `ticket.transferred`, `ticket.called_out_of_order`, `session.break_started`, `session.break_ended` and `agent.availability_changed`.
The counter's topic also carries `session.break_started`, `session.break_ended` and `ticket.call_timeout`.

## 13. Breaks and agent availability

**Break types** (`/admin/breaks/`, Org Admin and System Admin; `GET/POST /break-types`, `PUT /break-types/{id}`,
`POST /break-types/{id}/deactivate|activate`). A break type is what an agent picks when they press F9: a name in each language
(the default language, English, is required; a missing translation falls back to it) and an optional maximum duration in minutes
(1 to 1440; blank means no limit). Types are organisation-wide. They are deactivated, never deleted, so a break already taken keeps
its type; a deactivated type is no longer offered. Nothing is set up on a new install, so add at least one (lunch, prayer, meeting
and system issue are typical) before agents can take a break. Every change is audited (`break_type.created`, `.updated`,
`.deactivated`, `.activated`). Changing break types is organisation configuration, so it needs the same permission as sites and
zones; every role that runs a counter session may read the list.

**Taking a break (F9).** With the session open and no ticket called or serving, the agent presses F9, picks a type and starts the
break. The session becomes `on_break`: no new ticket is assigned from that moment (a call is refused with `session_not_open`),
the counter stays occupied, and the console shows the break, how long it has run and, once it passes its type's maximum, a
warning. A ticket held earlier stays held; it does not block a break. F9 again ends the break and the session is `open`. A
break is refused with `ticket_in_progress` while a ticket is called or serving, `already_on_break` if one is running, and
`session_not_open` on a session that is closing. Closing or force-closing a session on a break ends the break first. Every break
is recorded (`break_record`: session, type, start, end, and who started and ended it) and audited as `session.break_started` and
`session.break_ended` (the latter with the duration and whether it overran); the events `session.break_started` and
`session.break_ended` go to the counter's topic, so the console and any dashboard see them at once. The alert to the Team Admin for
a break that exceeds its maximum (FR-AGT-023) arrives with the dashboard; the report below already counts overruns.

**Setting an agent's availability** (`/admin/availability/`; Team Admin, Org Admin and System Admin, within their sites and Service
groups). `GET /agents/availability` lists the agents with a live session and their status: `available` (open), `on_break`,
`closing`. `PUT /agents/{agent_id}/availability` with `{"status": "on_break", "break_type_id": ..., "reason": ...}` starts a break
for the agent, and `{"status": "available"}` ends the one they are on (setting `available` on an agent who is already available
changes nothing). It acts on the agent's live session under the agent's own rules: a ticket in progress must be resolved first
(`ticket_in_progress`), and an agent with no session cannot be set (`no_live_session`, an unknown agent is `not_found`). An agent
cannot set anyone's availability, not even their own. Each change writes one audit entry, `agent.availability_changed`, with the
admin, the before and after status and the reason, and publishes the same session events, so the agent's console updates by itself.

**Break report** (`GET /break-report?from=&to=&agent_id=&break_type_id=`, Org Admin, Team Admin and System Admin; times are
ISO-8601 instants and the range is on when a break started). For each agent and break type it gives the count, total and average
duration in seconds, and how many ran longer than the type's maximum. Only breaks that have ended count. The report is limited to
the sites and Service groups in the caller's token.

## 14. Wait estimates

Every ticket the API returns, every queue view and every service list at reception shows an estimated wait (SRS §10.5,
FR-QUE-040 to FR-QUE-042, FR-ISS-005). It is a **rounded range** in minutes, such as "about 15–20 minutes", and is never one
figure or a promise: the range is five minutes wide and always holds the figure it rounds. It is worked out on every read
from the live queue, so nothing is stored and nothing needs configuring:

`estimate = tickets ahead ÷ max(open counters, 1) × average handling time`

- **Tickets ahead** is the place in the queue minus one for a ticket, and everyone waiting for the queue views (what a ticket
  issued now would be given). Paused tickets keep their place, so they count.
- **Open counters** are counters whose session is `open` and has chosen the Service. A counter on a break, closing, or serving
  only other Services does not count; with none open the divisor is 1.
- **Average handling time** is the mean service time of the last 20 completed tickets of the Service. While fewer than 5 have
  completed, the Service's **expected handling time** (section 8) is used instead, so set it to something honest.

A ticket that has left the queue has no estimate (`estimated_wait_minutes` is null). The API field is
`estimated_wait_minutes: {"low": 15, "high": 20}` on tickets, on `GET /queues/{service_id}` and on `GET /sites/{id}/services`.

Consoles and dashboards hear of changes on the `queue:{service_id}` topic. `queue.estimate_changed` carries `waiting_count`,
`open_counters` and the range a new ticket would get; it is published whenever a ticket joins, leaves or completes and whenever
a session opens, closes, goes on a break or comes back. `ticket.position_changed` carries `ticket_id`, `position` and that
ticket's own range, for each queued ticket whose place moved. The place last announced for each queued ticket is kept in
`ticket_position`; it is a cache of what subscribers were told, and the live place is always computed from the queue. (The
visitor's own `ticket:{ticket_id}` topic arrives with the visitor ticket page.)

## 15. What the console shows of the visitor, and the agent's own day

When an Agent's ticket is called, the console shows the token, the visitor's **name, code and category** where the ticket has a
visitor record, the Service, the **purpose note**, how long the visitor waited, the channel they came through, and whether they
came with an appointment (SRS §11.5, FR-AGT-030). A walk-in with no visitor record simply has no visitor lines. The same fields
ride on the tickets the Agent holds. (Appointment booking, in a later ticket, is still the only channel that has not yet filled
these; Reception's directory search, walk-in registration and note — §16 below — write the `visitor` table and
`ticket.purpose_note` today.)

**Which fields a role sees** is set in the backend configuration, per role, and is enforced on the server: a field outside the
role's set is left out of the API response, not hidden by the screen (FR-AGT-034, FR-SEC-020). A role with no entry sees the
full set, which is the Agent console default of §25.3. The fields are `code`, `name`, `category` and `purpose_note`; an entry
with no fields shows none. A caller with several roles sees what any one of them may.

```
qms.console.visitor-fields.agent=code,category,purpose_note     # this role never sees the name
qms.console.visitor-fields.team_admin=code,name,category,purpose_note
```

The backend refuses to start on an unknown role or field. A screen to change this without a restart arrives with the privacy
controls (ticket 54).

Completing a ticket records an **outcome** from the Service's list and an optional free-text **note** (FR-AGT-032). The note
is the Agent's; it is stored on the ticket next to, not over, the visitor's purpose note.

**The Agent's own day** (FR-AGT-040) sits on the console: tickets served today, tickets waiting for the Services of their open
session, their average service time and their break time. `GET /sessions/stats` answers with the caller's figures only
(`served`, `in_queue`, `average_service_seconds`, `break_seconds`, `as_of`): no other Agent's number, and nothing to rank by.
"Today" is the day at the Site, in the Site's time zone; a break in progress counts up to now.

## 16. Visitor directory and walk-in registration

Reception can search for a known visitor and issue a ticket on their behalf, or register a visitor the directory does not know
(SRS §8.3, §22.2). Both are on the Reception desk (`/admin/reception`), and both need the `reception_operator` role
(`visitor_pii:view` for the search, `ticket:issue` for registering and for issuing — the same permission Reception already has
to issue any ticket).

**Search** (`GET /visitors/lookup`, FR-ISS-020) takes one query — a typed code, a phone number, or whatever a QR scan reads —
and returns the visitor's directory record: code, name, category, phone. Reception picks "Use this visitor" to carry it onto
the ticket they are about to issue.

**Registration** (`POST /visitors`, FR-ISS-021) is for an unknown walk-in: name and phone are required, email, category and
purpose are optional. The response is the record plus a **pass reference** (`V-` and eight letters/digits, e.g. `V-7K3M9PQR`)
— hand it to the visitor as their pass, and it becomes their code for next time, so a returning walk-in is a known visitor on
their next visit. Registering writes a `visitor.registered` audit entry.

**Which optional fields are captured** is closed configuration, enforced on the server, not merely hidden by the screen
(FR-SEC-023): a field left out of `qms.visitor.registration-fields` is neither read from the request nor stored, even when the
caller sends it. `name` and `phone` are always captured — they are the minimum record itself.

```
qms.visitor.registration-fields=email,category,purpose   # the default: all three optional fields on
qms.visitor.registration-fields=category                 # email and purpose are dropped, not merely unshown
```

Once a visitor is selected or registered, Reception may also give the ticket a **Priority class** and a free-text **note**
visible only to the Agent who is called to it (`purpose_note`, FR-ISS-020) — issuing sends `visitor_id` and `purpose_note`
alongside the usual `service_id` and `priority_class_id` to `POST /tickets`. A registration's own `purpose` is not stored on
the visitor record (a visitor is reused across visits; a purpose belongs to one visit) — it is Reception's own reminder to
carry into that same note.

**The directory seam** (FR-INT-010, FR-INT-012) is a `VisitorDirectory` interface; this release ships one implementation, the
local `visitor` table. A future remote adapter (core banking CIF, HIS patient index, ERP supplier master) is addable behind the
same interface, ahead of the local one. Every implementation is given a hard time budget
(`qms.visitor.directory-timeout`, default 1.5 s, FR-INT-012): one that is slow or fails is abandoned, not waited on, and the
next directory — ultimately the local one — is tried instead (FR-INT-013). No queue operation ever calls the directory
directly: `POST /tickets` only ever takes a `visitor_id` Reception has already resolved, so a slow or unreachable directory
can never hold up issuing a ticket.

```
qms.visitor.directory-timeout=1500ms
```

## 17. Visitor CSV import

FR-INT-010's second v1 source for the visitor directory, alongside walk-in registration, is a CSV import of the client's
visitor master data (SRS §22.2, FR-INT-011). It needs `visitor_pii:view` — the same permission the directory's own search
already needs (§16); §5.2 has no row of its own for CSV import, and this is the closest fit already in the closed set. A
CSV import writes into the exact same `visitor` table a walk-in's pass reference does, upserted by `external_code`, so an
imported visitor is found through the same `GET /visitors/lookup` a walk-in is — CSV import is not a second
`VisitorDirectory` implementation, it is a second way rows reach the one table the existing implementation reads.

**Column mapping** (`GET`/`PUT /visitors/import/mapping`) is a single, admin-set mapping from the CSV's own header names
to the visitor fields: `external_code` and `name` are mandatory (an upsert needs a key, and every visitor has a name);
`phone`, `email` and `category` are optional — leaving one unmapped means that CSV carries no such column. The same
mapping is used by every manual upload and every scheduled pickup, since a scheduled run has no admin present to map
columns for it. Out of the box the mapping is `external_code`, `name`, `phone`, `email`, `category` — a CSV whose headers
already match needs no admin action at all.

**Manual upload** (`POST /visitors/import`, at `/admin/visitor-import/`) sends the file's raw text and runs it
immediately with the saved mapping, upserting each row and returning a validation report on the spot: how many rows were
seen, how many became a fresh visitor, how many updated one already known by its `external_code`, and every row skipped
for missing its `external_code` or `name`, by line number. Importing the same `external_code` again updates that visitor
in place; it is never duplicated.

**Scheduled folder pickup** (FR-INT-011) needs no manual action at all: any `*.csv` file waiting in
`qms.visitor.import.pickup-dir` is picked up, imported with the saved mapping exactly like a manual upload — only with
`source` `scheduled` and no actor — and then moved aside into a `processed/` or `failed/` subfolder next to it, so it is
never imported twice. It is off by default; how often the folder is checked is `qms.visitor.import.pickup-cron`, a cron
expression defaulting to `-` (disabled), the same convention `qms.numbering.scheduler.cron` uses.

```
qms.visitor.import.pickup-dir=/var/qms/visitor-imports
qms.visitor.import.pickup-cron=0 */15 * * * *
```

**Every run's report stays on record** (`GET /visitors/import/runs`, most recent first, and `GET
/visitors/import/runs/{id}` for one in full), since a scheduled run has nobody present to see its report synchronously.
A completed import writes a `visitor.import.completed` audit entry with its counts; changing the mapping writes
`visitor.import.mapping_updated`.

## 18. Device pairing and fleet management

An Org Admin or System Administrator (`config:org_sites_zones`, the same permission that manages Sites, Zones and
Counters — a device is physically scoped to one of them, SRS §20.2) pairs a kiosk or display, watches its health, and
can revoke it or push it a reload without touching the device itself (FR-OPS-011, FR-OPS-041, FR-OPS-042).

**Pairing** is a two-step handoff, never a shared secret typed into the device (FR-OPS-011). In the admin app
(`/admin/devices`), pick **New pairing code**, choose the kind — **Kiosk** (scoped to a Site) or **Display** (scoped to
a Zone within that Site) — and a label; the server returns an 8-character code (letters and digits only, no `0/O` or
`1/I/L`, so it reads and types cleanly) that expires in 10 minutes and can be redeemed once. Enter that code on the
device's own pairing screen; it exchanges the code for its own credential — an access token like any staff sign-in,
and a refresh token it keeps to itself, never shown again.

**A device's credential is its own**, never a staff one (NFR-SEC-005): stored hashed, rotated every time the device
silently refreshes, and revocable on its own without touching any other device. On a kiosk or display, the refresh
credential lives in the device's own storage rather than the cookie a staff browser gets, because a kiosk/display shell
is not a browser (API-017); the access token itself is always held in memory only, exactly like a staff session, and
is never written anywhere durable.

**The fleet list** (`/admin/devices`) shows every paired device with its site or zone, when it last reported in, its
app version, and a computed **connectivity**: `online` (heartbeat within the last 2.5 minutes), `stale` (up to 10
minutes), or `offline` beyond that or never seen (FR-OPS-041). Printer paper status is a Phase 2 field (SRS §22.6): it
has no place in this release.

**Revoke** ends a device's credential immediately: its refresh tokens are all revoked, and if it is connected to the
realtime hub right now, its connection is dropped at once, not at its access token's next expiry (FR-DSP-013,
`principal.changed`, the same mechanism disabling a staff user's account uses). A revoked device's own next heartbeat
or refresh attempt is refused; pairing it again needs a fresh code.

**Push reload or config change** sends a live command to a connected device over its own `device:{id}` realtime topic
(FR-OPS-042) without physical access to it: **Reload** tells it to reload itself; **push config update** tells it its
configuration changed and it should refetch `GET /config/bootstrap`. Pushing to a device that has been revoked is
refused (`conflict`, `device_inactive`).

**What a device itself loads** (`GET /config/bootstrap`, device-authenticated only) is everything it needs to render
without a second round trip: site branding and enabled languages, its own zone's layout and counters when it is a
display, and the site's active service tree — nothing here needs a staff permission, since the device authenticates
with its own kiosk/display role instead.
`visitor.import.mapping_updated`.

## 19. Reports

A System Admin, Org Admin or Team Admin (`reports:run_export`, §5.2) runs reports from `/admin/reports`: pick a Site,
then the detailed token report (SRS §16.1, ticket 48) — one row per Ticket with every timing, the Counter, Agent,
outcome and transfers, filtered by date range, Zone, Service group, Service, Agent, Priority class, channel and
visitor category, server-side paged and sortable by clicking any column header.

**Reports never run against the live queue.** They run against a separate `reporting` schema, refreshed from `ticket`
and its event log roughly every 15 seconds and never more than 60 seconds behind (FR-RPT-020, §16). A ticket that just
completed will not appear until the next refresh sweep; a heavy report can never slow down a counter calling its next
ticket, because the two never touch the same tables.

**"Tickets issued" always means chain heads**, not rows: a transfer closes the ticket in service and opens a
successor with the same token and visit (ADR-0006, ticket 15), so a visit that was transferred twice is three rows in
the detailed token report — three separate wait/service times, one per Service it actually queued for — but one
ticket issued, and each row's own **transfers** column says how many hops separate it from the chain's first ticket
(0 for the chain head itself).

`POST /reports/{key}/run` is the one endpoint every report answers through. Ticket 49 added CSV/XLSX/PDF export
(background for large exports, with an expiring download link) for `detailed-token`. Ticket 50 adds six more keys
below the detailed token report on the same `/admin/reports` screen — `visitor-flow`, `counter`, `agent`, `service`,
`department` and `site` — plus reuses the pre-existing `break` report (ticket 16) under this same catalogue. Ticket
51 will add the remaining §16.1 rows (appointment, journey, feedback, notification, audit) and the peak-hours/
staffing-gap planning views; any key outside the catalogue is `not_found`.

**The six operational reports (SRS §16.1, §15.2, §15.3, ticket 50).** Pick a report from the dropdown, choose a
**From** and **To** date (both required — a period comparison needs a bounded period to mirror), narrow with the
same Zone/Service group/Service/Agent/Priority class/channel/visitor-category filters the detailed token report
uses, and run it:

- **Visitor flow** — issued, served, cancelled and no-show counts by hour, day, month or year, plus the peak number
  of tickets waiting at once and, for the whole period, the abandonment rate and the mix of tickets by channel.
- **Counter** — sessions, open hours, tickets served, idle time and utilisation, one row per Counter.
- **Agent** — the full §15.2 KPI set per Agent: served, cancelled, average wait and service time, total service
  time, average break time, successful token rate, and login adherence (session open time against the Site's own
  configured business hours — the closest proxy this system has, since staff rostering itself is out of scope, §28.3).
- **Service**, **Department**, **Site** — volume, average and 90th-percentile wait, average handling time and SLA
  attainment, each one rolled up from the one below it (department rolls up Service, Site rolls up Department); the
  service report also breaks average/P90 wait down by hour of day.

Every report shows the requested period's own totals side by side with the immediately preceding period of the same
length, and the absolute and percentage change between them (FR-RPT-010, FR-MON-011) — a from/to spanning this
Monday through Sunday compares against the Monday through Sunday just before it. Percentiles are always computed
from the raw Ticket rows a filter reaches, never derived from an already-grouped average (FR-MON-010).

## 20. Operations: installation, upgrades, backups and diagnostics

Ticket 60, SRS §26. This section is the administrator-facing summary; `docs/ops/` carries the full procedures a
consultant runs at a site.

- **Installing and upgrading** — `docs/ops/installer.md`: single-node, multi-node and air-gapped modes, the offline
  artefact bundle (`deploy/bundle.sh` / `deploy/install.sh` / `deploy/windows/install.ps1`), the prerequisite checks
  every install and upgrade runs first (`deploy/preflight.sh`, including mandatory clock synchronisation,
  FR-OPS-002), and how an upgrade keeps every waiting Ticket and open Counter Session intact across a restart
  (FR-OPS-021).
- **Rollback and release notes** — `docs/ops/rollback-and-release-notes.md`: the template every release fills in for
  its own rollback path and any changed configuration default (FR-OPS-022, FR-OPS-023).
- **Backup and restore** — `docs/ops/backup-restore.md`: encrypted full and incremental (WAL-archive) backups
  including the configuration bundle and uploaded media, the restore procedure, and the acceptance drill that
  verifies RPO ≤ 5 min / RTO ≤ 60 min (FR-OPS-030, FR-OPS-031, NFR-SEC-012, NFR-AVL-003).
- **TLS and the kiosk/display packaged shell** — `docs/ops/tls-and-kiosk-display-shell.md`: the production TLS proxy
  config and per-platform kiosk lockdown (NFR-SEC-010, NFR-POR-003, NFR-SEC-052).
- **Site survey and hardware spec** — `docs/ops/site-survey-checklist.md`: the checklist to complete before
  installing at a site, and the hardware specification to hand a client's vendor (NFR-ENV-001..003, SRS §24).
- **Support diagnostics bundle** (FR-OPS-040, §26.5) — a System Administrator opens **Operations** from the admin
  home page (`/ops/`) and clicks **Download diagnostics bundle**. The zip carries `versions.txt` (application and
  runtime versions), `config.txt` (a fixed allow-list of non-secret configuration values — no property outside that
  list, and never anything that looks like a password or secret, ever leaves the server this way), and
  `recent-events.txt` (the last 200 audit-log entries). Downloading it is itself recorded in the audit log
  (`ops.diagnostics_exported`), so a support export is traceable like any other privileged read.

