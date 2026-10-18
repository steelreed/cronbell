# Docker

`docker run` and `docker compose run` exit with the exit code of the container's command, so the
host's crontab can send it to Cronbell as for any other job ([crontab](crontab.md)). With Ofelia,
the job pings from inside the container.

## docker run from the host's crontab

```cron
0 2 * * * docker run --rm --name nightly-backup -v backup-data:/data ghcr.io/acme/backup:1.4 > /tmp/backup.log 2>&1; code=$?; tail -c 10000 /tmp/backup.log | curl -fsS -m 10 --retry 3 --retry-max-time 30 -o /dev/null --data-binary @- https://cron.example.com/ping/<pingKey>/$code
```

- The exit code is the job's, or Docker's own when the container could not run: `125` (Docker
  failed: no such image, name in use), `126`/`127` (command not executable or not found), `137`
  (killed, often out of memory). All of them report a failed run, and the output says why.
- `--name` makes a second run fail with `125` while the first one still runs, so runs cannot
  overlap. Leave it out if they may.
- The crontab user must be able to use Docker (in the `docker` group, or root).

## docker compose run

For a job defined as a service in a compose file:

```yaml
# /opt/app/compose.yaml
services:
  backup:
    image: ghcr.io/acme/backup:1.4
    volumes:
      - backup-data:/data
    profiles: [jobs]   # not started by "docker compose up"

volumes:
  backup-data:
```

```cron
0 2 * * * docker compose -f /opt/app/compose.yaml run --rm -T backup > /tmp/backup.log 2>&1; code=$?; tail -c 10000 /tmp/backup.log | curl -fsS -m 10 --retry 3 --retry-max-time 30 -o /dev/null --data-binary @- https://cron.example.com/ping/<pingKey>/$code
```

`-T` runs without a terminal, which cron does not have. `docker compose run` exits with the
service's exit code.

## Ofelia

[Ofelia](https://github.com/mcuadros/ofelia) runs jobs in containers on a schedule read from
Docker labels. It does not report exit codes itself, so the job command pings Cronbell from inside
the container, which needs `sh` and `curl`:

```yaml
services:
  app:
    image: ghcr.io/acme/app:2.3   # your image, with sh and curl
    environment:
      PING_URL: https://cron.example.com/ping/<pingKey>
    labels:
      ofelia.enabled: "true"
      ofelia.job-exec.cleanup.schedule: "0 0 2 * * *"
      ofelia.job-exec.cleanup.command: >-
        sh -c 'curl -fsS -m 10 --retry 3 --retry-max-time 30 -o /dev/null "$$PING_URL/start";
        /app/cleanup > /tmp/cleanup.log 2>&1; code=$$?;
        tail -c 10000 /tmp/cleanup.log | curl -fsS -m 10 --retry 3 --retry-max-time 30 -o /dev/null --data-binary @- "$$PING_URL/$$code";
        exit $$code'

  ofelia:
    image: mcuadros/ofelia:0.3.21
    command: daemon --docker
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
    depends_on:
      - app
```

- Ofelia's schedules have a **seconds field first**: `0 0 2 * * *` is 02:00:00 every day. On the
  check, use the 5-field form `0 2 * * *` (or the same 6 fields).
- Ofelia reads schedules in its own container's time zone, UTC unless you set `TZ`. Give the check
  the same zone.
- `$$` is how a compose file writes a `$` for the shell.
- By default Ofelia starts a run even while the previous one still runs. Add
  `ofelia.job-exec.cleanup.no-overlap: "true"` to skip it instead; the skipped run sends nothing.
