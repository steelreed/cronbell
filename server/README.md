# Steelreed Cron server

Spring Boot 4.1 service on Java 25: ping endpoint, management API and detector. The behaviour is
specified in [`../DESIGN.md`](../DESIGN.md).

## Run

```bash
./mvnw spring-boot:run                    # SQLite in ./data/cron.db
```

or build and run the jar:

```bash
./mvnw -B verify                          # tests need Docker (Testcontainers PostgreSQL)
java -jar target/cron-server-0.0.1-SNAPSHOT.jar
```

On first start, with an empty database, the server creates a `Default` project and prints its API
key to the log, once:

```
Created the Default project. Its API key is shown only this once, store it now:

    crn_...
```

Only a hash of the key is stored. To choose the key yourself (Docker, automation), set
`CRON_API_KEY` before the first start.

To run locally against PostgreSQL in a container, start `TestCronServerApplication` from
`src/test/java`.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `CRON_DATA_DIR` | `./data` | Directory of the SQLite database (`cron.db`). Created if missing. |
| `CRON_DATABASE_URL` | unset | Use PostgreSQL instead of SQLite: `postgres://user:password@host:5432/db?sslmode=require`, or a `jdbc:postgresql:` URL. |
| `CRON_API_KEY` | unset | API key of the `Default` project created on first start (at least 20 characters). Ignored once a project exists. |
| `SERVER_PORT` | `8080` | HTTP port. |

Any Spring Boot property works too, for example `SPRING_DATASOURCE_URL`, which wins over
`CRON_DATABASE_URL`. Other server properties:

| Property | Default | Meaning |
|---|---|---|
| `cron.detector.interval` | `PT15S` | How often the detector looks for missed and too-long runs. |
| `cron.ping.rate-limit-per-second` | `5` | Pings per second per check, per server instance. |
| `cron.scheduling.enabled` | `true` | Turns the detector off (tests drive it by hand). |

Behind a reverse proxy, set `SERVER_FORWARD_HEADERS_STRATEGY=native` so pings record the client
address instead of the proxy's.

## Databases

SQLite is the default and needs nothing else. It runs with one connection in WAL mode, so run a
single server instance on it. PostgreSQL allows several instances: the detector claims rows with
`FOR UPDATE SKIP LOCKED`. The ping rate limit is in memory, so it applies per instance.

The same Flyway migrations (`src/main/resources/db/migration`) run on both databases. Keep them
portable: `VARCHAR`, `TEXT`, `INTEGER`, `BIGINT` only, ids as UUIDv7 strings made by the server,
times as epoch milliseconds.

## Try it

```bash
KEY=crn_...   # from the log

curl -X PUT localhost:8080/api/v1/checks/nightly-backup \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"cron": "0 3 * * *", "timeZone": "Asia/Ho_Chi_Minh", "graceSeconds": 1800}'
# -> {"pingKey": "…", "pingUrl": "http://localhost:8080/ping/…", "status": "NEW", …}

curl -fsS localhost:8080/ping/<pingKey>/start
./backup.sh > backup.log 2>&1; code=$?
curl -fsS --data-raw "$(tail -c 10000 backup.log)" localhost:8080/ping/<pingKey>/$code

curl -H "Authorization: Bearer $KEY" localhost:8080/api/v1/checks
```

## Layout

| Package | What |
|---|---|
| `schedule` | Cron (5- or 6-field, time zones, DST) and period schedules |
| `check` | Checks, the state machine, the detector, status-change events |
| `ping` | Ping endpoint, rate limiter |
| `api` | Management API (`/api/v1`) |
| `project` | Projects, API keys, first-start bootstrap |
| `security` | Bearer API key authentication |
| `db` | Database detection, SQLite settings, `CRON_DATABASE_URL` |
