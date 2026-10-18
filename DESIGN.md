# Cronbell — design

Status: **draft for v0.1**, revised 2026-10-10 to self-hosted first. This is the specification. Behaviour described here is what the tests
assert; change this file first, then the code.

Cronbell is a Steelreed product (name decided 2026-10-11). Page: https://steelreed.com/cronbell/, repository:
github.com/steelreed/cronbell.

## 1. What it does

Scheduled jobs fail silently: a job that never runs throws nothing and logs nothing. Cronbell
expects every job to report each run, knows when the next run is due, and alerts when a run is
missed, late, failing or unusually slow.

It is open source and **self-hosted first**: one Docker command gives anyone a working instance.
The hosted cloud version is how the project earns money (§10).

Users, in order of priority:

1. **Anyone with scheduled jobs, in any language**: Linux cron, shell scripts, backups, Docker
   containers, Kubernetes CronJobs, Node/Python/PHP/Go workers. They add one `curl` to the job.
2. **Spring Boot teams** add `cronbell-spring-boot-starter`. It finds every `@Scheduled`, Quartz and
   ShedLock job, registers it with its real schedule, and reports every run with no job code
   changes. This is the integration competitors do not have.

Self-hosting must take under a minute:

```
docker run -d -p 8080:8080 -v cronbell-data:/data ghcr.io/steelreed/cronbell
```

Non-goals for v0.1: uptime checks of URLs, log storage, status pages, Zalo (needs approved message
templates), on-call rotations, SSO.

## 2. Concepts

| Term | Meaning |
|---|---|
| **Account** | A person who signs in with email and password. There is no open registration: the first account comes from the setup, every other one from an invite. |
| **Instance admin** | The account made by the setup (or `CRONBELL_ADMIN_EMAIL`). The only one who can invite people to a project of their own. Being admin gives no access to other accounts' projects. |
| **Project** | A group of checks with its API keys and alert channels, isolated from every other project. Accounts reach projects through memberships (`project_members`, role `OWNER` or `MEMBER`); an account can be in several projects and works in one at a time (the session's current project). The setup account owns the `Default` project. |
| **Invite** | A single-use link that lets someone create an account (or use theirs). A *project invite* (from the instance admin) gives a new project they own; a *member invite* (from an owner) adds them to that owner's project as `MEMBER`. |
| **Check** | One job being watched. Identified publicly by a random UUID (`ping key`) and inside a project by a `slug`. |
| **Schedule** | Either a cron expression with a time zone, or a fixed period. |
| **Grace** | How long after the due time a run may still arrive before it counts as missed. |
| **Ping** | One message from a job: `start`, `success`, `fail`, or `log`. |
| **Run** | A `start` followed by its `success` or `fail`, or a lone `success`/`fail`. |
| **Channel** | Where alerts go: email, Telegram, Slack, Discord, ntfy, webhook. A check alerts every channel of its project, or only the channels it picked (§8, Routing). |

## 3. Check states

```
            first ping                 run missed / failed / too long
   NEW ───────────────────▶ UP ─────────────────────────────────────▶ DOWN
                             ▲                                          │
                             └──────────── successful run ──────────────┘
   PAUSED: set by the user; no alerts; the next ping returns it to UP.
```

- **Resume** (`POST /checks/{slug}/resume`) ends a pause without a ping: a check that was never
  pinged goes back to NEW; otherwise it is UP, any open run is dropped, and `due` is recomputed
  from now, so a long pause does not turn into an immediate MISSED. Resuming a check that is not
  paused changes nothing.

- **NEW**: created, never pinged. Never alerts. A check that stays NEW for 24 hours is flagged in
  the dashboard, not alerted.
- **UP**: the last run succeeded and the next run is not overdue.
- **DOWN**: one of the conditions in §4. An alert is sent on the transition UP → DOWN and on
  DOWN → UP, never repeatedly while the state holds (v0.1 has no reminder emails).

Each check stores `status`, `last_ping_at`, `last_success_at`, `last_start_at`, `next_due_at`,
`down_reason`, and the open run (`run_started_at`, `run_rid`).

Transitions per ping (decided 2026-10-10):

| Ping | NEW / PAUSED | UP | DOWN |
|---|---|---|---|
| `start` | UP, opens a run | opens a run | stays DOWN, opens a run |
| `success` | UP | UP | UP, alert (recovered) |
| `fail` | DOWN `FAILED`, alert | DOWN `FAILED`, alert | stays DOWN `FAILED`, no alert |
| `log` | no change | no change | no change |

A `fail` on a NEW or PAUSED check alerts: the first ping makes it UP, then the failure takes it
DOWN. `last_ping_at` is set by `start`, `success` and `fail`, not by `log`.

## 4. When a check goes DOWN

Let `due` be the next expected run time computed from the schedule after the last completed run
(`success` or `fail`). A NEW check has no `due`; its first ping sets it (a first `start` sets it
from the start time).

| Reason | Condition |
|---|---|
| `MISSED` | `now > due + grace` and no ping since `due` |
| `FAILED` | a `fail` ping arrived (any exit code ≠ 0 counts as fail) |
| `RUNNING_TOO_LONG` | a `start` arrived and neither `success` nor `fail` followed within `max_runtime` (default: grace) |
| `SLOW` | v0.2: duration above the p95 of the last 30 runs times a factor. Not in v0.1. |

Rules:

- Schedules are either a cron expression or a fixed period (at least 60 s). Cron expressions are
  standard 5-field Unix cron (`min hour day month weekday`, run at second 0), 6-field cron with a
  leading seconds field, or a macro (`@hourly`, `@daily`, `@weekly`, `@monthly`, `@yearly`).
- Schedule evaluation uses the check's time zone (default `UTC`), including daylight-saving gaps:
  a run that falls in a skipped hour is due at the first valid instant after it (the end of the
  gap). A run in a repeated hour is due once, at its first occurrence.
- A `success` that arrives early (before `due`) is accepted and moves `due` forward. For a period
  schedule `due` becomes the ping time plus the period. For a cron schedule, a run that completes
  up to 60 s before `due` (clock skew) counts as the run for `due`, so `due` moves to the cron time
  after it; an earlier run (a manual run) leaves `due` where it is, so it cannot hide a missed run.
- `grace` defaults to 1 hour (as in Healthchecks.io); `max_runtime` defaults to `grace`.
- `rid`: a `success`/`fail` with a different `rid` than the open run's finishes an older,
  overlapping run and leaves the newer run open. Without `rid`, a finish closes the open run.
- Pings older than the last recorded ping (clock skew, retries) are stored but do not change state.
- Detection runs every 15 seconds (`cronbell.detector.interval`). Alerts are therefore up to 15 s
  late; this is documented. On UP → DOWN and DOWN → UP the server publishes an internal
  `CheckEvent`; the notifier (§8) queues its deliveries before the transaction commits and sends
  them after.
- **Status history.** Every status change is stored as a status event, in the transaction that
  makes it: `UP` (the first run, a run after a pause, a recovery), `DOWN` with its reason and the
  run it is about (the missed due time, or the start of the run that ran too long), `PAUSED` and
  `RESUMED`. Its time is when Cronbell saw the change; for a missed run, when the detector noticed
  (up to 15 s after `due + grace`). The `CheckEvent` of a DOWN or a recovery carries its event,
  and every delivery it queues keeps it, so the history shows where each alert went. Upgrading
  starts the history with one event per check that is DOWN or paused, timed as well as the check
  row knows. The events of a check are ordered by time, then by the order they were written (a
  per-check sequence taken under the check's row lock), so two changes in one millisecond keep
  their order in the history, the uptime and pruning. Pings, pauses and resumes of one check lock
  its row, so each applies to the state the previous one left.
- Detection must be safe to run on several server instances: it claims due checks with
  `SELECT … FOR UPDATE SKIP LOCKED`.
- **Downtime.** Every detector run leaves a heartbeat in the database (`detector_heartbeat`). When
  the server starts and the last heartbeat is more than 2 minutes old (a constant, a few detector
  runs), the server was away (stopped, upgraded, the host rebooted) and pings sent meanwhile were
  lost: the jobs probably ran, Cronbell could not hear them. Before its first detection the server
  treats as resumed (§3) every UP check whose open run could have finished in the gap (its
  `max_runtime` ends after the last heartbeat), or whose run fell due by now without a ping and
  with its grace ending after the last heartbeat: the open run is dropped and `due` is computed
  from now, so the job has a full period plus grace to report. Nothing is alerted and no status
  changes; a check that was already DOWN stays DOWN, and one still silent after its new deadline
  goes DOWN as usual. This pass waits for checks locked by a ping instead of skipping them, so
  none keeps its old deadline. One INFO line says `Server was away from <last heartbeat> to <now>; N checks
  given a new deadline`. Shorter restarts, and a first start (no heartbeat yet), change nothing.

## 5. Ping API

The UUID ping URLs of Healthchecks.io, so a script that uses them moves over by changing the host
and the key. Its slug URLs (`/ping/{pingKey}/{slug}`) and `?create=1` are not supported.

```
GET|POST /ping/{pingKey}              success
GET|POST /ping/{pingKey}/start        start of a run
GET|POST /ping/{pingKey}/fail         failure
GET|POST /ping/{pingKey}/{exitCode}   0 = success, anything else = failure
GET|POST /ping/{pingKey}/log          log only, no state change
```

- The request body (up to 10 KB, truncated beyond) is stored with the ping as its log excerpt.
  The body is read as UTF-8 from the raw request, so `curl -d` (form-encoded) bodies are stored
  as sent. NUL characters are dropped.
- Optional query `rid=<uuid>` ties a `start` to its `success`/`fail` when runs overlap (any string
  up to 64 characters is accepted).
- `{exitCode}` is 0-255. Any other last path segment is `404`.
- Responses: `200 OK` with body `OK`; `404` for an unknown key. Never `5xx` for a valid key unless
  the database is down.
- No authentication: the ping key is the secret. Rate limit: 5 pings per second per check (token
  bucket, bursts of 5). Over the limit the ping is not stored and the answer is
  `429 Too Many Requests`. The limit is kept in memory, so it is per server instance.

## 6. Management API (used by the web app and the starter)

JSON over `/api/v1`, documented by OpenAPI at `/api/v1/openapi.json`.

- Web app: session cookie + CSRF token (same origin).
- Starter and scripts: `Authorization: Bearer <project API key>`.

Key endpoint for the starter: `PUT /api/v1/checks/{slug}` creates or updates a check by slug and
returns its ping key, so registration at startup is idempotent.

v0.1 endpoints. Every one accepts either a bearer key or a signed-in session, except where noted.
Everything that is not about accounts acts on one project: the bearer key's project, or the
session's current project:

```
GET    /api/v1/setup                  {"required": true} while no account exists (public)
POST   /api/v1/setup                  creates the first account and signs it in (public, once)
GET    /api/v1/session                the signed-in account, its current project and role, its projects and the ping base URL; 401 if none
POST   /api/v1/session                sign in: {"email", "password"} (public)
DELETE /api/v1/session                sign out (204, also when not signed in)
POST   /api/v1/session/project        switch the current project: {"projectId"} (session only; 404 if not a member)
GET    /api/v1/projects               projects of the signed-in account with its role, oldest membership first (session only)
POST   /api/v1/account/password       {"currentPassword", "newPassword"}: 204, other sessions of the account end (session only)
GET    /api/v1/members                members of the current project: accountId, email, role, joinedAt (session only)
DELETE /api/v1/members/{accountId}    204: an owner removes a member, or a member leaves (session only)
GET    /api/v1/invites                member invites of the current project (session, owner)
POST   /api/v1/invites                {"note"} -> 201 with the invite link, shown this once (session, owner)
DELETE /api/v1/invites/{id}           204: a pending link stops working (session, owner)
GET    /api/v1/admin/invites          project invites of the instance (session, instance admin)
POST   /api/v1/admin/invites          {"note"} -> 201 with the invite link, shown this once (session, instance admin)
DELETE /api/v1/admin/invites/{id}     204 (session, instance admin)
GET    /api/v1/invites/{token}        what the link offers: kind, projectName, invitedBy, expiresAt (public)
POST   /api/v1/invites/{token}/accept {"email", "password", "projectName"} -> the new session (public)
GET    /api/v1/checks                 checks of the project
GET    /api/v1/checks/{slug}          one check
PUT    /api/v1/checks/{slug}          create (201) or update (200); the ping key never changes; optional "channels" (§8, Routing)
DELETE /api/v1/checks/{slug}          204; its pings and pending alerts go with it
GET    /api/v1/checks/{slug}/pings    latest pings, newest first (?limit=, default 50, at most 200)
GET    /api/v1/checks/{slug}/events   status changes, newest first (?limit=, default 50, at most 200)
GET    /api/v1/checks/{slug}/alerts   deliveries of its alerts with eventId, channel and status, newest first (?limit=, default 50, at most 200)
POST   /api/v1/checks/{slug}/pause    PAUSED until the next ping
POST   /api/v1/checks/{slug}/resume   ends a pause (§3)
GET    /api/v1/channels               alert channels of the project (§8)
POST   /api/v1/channels               create (201, Location header)
GET    /api/v1/channels/{id}
PUT    /api/v1/channels/{id}          update name and config; the type never changes
DELETE /api/v1/channels/{id}          204; pending deliveries to it are dropped
POST   /api/v1/channels/{id}/test     sends a test message now (200, or 502 with the error)
GET    /api/v1/keys                   API keys of the project: id, name, prefix, createdAt (session only)
POST   /api/v1/keys                   {"name"} -> 201 with the key, shown this once (session only)
DELETE /api/v1/keys/{id}              204, the key stops working at once (session only)
GET    /api/v1/runs/recent            latest finished runs per check: {slug: [{at, ok, exitCode}]}, oldest first (?limit=, default 20, at most 90)
GET    /api/v1/stats/daily            finished runs and failures per day: [{date, runs, failures}], oldest first (?days=, default 14, at most 90; ?tz=, default UTC)
GET    /api/v1/events                 status changes of every check, newest first, with slug and name (?limit=, default 50, at most 200)
GET    /api/v1/stats/status           how each check spent each day: [{slug, name, days: [{date, state, runs, failures, missed, downMs}]}], oldest day first (?days=, default 30, at most 90; ?tz=, default UTC)
GET    /api/v1/schedules/next         next runs of a cron expression: {cron, timeZone, runs} (?cron=, ?timeZone=, default UTC; ?count=, default 5, at most 10)
```

A check has its definition, `channels` (the ids of the channels it picked, `null` for every
channel), `status`, `downReason`, `lastPingAt`, `lastStartAt`, `lastSuccessAt`, `nextDueAt` and
`nextRunAt`. `nextDueAt` is the run being waited for (§4), so for a late or missed
run it stays in the past. `nextRunAt` is when the schedule next expects a run, seen from now:
`nextDueAt` while that is ahead, else the next cron time after now; `null` for a period schedule
that is overdue (it only moves on with a run), for a paused check and before the first ping. The
dashboard shows a missed run as "Missed 02:00 run" with the next run, not as "due 1 h ago".

A ping in the list has `kind`, `exitCode`, `rid`, `body`, `receivedAt`, `remoteAddr`, `userAgent`,
and for a `success`/`fail` that closes a `start` seen in the same list, `durationMs` (matched by
`rid`, else the latest open `start`).

Runs feed the dashboard's run ticks, and `stats/daily` counts runs and failures per day. A finished run is a `success`
or `fail` ping, so both only cover what pings recorded: a missed run leaves no ping and is not
counted (the check's `status` and `downReason` say when the latest run was missed), and history
older than the retention (§9) is gone. `runs/recent` reads the latest pings of each check through
the `(check_id, received_at)` index, one short query per check. `stats/daily` cuts days at
midnight in `tz` (the browser's zone), so a day can be 23 or 25 hours long around a DST change, and
counts them in one statement. `schedules/next` lets the check form preview a cron
schedule with the detector's own code (DST rules included); it returns `400` for an invalid
expression or zone, or one that never fires (30 February).

The status history (§4) gives the rest. An event has `kind` (the status entered), `reason` and
`runAt` for a DOWN, and `at`. `GET /checks/{slug}` (not the list) adds `stats`: `uptime7d` and
`uptime30d`, the share of the last 7 or 30 days, or since the check was created, that it was not
DOWN, paused time left out (`null` when no time is left); and `averageDurationMs7d`, the average of
the runs that started and finished in the last 7 days, paired as in the ping list (it reads at
most the latest 2,000 pings, about 1,000 runs; `null` when none was timed). Time before the oldest
kept event counts as not DOWN. In `stats/status` a day (cut as in `stats/daily`, today up to now)
is `DOWN` if the check was DOWN at its end, `ISSUES` if it was DOWN for a while or had a failed
run, `OK` if it had runs and neither, and `NONE` otherwise (no run, paused, or before the check
existed); `missed` counts how often it went DOWN for a missed run that day, `downMs` how long it
was DOWN.

API keys can read and change checks and channels but cannot manage keys: a leaked key cannot mint
new ones or revoke the owner's. Key management needs a signed-in session.

### Roles

What each caller may do in a project. Everything else (sign-in, switching project, the password)
only concerns the caller's own account.

| | Owner | Member | API key |
|---|---|---|---|
| Checks, pings, runs, stats: read, create, edit, pause, resume, delete | yes | yes | yes |
| Alert channels: read, create, edit, test, delete | yes | yes | yes |
| API keys: list (name and prefix) | yes | yes | no |
| API keys: create, revoke | yes | no | no |
| Members: list | yes | yes | no |
| Members: remove | anyone but the last owner | only themself (leave) | no |
| Member invites: list, create, delete | yes | no | no |

Project invites (`/api/v1/admin/invites`) are for the instance admin only, whatever their role in
the current project. A refused action is `403`. A session whose current project is no longer one of
its memberships (it was removed) gets `403` on project endpoints until it switches project or reads
`GET /api/v1/session` (below).

### Projects and invites

- **Isolation.** Every check, ping, run, channel, delivery and API key belongs to one project, and
  every query of the management API is filtered by the caller's project. A check, channel, key,
  member or invite of another project is `404` (never `403`, so ids and slugs of other projects
  cannot be probed). Ping URLs stay global: the ping key is the secret. The detector, the notifier
  and the history pruner work across projects, and an alert goes only to the channels of the
  check's own project.
- **Several projects.** The session has a current project: at sign-in the oldest membership.
  `POST /api/v1/session/project` switches it (`404` for a project the account is not a member of,
  whether it exists or not). Membership and role are read again on every request, so a removed
  member loses access at once. When the current project is no longer a membership,
  `GET /api/v1/session` moves the session to the oldest remaining one. An account can end up with
  no project (it was removed from its last one): it can still sign in, sees `project: null`, can
  accept invites and change its password. Bearer keys stay bound to their project.
- **Invites.** A link is `<CRONBELL_BASE_URL>/invite/<token>` (the request's own address when the
  base URL is unset). The token is 32 random bytes in URL-safe Base64; only its SHA-256 is stored,
  so the link is shown once, when it is made. A link is valid for `CRONBELL_INVITE_TTL` (default
  7 days), is used once, and can be deleted before. An invite records its kind, an optional note
  (at most 200 characters), who made it, when it expires, and who accepted it when. Lists show
  every invite, newest first, with `status` `PENDING`, `ACCEPTED` or `EXPIRED`; deleting an
  accepted invite only removes it from the list. Invites are not sent by email: the inviter copies
  the link.
- **Reading a link.** `GET /api/v1/invites/{token}` answers `404` alike for an unknown, used,
  deleted or expired link, so it tells nothing about other invites. `projectName` is the project a
  member invite joins (`null` for a project invite).
- **Accepting.** `POST /api/v1/invites/{token}/accept`:
  - Not signed in: `email` and `password` (same rules as setup) create the account and sign it in
    like sign-in (new session id, CSRF token rotated; the CSRF header is needed as for setup).
    `201`. An email that already has an account is `409` ("sign in first, then open the link
    again").
  - Signed in: the invite goes to the signed-in account; `email` and `password` are ignored. `200`.
    A member invite to a project the account is already in is `409`. The session keeps the
    session version it was signed in with, so a password change made meanwhile still ends it.
  - A project invite creates a new project owned by the account, named `projectName` (1 to 200
    characters, default "<local part of the email>'s project"). A new project starts empty: no
    checks, channels or API keys. Its owner creates an API key on the API keys page; only the
    `Default` project of a new instance gets a key at start-up.
  - A member invite adds the account to the inviter's project as `MEMBER`.
  - Either way the new project becomes the session's current project.
  - Accepting is one transaction that claims the invite with a conditional update, so of two
    concurrent accepts exactly one wins; the other is `404` and creates nothing.
  - Reads and accepts of unknown links count against the client address like failed sign-ins
    (Accounts and sessions, brute force): after 5, `429` with `Retry-After`.
- **Leaving and removing.** `DELETE /api/v1/members/{accountId}` by an owner removes anyone; a
  member may remove only themself. The last owner of a project cannot be removed or leave (`409`),
  so every project keeps an owner. Removing an account from a project does not delete the account.
  Projects are not deleted in v0.1.
- **Check limit.** `CRONBELL_PROJECT_MAX_CHECKS` (unset: no limit) caps the checks of each project,
  for hosted betas. Creating a check beyond it is `403` with a problem detail naming the limit
  (as Healthchecks.io does); updating existing checks always works. Concurrent creates on
  PostgreSQL may pass the limit by one or two.

### Accounts and sessions

- **Accounts.** Email (stored lower-case, unique) and a password hash made by Spring Security's
  `DelegatingPasswordEncoder` (`{bcrypt}…`, so the algorithm can change later). Passwords are 8 to
  128 characters. Accounts are made by the setup and by invites only.
- **Password change.** `POST /api/v1/account/password` checks the current password (failures
  count like failed sign-ins, `400` for a wrong one), stores the new one and ends every other
  session of the account: the account has a session version, raised by the change, and a session
  made with an older version is signed out on its next request. This works with sessions in
  memory and would with sessions in the database. The session that made the change stays signed
  in, with a new session id. The new password is stored only while the session version is still
  the one read with the current password, so of two concurrent changes with the same current
  password the second is `409` (as other conflicts in this module) and changes nothing.
- **First start.** When no account exists and both `CRONBELL_ADMIN_EMAIL` and
  `CRONBELL_ADMIN_PASSWORD` are set, the server creates that account at start-up. Otherwise the
  dashboard shows a one-time setup page that posts to `POST /api/v1/setup`, which is accepted only
  while no account exists. The first account takes a unique "setup slot" in its row, so two
  concurrent setups cannot both succeed: the loser gets `409`, as does any setup once an account
  exists. The setup account owns the oldest project (the `Default` project made at first start)
  and is the instance admin (`accounts.instance_admin`; on upgrade, the existing setup account).
- **Sessions.** The browser signs in with `POST /api/v1/session` and gets a server-side session
  cookie `cronbell_session`: `HttpOnly`, `SameSite=Lax`, `Secure` when the request came over HTTPS
  (directly or through a proxy whose `X-Forwarded-Proto` the server trusts). Sign-in changes the
  session id (fixation). Sessions live in memory: a restart signs everyone out. Idle timeout 7 days.
- **CSRF.** Cookie-authenticated `POST`, `PUT`, `PATCH` and `DELETE` requests (sign-in and setup
  included) need the `X-XSRF-TOKEN` header with the value of the `XSRF-TOKEN` cookie (Spring
  Security's single-page-app pattern; the cookie is readable by the page, `SameSite=Lax`). Any
  `/api` response sets it when missing, and sign-in rotates it. Requests with an
  `Authorization: Bearer` header are stateless and CSRF-free; for them the session cookie is
  ignored, so a bad key is `401` even next to a valid session.
- **Brute force.** Failed sign-ins are counted per client address and email, in memory (per
  instance). After 5 failures in a row each further attempt is refused with `429` and
  `Retry-After` for 30 s, doubling per failure up to 15 minutes, even with the right password. A
  success clears the count; counts are forgotten after an hour without failures. Wrong email and
  wrong password give the same `401`.
- The cloud (§11) will add magic links or OAuth on top of the same accounts, projects and
  invites.

`PUT` body: `name` (default: the slug), exactly one of `cron` or `periodSeconds`, `timeZone`
(IANA id, default `UTC`), `graceSeconds`, `maxRuntimeSeconds`. Slugs are 1-128 characters of
letters, digits, `.`, `_`, `-`. Changing the schedule recomputes `next_due_at` from now. Errors
are RFC 9457 problem details (`400` invalid definition, `401` missing or unknown key).

API keys look like `cb_<43 random characters>`; only their SHA-256 is stored. On first start,
when no project exists, the server creates a `Default` project and one key: `CRONBELL_API_KEY` if set,
otherwise a generated key printed once to the log.

## 7. Spring Boot starter

Artifact `com.steelreed:cronbell-spring-boot-starter`, package `com.steelreed.cronbell.spring`, own
repo `steelreed/cronbell-spring-boot-starter`. Java 17 bytecode, Spring Boot 3.3+ and 4.x (Spring
Framework 6.1+ and 7). Apache-2.0 so it never affects the user's licence. Dependencies: Spring Boot
only; Quartz, ShedLock and Actuator support switch on when they are on the classpath.

**Properties** (prefix `cronbell`):

| Property | Default | Meaning |
|---|---|---|
| `url`, `api-key` | unset | Server base URL and project API key. Off unless both are set (one INFO line says why). |
| `enabled` | `true` | Master switch. |
| `environment` | unset | Slug prefix (`prod.` …) so staging and production do not share checks. |
| `include`, `exclude` | all, none | Glob patterns (`*`, `?`) on slugs without the prefix; exclude wins. |
| `grace` | server default | Grace time sent with every check. |
| `register` | `true` | Upsert the checks at start-up. When `false`, the checks must exist already. |
| `timeout.connect`, `timeout.read` | `5s`, `10s` | HTTP timeouts. |
| `queue-capacity` | `1000` | Pings waiting to be sent; beyond it pings are dropped. |

**Discovery.**

- `@Scheduled`: cron (with `zone`, else the JVM zone), `fixedRate` and `fixedDelay` become
  `periodSeconds` (at least 60, the server minimum); `initialDelay` is ignored. A `fixedDelay` job
  drifts by its own run time, so a long job may come in late; raise `grace` for those. Tasks with
  several schedules, one-time tasks and custom `Trigger`s are not monitored.
- Slug `<bean>.<method>` in kebab case (`reportJob.sendDaily` → `report-job.send-daily`), or the
  value of `@CronbellCheck("slug")`, which also takes `name`, `grace`, `maxRuntime` and
  `enabled = false`.
- Quartz: `CronTrigger`s and endlessly repeating `SimpleTrigger`s; slug `<group>.<name>` of the
  trigger. Runs are reported by a `JobListener`.
- ShedLock: a run skipped because another node holds the lock reports nothing (that node reports).
  `lockAtMostFor` becomes `maxRuntimeSeconds` unless `@CronbellCheck(maxRuntime)` is set.

**Registration.** On start-up, `PUT /api/v1/checks/{slug}` per check with the bearer key; the
returned `pingUrl` is used for pings. Failures never fail start-up: they are retried in the
background (5 s doubling to 5 min). A `400` for one check is logged once and that check is left
out; a `401` stops registration.

**Reporting.** `start?rid=<uuid>` before each run, then `success` (body: duration) or `fail`
(body: exception class, message, top stack frames and causes, at most 4 KB). For `@Scheduled` the
starter listens to the Micrometer observation Spring records around every `@Scheduled` method
(`ScheduledTaskObservationContext`), so it works with any `TaskScheduler`, virtual threads and
`SimpleAsyncTaskScheduler`, without proxying beans. The job's exception propagates as before.

**A monitoring outage must never slow or break a job.** Pings go to a bounded queue served by one
background thread (JDK `HttpClient`). When the queue is full or the server is unreachable, pings are
dropped and one WARN is logged per minute at most. Reporting adds well under a millisecond to a run.
With Actuator, `/actuator/info` shows the registered checks, dropped pings and the last error.

## 8. Alerts

Channels: email, Telegram bot, Slack incoming webhook, Discord webhook, ntfy (ntfy.sh or
self-hosted), generic webhook (JSON POST). An alert goes to **every channel of the check's
project**, unless the check picked some (Routing below).

### Channels

A channel is `{"type", "name", "config"}`; `name` defaults to the type. Config per type:

| Type | Config | Secret fields | Rules |
|---|---|---|---|
| `email` | `address` | — | one address; needs server SMTP settings (`CRONBELL_SMTP_*`), else `400` |
| `telegram` | `botToken`, `chatId` | `botToken` | token `<digits>:<35+ chars>`; chat id is an integer (groups are negative) or `@channelname` |
| `slack` | `webhookUrl` | `webhookUrl` | `https://` only (Slack-compatible receivers such as Mattermost work too) |
| `discord` | `webhookUrl` | `webhookUrl` | `https://discord.com/api/webhooks/…` (also `discordapp.com` and the `ptb.`/`canary.` hosts) |
| `ntfy` | `serverUrl` (optional), `topic`, `token` (optional) | `token` | server `https://` or `http://`, default `https://ntfy.sh`, no user name or password in it (a trailing `/` is dropped); topic 1-64 letters, digits, `_`, `-`; an access token, sent as `Authorization: Bearer`. User and password logins are not supported. |
| `webhook` | `url`, `secret` (optional) | `url`, `secret` | `https://` or `http://`: self-hosted installs post to services on their own network. The cloud will restrict this to public `https://`. |

Secrets are stored as given (the database already holds the ping keys, which are secrets too) and
are never logged. The API returns them masked: tokens and secrets as `ab…yz` (`****` when short),
URLs as `https://host/…`. On `PUT`, a secret field that is omitted, `null`, or equal to its masked
value keeps the stored value, so a client can round-trip what it read; an empty optional secret
removes it. Other fields are replaced (an omitted `name` returns to the default). Unknown config
fields are `400`. Each channel in a response also has `lastAttemptAt`, `lastDeliveryAt` (last success),
`lastError` (of the latest attempt, `null` if it succeeded), `pendingDeliveries`, and `pickedBy`
(the checks that picked it; checks alerting every channel are not counted).

### Routing

A check alerts every channel of its project until it picks some; then it alerts only those. On
`PUT /api/v1/checks/{slug}`, `channels` is:

- left out: a new check alerts every channel; an update keeps the current selection, so the
  starter, which registers its checks at start-up without `channels`, never resets what was
  picked in the dashboard;
- `null` or `[]`: every channel, now and added later;
- a list of channel ids: only those (duplicates are dropped). An id that is not a channel of the
  project, another project's included, is `400` (`channels: no channel with id … in this
  project`), the same answer either way.

Deleting a channel removes it from every selection. A check whose picked channels are all deleted
alerts every channel again rather than none, and its `channels` reads `null`: a check that alerts
no one is worse than one that alerts too many. The selection lives in the checks module
(`check_channels`), which asks the alerts module for the project's channel ids to validate it. It
is written with the check, under the check's row lock that pings, pause and resume also take, so
a save never overwrites a state change made meanwhile.

### Messages

An alert names the check, its status and reason, the relevant time in the check's time zone, the
last success, the last log excerpt, and a link to the check (`<CRONBELL_BASE_URL>/checks/<slug>`)
when `CRONBELL_BASE_URL` is set. English, plain and short. Example:

```
[Cronbell] nightly-backup is DOWN: missed run
Check: Nightly backup (nightly-backup)
Expected at: 2026-01-05 03:00 Asia/Ho_Chi_Minh (grace 30m)
Last success: 2026-01-04 03:01 Asia/Ho_Chi_Minh
Log (2026-01-04 03:01):
<last 1,000 characters of the latest ping body>
https://cronbell.example.com/checks/nightly-backup
```

The second line depends on the reason: `MISSED` gives the expected time and grace, `FAILED` the
failure time (and exit code), `RUNNING_TOO_LONG` the run's start and maximum runtime, and a
recovery `[Cronbell] <slug> is UP again` gives the next expected time. The log excerpt is the body
of the latest ping that has one (DOWN alerts only); pings store up to 10 KB, the alert shows the
last 1,000 characters.

- **Email**: subject is the first line, body the rest, plain text.
- **Telegram**: Bot API `sendMessage`, `parse_mode=HTML`, `&`, `<`, `>` escaped, log in `<pre>`.
- **Slack**: `{"text": …}` with mrkdwn (`&`, `<`, `>` escaped), log in a code block.
- **Discord**: one embed: the first line as title linking to the check, the rest as description
  (markdown characters escaped, the log in a code block), red for DOWN, green for UP, the alert
  time as timestamp; posted as "Cronbell" with mentions off (`allowed_mentions.parse: []`), so a
  log line with `@everyone` pings no one.
- **ntfy**: JSON publish to the server's root URL: `topic`, `title` (the first line), `message`
  (the rest, plain text), `priority` 4 (high) for DOWN and 3 (default) otherwise, `tags`
  (`rotating_light`, `white_check_mark`, `bell` for a test) and `click` (the check's link).
- **Webhook**: `POST`, `Content-Type: application/json`, body:

  ```json
  {"version": 1, "id": "<delivery id>", "event": "check.down",
   "at": "2026-01-05T04:00:00Z", "text": "<the plain-text message>",
   "check": {"slug": "nightly-backup", "name": "Nightly backup", "status": "DOWN",
             "downReason": "MISSED", "timeZone": "Asia/Ho_Chi_Minh",
             "expectedAt": "2026-01-04T20:00:00Z", "lastSuccessAt": "2026-01-03T20:01:00Z",
             "url": "https://cronbell.example.com/checks/nightly-backup"},
   "log": "…"}
  ```

  `event` is `check.down`, `check.up` or `test` (`check` is `null` for a test). `expectedAt` is
  the missed due time for `MISSED`, the next due time for `check.up`, else `null`; absent values
  are `null`. Headers:
  `Cronbell-Delivery: <id>` (the same on retries, so receivers can drop duplicates),
  `Cronbell-Timestamp: <unix seconds>`, and, when the channel has a secret,
  `Cronbell-Signature: sha256=<hex HMAC-SHA256 of "<timestamp>.<body>" with the secret>`.
  Receivers should reject timestamps more than 5 minutes old.

Any 2xx answer is a success. Redirects are not followed.

### Delivery

- **Outbox.** On UP → DOWN and DOWN → UP, one delivery row per channel the check alerts is written
  in the same transaction as the state change, with the rendered alert. Nothing is lost on a
  restart; a crash during a send repeats it (at least once; webhooks carry `Cronbell-Delivery`).
- **Sender.** Every 5 s (`cronbell.notifier.interval`), and right after a commit that queued
  deliveries, the sender claims due rows in a short transaction (PostgreSQL:
  `FOR UPDATE SKIP LOCKED`, so several instances never send the same row; SQLite: one instance)
  and leases them for 2 minutes, then sends outside any transaction, in parallel. A lease that
  expires (the instance died) makes the row due again.
- **Timeouts.** Connect 5 s, read 10 s for HTTP; the same for SMTP. A stuck channel delays only
  its own deliveries.
- **Retry.** Network errors, timeouts, `408`, `429` and `5xx` are retried after 30 s, then
  doubling (1, 2, 4, 8 min…, at most 15 min), honouring `Retry-After` on `429` (Discord's
  `retry_after` too). Other `4xx` (bad token, removed webhook) fail at once. A delivery not sent
  within **1 hour** of the alert is given up. The latest error is kept on the row and shown on
  the channel.
- **Errors** are plain sentences that say what to fix, never a URL, a secret or an exception
  name: `Could not resolve hooks.example.com`, `Connection refused by ntfy.internal:8080`,
  `Timed out after 10 s waiting for hooks.example.com`, `TLS handshake with host:443 failed`,
  `HTTP 401 from Telegram: check the bot token`, `HTTP 404 from Discord: the webhook was deleted
  or its URL is wrong`, `HTTP 403 from ntfy: the topic needs an access token`, `HTTP 500 from
  hooks.example.com: <first 200 characters of the body>`, `SMTP server smtp.example.com rejected
  the user name or password (…)`. A network error names the host and port only, since a webhook
  URL can itself be a secret; a given-up delivery reads `Gave up after 8 attempts in 60 min: …`.
  A body that echoes a secret of the channel (a token, the webhook URL or its path) has it
  replaced by `****` before it is cut to 200 characters, so a secret across the cut leaves no
  part behind.
- **Rate limits.** Alerts are only sent on transitions, so there is no per-channel limit in
  v0.1. `POST /channels/{id}/test` is limited to one call per 10 s per channel (`429`).
- Finished deliveries are kept for history and pruned with the pings (§10).

## 9. Architecture

```
server/   Spring Boot 4.1, Java 25, Flyway. One process:
          ping endpoint · management API · detector · notifier · serves web/dist
web/      Vite + React 19 + TypeScript + TanStack Router/Query + Tailwind + shadcn/ui
starter/  Java 17 library for user applications (later)
```

- **Two databases, one schema.** SQLite (file in `/data`) is the default, so the Docker image
  needs nothing else. PostgreSQL is used by the cloud and by larger self-hosted installs
  (`CRONBELL_DATABASE_URL=postgres://…`). Every test runs against both.
- One set of Flyway migrations (`db/migration`) runs unchanged on both, so it uses portable types
  only: ids are UUIDv7 strings made by the server (`VARCHAR(36)`), times are epoch milliseconds
  (`BIGINT`, UTC). A per-vendor folder is added only if a migration cannot be portable.
- SQLite runs in WAL mode with a single connection (one writer anyway), foreign keys on.
- With SQLite there is exactly one server instance and the detector claims nothing. With
  PostgreSQL several instances may run, and the detector claims due checks with
  `SELECT … FOR UPDATE SKIP LOCKED`.
- A ping is one insert plus one update. Old pings are pruned daily (History, below).
- The server serves the built web app as static files: one container, one port.
- Image: `ghcr.io/steelreed/cronbell`, multi-arch (amd64, arm64). Target: under 300 MB of RAM at
  1,000 checks.
- First start creates an admin account from `CRONBELL_ADMIN_EMAIL` / `CRONBELL_ADMIN_PASSWORD`, or shows a
  one-time setup page (§6, Accounts and sessions).

### History

Pings, status events and finished deliveries are kept for `cronbell.history.retention`
(`CRONBELL_HISTORY_RETENTION`, a duration such as `90d` or `P90D`, default 90 days; §10). Zero or
negative is rejected at startup.

- **What is pruned.** Pings received before `now - retention`, status events before it, and
  deliveries that are `DELIVERED` or `FAILED` and were created before it. `PENDING` deliveries are
  never deleted.
- **What is kept anyway.** Per check: its latest ping, its latest ping with a body (the log
  excerpt of a DOWN alert, §8), and every ping of the open run (received at or after
  `run_started_at`); and its latest status event before the cutoff, so its history and uptime
  still know the status it was in. The state itself (`status`, `last_ping_at`, `last_success_at`, `next_due_at`,
  the open run) lives on the check row, so pruning never changes a check's state.
- **When.** Once a day, and once a minute after the server starts.
- **Batches.** Rows are deleted at most 5,000 per statement (`cronbell.history.batch-size`), each
  statement its own short transaction, so pings and the detector are never blocked for long on
  either database.
- **Several instances.** A run first takes a lease on a row of `job_locks` with a conditional
  update (`locked_until <= now`), renewed after each batch and released at the end. An instance
  that cannot take it skips the run. Two runs at once would only waste work: deletes are
  idempotent.
- One INFO log line per run gives the cutoff and the number of pings, status events and
  deliveries deleted.
- **SQLite space.** The database runs without `auto_vacuum`, so deleted pages are reused for new
  rows and the file stops growing once the retention window is full, but it does not shrink. To
  return the space, stop the server and run `VACUUM` (not done automatically: it rewrites the
  whole file and blocks every writer).

### Dashboard

The web app is served at `/` by the same server, same origin, so it uses the session cookie and
CSRF header and never sees an API key except when one is created. Look: the Steelreed design
system (black and white, dark by default with a light mode, colour only for state: UP green, DOWN
red, late runs and other warnings amber; NEW and PAUSED grey, since a pause is deliberate; system
fonts; status is coloured text with a small dot, no badges). Dense and calm, data first. English in v0.1; strings live in one module so Vietnamese
can follow.

The shell is a collapsible left sidebar (the project switcher at the top; Overview, Checks, Alert
channels, API keys; Settings: Members and Account; Admin, for the instance admin only: Instance
invites; theme and account at the bottom; a sheet on phones) and a header with the page title, or
on deeper pages a breadcrumb ending with the page itself ("Checks / Nightly backup / Edit", the
check by its name), and a command menu (⌘K, Ctrl+K or `/`: jump to a check by name or slug, go to
a page, create a check, switch theme). The active page is marked with a grey fill and the product's
amber on its icon. Theme: dark (default), light or system. In the light theme the page is white and
cards are the `#F7F7F7` surface; in the dark theme the page is ink and cards `#131313`.

Every page has a skeleton while it loads and, when a load fails, the error with a retry. An
unknown address or check shows one "not found" page inside the shell (404, what is missing, a link
back to the overview or the checks); a page that fails to render shows its error with a retry.

| Screen | Path | Content |
|---|---|---|
| Setup | `/setup` | first run only: admin email and password |
| Sign in | `/signin` | email and password; shows the rate-limit wait |
| Overview | `/` | counts (checks, down, late, up; each opens the filtered list); what needs attention (DOWN first, then late, then never pinged) with the reason and since when, from the check's state; the last 30 days in the browser's zone, one square per check and day (`stats/status`: DOWN at day end, failure or downtime, all runs fine, no runs or paused; the day's runs, failures, missed runs and downtime on hover), problems first, 12 checks then "show all"; the recent status changes of the project (`/events`) with the reason or how long the downtime lasted; above them while the project is being set up, its first steps (create a check, receive the first ping, add an alert channel and send a test, and for owners, optional, invite a teammate) with the next one open, until the first three are done (the third once a channel exists, since a test is not stored as a delivery); a project without checks shows only the steps |
| Checks | `/checks` | table: name and slug, schedule and time zone, status with reason (late = overdue within grace), the last 20 runs as ticks, last ping (relative, exact on hover), next run (a missed run by its time, with the next run); search, status filter with counts, sortable columns, problems first by default, 25 per page, all in the URL; row menu (open, pause or resume, copy ping URL, delete with confirmation); a list on phones; refreshed every 15 s; empty state: a button to create the first check, and behind "Using the API or the Spring Boot starter?" a ready `curl` to create and ping a check and the starter's two properties |
| Check | `/checks/{slug}` | status and since when; uptime over 7 and 30 days and the average run over 7 days; the latest runs as ticks (90, 40 on phones; missed runs from the status history as hollow ticks) and run durations on an axis from 0 with the max runtime line; the history: each DOWN or pause, newest first, with the reason, start, end and duration, and where its alerts went, the channels the check alerted at the time (✓ delivered, ✗ failed, with the error on hover); recent runs, a start and its end paired as in the ping list, with result, duration and output (expand to read; cards on phones), or the raw pings with exit code and source; details, with where alerts go; ping URL with copy and snippets (crontab, shell, Kubernetes CronJob, Spring Boot starter); pause/resume, edit, delete (confirmed); a check without pings when its page opens leads with the ping URL, waiting for the first ping (refreshed every 3 s) with a test ping sent from the browser to the ping URL, then when it arrived and the next run; its runs, history and pings show once there are some |
| New / edit check | `/checks/new`, `/checks/{slug}/edit` | slug, name, cron or period, time zone (searchable, common zones first, UTC offsets), the next five runs of a cron schedule (`/schedules/next`), grace, max runtime, alerts to all channels or to ticked ones (at least one) |
| Channels | `/channels` | channels with type, target, last delivery or last error, how many checks picked it; add and edit in a dialog per type (secrets shown masked, kept unless replaced); send test; delete (confirmed, saying how many checks picked it and that a check left with none alerts every channel) |
| API keys | `/keys` | keys by name and prefix; create in a dialog that shows the key once with copy; revoke (confirmed); create and revoke for owners only |
| Members | `/members` | members of the current project with role and "you"; remove (owners) or leave (confirmed); for owners, "Invite member" in a dialog that makes a link and shows it once with copy, and the member invites with status, expiry and delete (confirmed) |
| Instance invites | `/admin/invites` | instance admin only: create a project invite with an optional note (link shown once with copy), the project invites with status, who accepted them and expiry; delete (confirmed); a pointer to Members for adding someone to the current project |
| Account | `/account` | email, change password (current and new), sign out |
| Invite | `/invite/{token}` | public: who invites to what and until when; email, password (and project name for a project invite) to create the account, or "Accept as <email>" when signed in; a clear message for an invalid or expired link |

The project switcher shows the current project and the role; it opens a menu of the account's
projects (a plain label when there is only one). Switching goes to the overview. An account with
no project sees a short page that says so, with its email and sign out.

Any page needs a session: the app asks `GET /api/v1/setup` and `GET /api/v1/session` first and
sends the visitor to `/setup` or `/signin` (then back).

## 10. Plans

Self-hosted is complete and free forever: no feature is removed to push people to the cloud.
The cloud sells not having to run it, plus team features.

| | Self-hosted | Cloud Free | Cloud Team (planned ~$9/mo) |
|---|---|---|---|
| Checks | unlimited | 20 | 200 |
| Channels | all | email, Telegram | all, incl. Slack, Discord, ntfy, webhook, Zalo later |
| History | configurable (default 90 days) | 30 days | 1 year |
| Members | unlimited | 1 | 10 |

`CRONBELL_PROJECT_MAX_CHECKS` (§6, Projects and invites) is how a hosted instance caps checks per
project before billing exists.
| Support | GitHub issues | GitHub issues | email |

Billing is not built in v0.1.

## 11. Open questions

- Sign-in on the cloud: magic-link email (Spring Security one-time tokens) or GitHub OAuth first?
  Self-hosted uses email and password.
- Sessions in the database (Spring Session JDBC) so restarts and several instances keep sign-ins?
  Needed before the cloud; v0.1 keeps them in memory.
- Password reset for self-hosted: today only by deleting the account row and running setup again,
  or by a future `cronbell admin reset-password` command.
- Billing provider usable from Vietnam.
- Reminders while a check stays DOWN (v0.2?).
- Channel secrets at rest: encrypt with a server key (`CRONBELL_SECRET_KEY`) before the cloud
  launch? The cloud also needs webhook targets limited to public addresses (SSRF).
