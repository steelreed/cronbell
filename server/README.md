# Cronbell server

Spring Boot 4.1 service on Java 25: ping endpoint, management API, detector and notifier. The behaviour is
specified in [`../DESIGN.md`](../DESIGN.md).

## Run

```bash
./mvnw spring-boot:run                    # SQLite in ./data/cronbell.db
```

or build and run the jar:

```bash
./mvnw -B verify                          # tests need Docker (Testcontainers PostgreSQL)
java -jar target/cronbell-server-0.0.1-SNAPSHOT.jar
```

On first start, with an empty database, the server creates a `Default` project and prints its API
key to the log, once:

```
Created the Default project. Its API key is shown only this once, store it now:

    cb_...
```

Only a hash of the key is stored. To choose the key yourself (Docker, automation), set
`CRONBELL_API_KEY` before the first start.

To run locally against PostgreSQL in a container, start `TestCronbellApplication` from
`src/test/java`.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `CRONBELL_DATA_DIR` | `./data` | Directory of the SQLite database (`cronbell.db`). Created if missing. |
| `CRONBELL_DATABASE_URL` | unset | Use PostgreSQL instead of SQLite: `postgres://user:password@host:5432/db?sslmode=require`, or a `jdbc:postgresql:` URL. |
| `CRONBELL_API_KEY` | unset | API key of the `Default` project created on first start (at least 20 characters). Ignored once a project exists. |
| `CRONBELL_BASE_URL` | unset | Public address of the server, e.g. `https://cronbell.example.com`. Alerts link to `<base>/checks/<slug>`; unset, they carry no link. |
| `CRONBELL_SMTP_HOST` | unset | SMTP server for email channels. Without it (and a sender address) email channels cannot be created. |
| `CRONBELL_SMTP_PORT` | `587` | SMTP port. `465` uses implicit TLS (SMTPS). |
| `CRONBELL_SMTP_USERNAME` | unset | SMTP user; authentication is off when unset. |
| `CRONBELL_SMTP_PASSWORD` | unset | SMTP password. Never logged. |
| `CRONBELL_SMTP_FROM` | the username, if it is an address | Sender address of alert emails, e.g. `Cronbell <cronbell@example.com>`. |
| `CRONBELL_SMTP_STARTTLS` | `true` | Require STARTTLS on ports other than 465. Set `false` only for a local relay. |
| `SERVER_PORT` | `8080` | HTTP port. |

Any Spring Boot property works too, for example `SPRING_DATASOURCE_URL`, which wins over
`CRONBELL_DATABASE_URL`. Other server properties:

| Property | Default | Meaning |
|---|---|---|
| `cronbell.detector.interval` | `PT15S` | How often the detector looks for missed and too-long runs. |
| `cronbell.ping.rate-limit-per-second` | `5` | Pings per second per check, per server instance. |
| `cronbell.scheduling.enabled` | `true` | Turns the detector and the notifier's timer off (tests drive them by hand). |
| `cronbell.notifier.interval` | `PT5S` | How often the notifier looks for alerts to send or retry. |
| `cronbell.notifier.connect-timeout` | `PT5S` | Connect timeout for Slack, Telegram, webhooks and SMTP. |
| `cronbell.notifier.read-timeout` | `PT10S` | Read timeout for the same. |
| `cronbell.notifier.telegram-api-url` | `https://api.telegram.org` | Telegram Bot API base URL (tests point it at a local stub). |

Behind a reverse proxy, set `SERVER_FORWARD_HEADERS_STRATEGY=native` so pings record the client
address instead of the proxy's.

## Databases

SQLite is the default and needs nothing else. It runs with one connection in WAL mode, so run a
single server instance on it. PostgreSQL allows several instances: the detector and the notifier
claim rows with `FOR UPDATE SKIP LOCKED`, so a check is processed and an alert sent by one
instance only. The ping rate limit is in memory, so it applies per instance.

The same Flyway migrations (`src/main/resources/db/migration`) run on both databases. Keep them
portable: `VARCHAR`, `TEXT`, `INTEGER`, `BIGINT` only, ids as UUIDv7 strings made by the server,
times as epoch milliseconds.

## Try it

```bash
KEY=cb_...   # from the log

curl -X PUT localhost:8080/api/v1/checks/nightly-backup \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"cron": "0 3 * * *", "timeZone": "Asia/Ho_Chi_Minh", "graceSeconds": 1800}'
# -> {"pingKey": "…", "pingUrl": "http://localhost:8080/ping/…", "status": "NEW", …}

curl -fsS localhost:8080/ping/<pingKey>/start
./backup.sh > backup.log 2>&1; code=$?
curl -fsS --data-raw "$(tail -c 10000 backup.log)" localhost:8080/ping/<pingKey>/$code

curl -H "Authorization: Bearer $KEY" localhost:8080/api/v1/checks

# Alerts: every channel of the project gets every DOWN and UP alert.
curl -X POST localhost:8080/api/v1/channels \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"type": "telegram", "name": "Ops", "config": {"botToken": "<token>", "chatId": "-100123"}}'
# -> 201 {"id": "…", "type": "telegram", "config": {"botToken": "12…yz", "chatId": "-100123"}, …}

curl -X POST localhost:8080/api/v1/channels/<id>/test -H "Authorization: Bearer $KEY"
```

Channel types and their config are in [`DESIGN.md` §8](../DESIGN.md#8-alerts). Secrets (bot
tokens, webhook URLs and secrets) are returned masked. Webhook receivers can check the
`Cronbell-Signature` header: `sha256=` + hex HMAC-SHA256 of `<Cronbell-Timestamp>.<body>`.

## Layout

| Package | What |
|---|---|
| `schedule` | Cron (5- or 6-field, time zones, DST) and period schedules |
| `check` | Checks, the state machine, the detector, status-change events |
| `ping` | Ping endpoint, rate limiter |
| `api` | Management API (`/api/v1`) |
| `notify` | Alert channels, message rendering, the delivery outbox and its sender |
| `project` | Projects, API keys, first-start bootstrap |
| `security` | Bearer API key authentication |
| `db` | Database detection, SQLite settings, `CRONBELL_DATABASE_URL` |
