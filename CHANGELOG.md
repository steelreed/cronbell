# Changelog

All notable changes to Cronbell are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Discord and ntfy alert channels. Discord gets an embed (red for DOWN, green for UP, mentions
  off) and its rate limits are retried after `retry_after`; ntfy posts to ntfy.sh or a self-hosted
  server, with an optional access token, high priority for DOWN, tags and a link to the check.
- Per-check alert channels: a check alerts every channel of its project, or only the channels it
  picked (`"channels": [ids]` on `PUT /api/v1/checks/{slug}`, `null` for every channel; left out,
  an update keeps the selection, so the starter does not reset it). A check whose picked channels
  are all deleted alerts every channel again. Channels report `pickedBy`.
- Dashboard: "Alert channels" in the check form, where alerts go on the check page, and how many
  checks picked each channel.

### Fixed

- Channel errors are plain sentences that say what to fix ("Could not resolve
  hooks.example.com", "Connection refused by localhost:8080", "Timed out after 10 s waiting for
  …", "HTTP 401 from Telegram: check the bot token") instead of exception names such as
  `UnresolvedAddressException`.

## [0.1.0-beta.2] - 2026-10-17

### Added

- Invites instead of open registration. The setup account is the instance admin (existing
  databases are migrated) and makes single-use project invite links that give a new account a
  project of its own; project owners make member invite links to theirs. Links last
  `CRONBELL_INVITE_TTL` (default 7 days), are stored only as hashes, can be deleted, and are
  accepted atomically, with a new account or the signed-in one.
- Several projects per account: the session has a current project, switched in the sidebar
  (`POST /api/v1/session/project`); `GET /api/v1/session` lists the projects with the role, and
  `GET /api/v1/projects` too. Every project is isolated from the others.
- Roles: owners manage members, member invites and API keys; members manage checks and alert
  channels and see the keys. `GET`/`DELETE /api/v1/members`, `/api/v1/invites`,
  `/api/v1/admin/invites`, `GET /api/v1/invites/{token}` and `POST /api/v1/invites/{token}/accept`.
  Removed members lose access at once.
- `POST /api/v1/account/password` changes the password and signs out the account's other
  sessions.
- `CRONBELL_PROJECT_MAX_CHECKS` caps the checks of each project (403 beyond it), for hosted
  instances.
- Dashboard: the invite page, a project switcher, and Members, Invites (instance admin) and Account
  pages. API keys are read-only for members.

## [0.1.0-beta.1] - 2026-10-12

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
- Web dashboard redesign: sidebar shell with a command menu (⌘K), an overview (counts, what needs
  attention, runs per day), a checks table with search, status filter, sorting, pagination and run
  history ticks, a check page with run history, durations and Spring Boot snippet, a time zone picker
  and next-run preview in the check form, dialogs for channels and API keys, and a system theme option.
  The check form fills the slug from the name, and legacy zone names such as Asia/Saigon are shown
  under their current name (Asia/Ho_Chi_Minh).
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
