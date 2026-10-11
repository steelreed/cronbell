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
| **Account** | A person who signs in. |
| **Project** | A group of checks, owned by one account in v0.1. Has API keys and alert channels. |
| **Check** | One job being watched. Identified publicly by a random UUID (`ping key`) and inside a project by a `slug`. |
| **Schedule** | Either a cron expression with a time zone, or a fixed period. |
| **Grace** | How long after the due time a run may still arrive before it counts as missed. |
| **Ping** | One message from a job: `start`, `success`, `fail`, or `log`. |
| **Run** | A `start` followed by its `success` or `fail`, or a lone `success`/`fail`. |
| **Channel** | Where alerts go: email, Telegram, Slack, webhook. |

## 3. Check states

```
            first ping                 run missed / failed / too long
   NEW ───────────────────▶ UP ─────────────────────────────────────▶ DOWN
                             ▲                                          │
                             └──────────── successful run ──────────────┘
   PAUSED: set by the user; no alerts; the next ping returns it to UP.
```

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
  `CheckEvent` after the transaction commits; the notifier (§8) listens to it.
- Detection must be safe to run on several server instances: it claims due checks with
  `SELECT … FOR UPDATE SKIP LOCKED`.

## 5. Ping API

Compatible with the Healthchecks.io URL shape, so moving over is a host change.

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

v0.1 endpoints so far (bearer key only; session sign-in and OpenAPI come with the web app):

```
GET  /api/v1/checks                 checks of the key's project
PUT  /api/v1/checks/{slug}          create (201) or update (200); the ping key never changes
POST /api/v1/checks/{slug}/pause    PAUSED until the next ping
```

`PUT` body: `name` (default: the slug), exactly one of `cron` or `periodSeconds`, `timeZone`
(IANA id, default `UTC`), `graceSeconds`, `maxRuntimeSeconds`. Slugs are 1-128 characters of
letters, digits, `.`, `_`, `-`. Changing the schedule recomputes `next_due_at` from now. Errors
are RFC 9457 problem details (`400` invalid definition, `401` missing or unknown key).

API keys look like `cb_<43 random characters>`; only their SHA-256 is stored. On first start,
when no project exists, the server creates a `Default` project and one key: `CRONBELL_API_KEY` if set,
otherwise a generated key printed once to the log.

## 7. Spring Boot starter

Artifact `com.steelreed:cronbell-spring-boot-starter`. Baseline **Java 17, Spring Boot 3.x and 4.x**,
so existing applications can use it. License Apache-2.0 so it never affects the user's licence.

- Disabled unless `cronbell.key` is set.
- Discovers jobs from `ScheduledTaskHolder` (`@Scheduled`), Quartz `Scheduler` triggers, and wraps
  ShedLock-locked methods.
- Slug: `<bean>.<method>` for `@Scheduled`, `<group>.<name>` for Quartz. Overridable with
  `@CronCheck("slug")` for users who want stable names.
- Reports `start`, then `success` or `fail` with the exception class and message as the log.
- All reporting is asynchronous with a bounded queue; when the server is unreachable it drops
  pings and logs a warning once per minute. **A monitoring outage must never slow or break a job.**

## 8. Alerts

Channels in v0.1: email, Telegram bot, Slack incoming webhook, generic webhook (JSON POST).

An alert names the check, the reason, the expected time in the check's time zone, the last success,
and the last log excerpt. Delivery is retried with backoff for 1 hour; failures are visible on the
channel in the dashboard.

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
- A ping is one insert plus one update. Old pings are pruned daily (§10).
- The server serves the built web app as static files: one container, one port.
- Image: `ghcr.io/steelreed/cronbell`, multi-arch (amd64, arm64). Target: under 300 MB of RAM at
  1,000 checks.
- First start creates an admin account from `CRONBELL_ADMIN_EMAIL` / `CRONBELL_ADMIN_PASSWORD`, or shows a
  one-time setup page.

## 10. Plans

Self-hosted is complete and free forever: no feature is removed to push people to the cloud.
The cloud sells not having to run it, plus team features.

| | Self-hosted | Cloud Free | Cloud Team (planned ~$9/mo) |
|---|---|---|---|
| Checks | unlimited | 20 | 200 |
| Channels | all | email, Telegram | all, incl. Slack, webhook, Zalo later |
| History | configurable (default 90 days) | 30 days | 1 year |
| Members | unlimited | 1 | 10 |
| Support | GitHub issues | GitHub issues | email |

Billing is not built in v0.1.

## 11. Open questions

- Sign-in on the cloud: magic-link email (Spring Security one-time tokens) or GitHub OAuth first?
  Self-hosted uses email and password.
- Billing provider usable from Vietnam.
