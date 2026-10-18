# Cronbell

[![CI](https://github.com/steelreed/cronbell/actions/workflows/ci.yml/badge.svg)](https://github.com/steelreed/cronbell/actions/workflows/ci.yml)
[![License: AGPL-3.0-only](https://img.shields.io/badge/license-AGPL--3.0--only-blue)](LICENSE)

**Know when a cron job stops running.** Cronbell is open-source monitoring for cron jobs,
scheduled tasks and background workers, in any language. Each job reports its runs; Cronbell knows
when the next run is due and alerts you when a run is missed, late, failing or unusually slow.

> **Status: beta.** `0.1.0-beta.2` is out and runs real jobs; `0.1.0` is planned for October 27,
> 2026. Releases and notes: [CHANGELOG.md](CHANGELOG.md).

## Why

A job that never runs throws nothing and logs nothing. The backup that stopped three weeks ago,
the invoice job that died after a deploy, the `@Scheduled` method stuck behind another task: you
usually hear about them from a customer. Cronbell turns "nothing happened" into an alert.

## How it works

1. Create a check with the job's schedule (`0 2 * * *`, `Asia/Ho_Chi_Minh`) and a grace period.
2. Add one line to the job, so it pings Cronbell when it runs:

   ```bash
   0 2 * * * /usr/local/bin/backup.sh && curl -fsS --retry 3 https://cronbell.example.com/ping/<key>
   ```

3. When a run is missed, fails or takes too long, Cronbell alerts you by email, Telegram, Slack or
   webhook.

The ping API is compatible with Healthchecks.io (`/ping/<key>`, `/start`, `/fail`, exit codes), so
existing scripts keep working. [`docs/integrations/`](docs/integrations) has guides for crontab,
systemd timers, Kubernetes, GitHub Actions, Docker and scripts.

**Spring Boot teams** can use
[`cronbell-spring-boot-starter`](https://github.com/steelreed/cronbell-spring-boot-starter) (beta):
it finds every `@Scheduled`, Quartz and ShedLock job and reports each run without code changes.

## Self-host

One container, SQLite by default, PostgreSQL optional:

```bash
docker run -d -p 8080:8080 -v cronbell-data:/data ghcr.io/steelreed/cronbell:0.1.0-beta.2
```

Open `http://localhost:8080` and create the admin account.

For a server with your own domain and HTTPS (Docker Compose with Caddy, backups, upgrades), follow
[`docs/self-hosting.md`](docs/self-hosting.md). See [`server/README.md`](server/README.md) for
every setting.

## Repository layout

| Directory | What |
|---|---|
| [`server/`](server) | Spring Boot 4.1 service on Java 25: ping endpoint, management API, detector |
| [`web/`](web) | Dashboard: Vite, React 19, TypeScript, TanStack, Tailwind, shadcn/ui |
| [`DESIGN.md`](DESIGN.md) | The specification. Behaviour changes start here |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | How the server is built: modules, flows, data, rules |

## Develop

```bash
make test     # server tests (needs Docker for Testcontainers) and web lint, typecheck, tests
make dev      # server on :8080 and the web dev server on :5173
make docker   # build the container image
```

Requirements: JDK 25, Node 24 or newer, Docker. See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Cronbell server and web are licensed under the [GNU AGPL-3.0-only](LICENSE). The
[Spring Boot starter](https://github.com/steelreed/cronbell-spring-boot-starter) is Apache-2.0.

Cronbell is made by [Steelreed](https://steelreed.com), open-source tools for the parts of your
backend nobody is watching.
