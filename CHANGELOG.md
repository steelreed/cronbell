# Changelog

All notable changes to Cronbell are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Status history: every change of a check (UP, DOWN with its reason and run, paused, resumed) is
  stored with the status change and pruned with the rest of the history, keeping each check's
  latest. `GET /api/v1/checks/{slug}/events` and `GET /api/v1/events` (the whole project) list
  them; `GET /api/v1/checks/{slug}/alerts` lists the deliveries of each change per channel.
  Existing checks that are DOWN or paused start their history at upgrade.
- `GET /api/v1/checks/{slug}` has `stats`: uptime over 7 and 30 days (paused time left out) and
  the average run duration over 7 days.
- `GET /api/v1/stats/status`: how each check spent each of the last days (DOWN, issues, OK or no
  data), with runs, failures, missed runs and time DOWN.
- Check page: a History panel with each downtime and pause (why, from when to when, how long) and
  where its alerts went, delivered or failed; uptime over 7 and 30 days and the average run; missed
  runs among the run ticks.
- Overview: the last 30 days as one square per check and day, problems first, with the day's runs,
  failures, missed runs and downtime on hover; and the project's recent status changes next to
  what needs attention.
- A check that has never pinged opens on its ping URL, waiting for the first ping (refreshed
  every 3 seconds), with a **Send a test ping** button that pings it from the browser; then it
  says when the ping arrived and when the next run is due. Its empty Runs and Recent pings panels
  stay hidden until there is a ping.
- First steps on the overview while a project is being set up: create a check, receive its first
  ping, add an alert channel and send a test, and optionally invite a teammate. The next step is
  open with its action, and the list goes away once the first three are done.
- Integration guides in `docs/integrations/`: crontab, systemd timers, Kubernetes CronJob, GitHub
  Actions, Docker (with Ofelia) and scripts in Python, Node.js, Bash and PowerShell.
- Discord and ntfy alert channels. Discord gets an embed (red for DOWN, green for UP, mentions
  off) and its rate limits are retried after `retry_after`; ntfy posts to ntfy.sh or a self-hosted
  server, with an optional access token, high priority for DOWN, tags and a link to the check.
- Per-check alert channels: a check alerts every channel of its project, or only the channels it
  picked (`"channels": [ids]` on `PUT /api/v1/checks/{slug}`, `null` for every channel; left out,
  an update keeps the selection, so the starter does not reset it). A check whose picked channels
  are all deleted alerts every channel again. Channels report `pickedBy`.
- Dashboard: "Alert channels" in the check form, where alerts go on the check page, and how many
  checks picked each channel.

### Changed

- The check page lists recent runs, a start and its end in one row with result, duration and
  output; "Raw pings" shows every ping as before.
- Durations read the same everywhere ("850ms", "12.4s", "1m 17s", "5h 46m"), and the duration
  chart's axis starts at 0.
- The overview's "Runs, last 14 days" chart is replaced by the 30-day status strip.
- With no checks, the overview and the checks list lead with one button, "Create your first
  check", which opens the form and then the new check's page. The `curl` recipe for scripts, now
  with the Spring Boot starter's two properties, sits behind "Using the API or the Spring Boot
  starter?".
- The README no longer says every Healthchecks.io script keeps working: it lists what does (UUID
  ping URLs with `/start`, `/fail`, `/log`, exit codes and `rid`) and what does not (slug URLs,
  `?create=1`, bodies beyond 10 KB), and how to watch Cronbell itself.
- One "not found" page for unknown addresses and checks, inside the app with the sidebar and a
  link back (the global one had no sidebar, a missing check was a bare line). A page that fails to
  load or render shows the error with a retry instead of the router's raw message.
- The header's breadcrumb ends with the page itself and names the check: "Checks / Nightly backup",
  "Checks / Nightly backup / Edit", "Checks / New check" (it showed only "Checks").
- The instance admin's Invites page, easy to mix up with the member invite links on Members, is
  now Instance invites at `/admin/invites`, in an Admin group of the sidebar that only the
  instance admin sees, and points to Members for adding someone to the current project.

### Fixed

- No more alert storm after the server was down (outage, upgrade, host reboot). Every overdue check
  used to go DOWN "missed run" or "running too long" on the first detector run, then "UP again"
  with the next ping. The detector now leaves a heartbeat; after a gap of more than 2 minutes, the
  checks that could have reported meanwhile get a new deadline, as on resume, and one log line says
  how long the server was away.
- The crontab and Kubernetes snippets of a period check always said `*/5 * * * *`. They now use
  the cron line of the period (`0 * * * *` for every hour, `*/15 * * * *` for 15 minutes, daily,
  every 2, 3, 4, 6, 8 or 12 hours) and say so when a period has none. A 6-field cron drops its
  zero seconds field, which crontab does not have.
- Ping sources read `0:0:0:0:0:0:0:1`; IPv6 addresses are now compressed (`::1`) and
  IPv4-mapped ones shown as IPv4.
- Paused checks were amber like late ones. A pause is deliberate, so it is grey everywhere; amber
  is left for late runs and checks waiting too long for their first ping.
- The Up tile of the overview said "38% of checks"; it now says "on schedule".
- The light theme had white cards on a grey page. As in the Steelreed design system, the page is
  now white and cards, tables and code use the `#F7F7F7` surface.
- On phones the status filter cut "New" in half and hid "Paused". The row still scrolls, now
  with a fade on the side that has more, and the chosen filter is scrolled into view.
- A session that accepted an invite while the account's password was being changed elsewhere
  stayed signed in; it is now signed out like the other sessions. Of two concurrent password
  changes with the same current password, the second one overwrote the first; it is now refused
  with `409`.
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
