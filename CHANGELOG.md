# Changelog

All notable changes to Cronbell are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.0-beta.1] - TBD

### Added

- Ping API compatible with Healthchecks.io: `/ping/<key>`, `/start`, `/fail`, `/log`, exit codes.
- Cron schedules with time zones (5 or 6 fields, DST aware) and fixed-period schedules.
- Detector for missed runs, failed runs and runs that exceed their maximum runtime.
- Management API `/api/v1/checks` with project API keys; errors as RFC 9457 problem details.
- Alerts on DOWN and recovery to email (SMTP), Telegram, Slack and signed webhooks, managed with
  `/api/v1/channels` (secrets masked, test messages). Deliveries go through a database outbox and
  are retried with backoff for an hour; the last error is shown on each channel.
- Configuration: `CRONBELL_BASE_URL` for links in alerts, `CRONBELL_SMTP_*` for email.
- SQLite by default, PostgreSQL optional, one Flyway schema for both.
- OpenAPI description at `/api/v1/openapi.json`.
- Web dashboard: checks list with status filter and 15 s refresh, check detail with ping URL,
  crontab/shell/Kubernetes snippets and recent pings with run durations, create/edit/pause/
  resume/delete checks, alert channels with test messages, API keys. Dark by default, light
  mode, usable on phones.
- Accounts: one admin per instance, from `CRONBELL_ADMIN_EMAIL` / `CRONBELL_ADMIN_PASSWORD` or a
  one-time setup page. Cookie sessions with CSRF protection for the browser, sign-in backoff
  against password guessing; bearer API keys stay stateless.
- API: `GET`/`DELETE /api/v1/checks/{slug}`, `GET /api/v1/checks/{slug}/pings`,
  `POST /api/v1/checks/{slug}/resume`, `/api/v1/keys`, `/api/v1/session`, `/api/v1/setup`.
- The server serves the web dashboard.
- Deploy kit in `deploy/`: Docker Compose with Caddy for automatic HTTPS, an annotated
  `.env.example`, and `backup.sh` for consistent SQLite backups while the server runs. Step-by-step
  guide in `docs/self-hosting.md` (Ubuntu, DNS, first check, backups and restore, upgrades,
  PostgreSQL). CI validates the compose file and shellchecks the script.
- API: checks carry `nextRunAt`, the next run the schedule expects, also after a missed run.
- History retention: pings and finished alert deliveries older than `CRONBELL_HISTORY_RETENTION`
  (default 90 days) are pruned daily in batches; each check keeps its latest ping, its latest log
  and the pings of a run in progress. Several PostgreSQL instances take turns through a lease.
- API: `GET /api/v1/runs/recent` (latest finished runs per check), `GET /api/v1/stats/daily`
  (runs and failures per day in a given time zone) and `GET /api/v1/schedules/next` (preview of a
  cron schedule's next runs).

### Changed

- Server restructured into a modular monolith (checks, pings, alerts, accounts, history, shared)
  verified by Spring Modulith and ArchUnit, see `ARCHITECTURE.md`. No behaviour change.

### Fixed

- Pings without an exit code were reported with exit code 0.
- The bundled `/assets` files were answered with the dashboard's `index.html`.
- Container image built from one Dockerfile.
- The dashboard showed a missed run's due time as "Next due: 1 min. ago"; it now reads
  "Missed 02:00 run" with the next run.
- Recent pings scrolled sideways on phones; they are cards below 768 px, and a ping's output opens
  full width.
