# Self-hosting Cronbell

This guide puts Cronbell on one small Linux server with HTTPS, using the files in
[`deploy/`](../deploy):

| File | What |
|---|---|
| `docker-compose.yml` | Cronbell, and Caddy in front of it for HTTPS |
| `Caddyfile` | Caddy: certificate for your domain, security headers, compression |
| `.env.example` | Every setting, with a comment. Copy it to `.env` |
| `backup.sh` | Backs up the SQLite database while Cronbell runs |

Cronbell itself is one container with an SQLite database in a Docker volume. Caddy gets and renews
a Let's Encrypt certificate for your domain on its own.

## What you need

- A server with a public IP address: Ubuntu 22.04 or 24.04, 1 vCPU and 1 GB of RAM are enough.
  Cronbell uses about 250 MB of RAM (its container is capped at 512 MB) and Caddy about 15 MB.
  Disk: pings older than 90 days are pruned daily (`CRONBELL_HISTORY_RETENTION`). A ping takes a few
  hundred bytes plus its output (at most 10 KB), so an hourly check needs about 1.3 MB for 90 days
  and a check every 5 minutes about 8 MB. A few GB free is plenty.
- A domain name you control, for example `cron.example.com`.
- SSH access with `sudo`.

## 1. Point the domain at the server

At your DNS provider, add an `A` record for the domain with the server's IPv4 address (and an
`AAAA` record if the server has IPv6). Check it before going on, since Caddy needs it to get the
certificate:

```bash
dig +short cron.example.com     # prints the server's address
```

## 2. Install Docker

On the server:

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker "$USER"   # then log out and back in, so docker works without sudo
docker compose version            # Docker Compose v2
```

(Or follow [Docker's apt instructions](https://docs.docker.com/engine/install/ubuntu/).)

## 3. Open the firewall

Allow SSH, HTTP (needed for the certificate challenge and the redirect to HTTPS) and HTTPS:

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw allow 443/udp    # HTTP/3, optional
sudo ufw enable
```

Ports published by Docker bypass ufw, so keep the compose file as it is: only Caddy publishes ports
(80 and 443). Cronbell's port 8080 stays inside the Docker network, which matters because
Cronbell trusts the `X-Forwarded-*` headers Caddy sends.

## 4. Get the deploy files

```bash
sudo mkdir -p /opt/cronbell && sudo chown "$USER" /opt/cronbell
cd /opt/cronbell
for f in docker-compose.yml Caddyfile .env.example backup.sh; do
  curl -fsSLO "https://raw.githubusercontent.com/steelreed/cronbell/main/deploy/$f"
done
chmod +x backup.sh
```

(Or clone the repository and use its `deploy/` directory.)

## 5. Fill in `.env`

```bash
cp .env.example .env
chmod 600 .env
nano .env
```

Set at least:

- `CRONBELL_DOMAIN`: your domain, without `https://`.
- `CRONBELL_ADMIN_EMAIL` and `CRONBELL_ADMIN_PASSWORD`: the admin account, created on first start.
  Leave them out to create the account on a setup page instead, but then open the dashboard right
  away: until an account exists, anyone who reaches the page can create it.
- `CRONBELL_VERSION`: the image tag. Pin a release such as `0.1.0-beta.1`, so the server only
  changes version when you change it. Image tags are the version (`0.1.0-beta.1`, `0.1.0`), `0.1`
  for the newest patch of a minor version, `latest` for the newest stable release and `beta` for
  the newest pre-release. Until 0.1.0 is out there are only pre-releases, so `latest` does not
  exist yet.

Email alerts need the `CRONBELL_SMTP_*` settings; Telegram, Slack and webhook channels need nothing
here. Leave settings you don't use commented out: an empty value is not the same as unset.
`CRONBELL_BASE_URL` (links in alerts) and `SERVER_FORWARD_HEADERS_STRATEGY` are set by the
compose file from `CRONBELL_DOMAIN`.

## 6. Start

```bash
docker compose up -d
docker compose ps         # cronbell should become "healthy" within a minute
docker compose logs -f    # Ctrl-C to stop following
```

Caddy asks Let's Encrypt for the certificate on the first request; that takes a few seconds. If it
fails, `docker compose logs caddy` says why (usually DNS not pointing at the server yet, or port
80 blocked).

Open `https://cron.example.com` and sign in with the admin email and password.

## 7. Create an API key and a first check

The dashboard can create checks, but scripts and the Spring Boot starter use an API key. In the
dashboard, open **API keys**, create one, and copy it: it is shown once.

(Unless you set `CRONBELL_API_KEY`, the server also created a key on first start and printed it
to the log once: `docker compose logs cronbell | grep -A 2 'API key'`.)

Create a check and ping it:

```bash
KEY=cb_...                      # the API key
URL=https://cron.example.com

curl -fsS -X PUT "$URL/api/v1/checks/nightly-backup" \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"cron": "0 3 * * *", "timeZone": "Europe/Berlin", "graceSeconds": 1800}'
# -> {"pingKey": "…", "pingUrl": "https://cron.example.com/ping/…", "status": "NEW", …}

curl -fsS https://cron.example.com/ping/<pingKey>      # -> OK
```

The check turns UP in the dashboard. Its page has ready-made crontab, shell and Kubernetes
snippets; [`integrations/`](integrations) covers more ways to run jobs. Add an alert channel under **Channels** and send a test message.

## 8. Back up

`backup.sh` makes a consistent copy of the SQLite database while Cronbell runs (`sqlite3 .backup`
in a throwaway container, so nothing needs to be installed on the server), checks it, gzips it into
`/var/backups/cronbell` and deletes copies older than 14 days.

```bash
sudo mkdir -p /var/backups/cronbell && sudo chown "$USER" /var/backups/cronbell
./backup.sh
# backup: wrote /var/backups/cronbell/cronbell-20261012T030000Z.db.gz (24K)
```

`BACKUP_DIR` and `KEEP_DAYS` change the place and the retention. Copy the backups off the server
as well (the end of `backup.sh` has an `rclone` example): a backup on the same disk does not
survive losing the server.

Run it every night from cron, and let Cronbell watch its own backup. Create a check for it:

```bash
curl -fsS -X PUT "$URL/api/v1/checks/cronbell-backup" \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"name": "Cronbell backup", "cron": "15 3 * * *", "timeZone": "UTC", "graceSeconds": 1800}'
```

then `crontab -e` as the user that runs Docker and add, with the check's ping key:

```cron
15 3 * * * /opt/cronbell/backup.sh > /tmp/cronbell-backup.log 2>&1; curl -fsS -m 10 --retry 3 --data-binary @/tmp/cronbell-backup.log https://cron.example.com/ping/<pingKey>/$?
```

The ping carries the exit code and the script's output, so a failed backup turns the check DOWN
with the error in its pings, and a backup that stops running is reported as missed. (If Cronbell
itself is down, nothing alerts you: an uptime monitor on `https://cron.example.com/actuator/health`
covers that.)

### Restore a backup

Stop Cronbell, replace the database in its volume, and start it again. On a new server, run
steps 1 to 5 first and use `docker compose create` instead of `up -d`, so the volume exists but no
empty database is made.

```bash
cd /opt/cronbell
BACKUP=/var/backups/cronbell/cronbell-20261012T030000Z.db.gz

docker compose stop cronbell        # new server: docker compose create
gunzip -c "$BACKUP" | docker run --rm -i --volumes-from "$(docker compose ps --all --quiet cronbell)" \
  --user 10001:10001 --entrypoint sh keinos/sqlite3:3.53.4 -c '
    cat > /data/restore.db &&
    test "$(sqlite3 /data/restore.db "PRAGMA integrity_check")" = ok &&
    rm -f /data/cronbell.db-wal /data/cronbell.db-shm &&
    mv /data/restore.db /data/cronbell.db'
docker compose up -d
```

Everyone is signed out (sessions live in memory) and the checks, pings, channels and API keys are
as they were at the backup.

## Invite people

There is no sign-up page: everyone else comes in through a single-use invite link that you copy
and send yourself (Cronbell does not email it). Links are valid for 7 days
(`CRONBELL_INVITE_TTL`, for example `CRONBELL_INVITE_TTL=14d` in `.env`) and can be deleted
before they are used.

- **Someone with a project of their own** (a tester, another team): as the admin, open
  **Invites**, choose **Create invite**, add a note such as "Lan, beta tester", and copy the link.
  Whoever opens it picks an email, a password and a project name, and gets an empty project with
  its own checks, alert channels and API keys (they create a key on their API keys page). You do
  not see their project, and they do not see yours. The Invites page shows when a link was
  accepted, by whom.
- **Someone in your project**: open **Members**, choose **Invite member** and copy the link. The
  person joins your current project as a member: they can manage checks and alert channels and
  see the API keys, but only owners create or revoke keys and manage members and invites. Remove a
  member (or leave a project) from the same page; the account itself stays.

Someone who already has an account opens the link while signed in and accepts with one click; the
project then appears in the project switcher at the top of the sidebar. Everyone can change their
password on the **Account** page, which signs out their other sessions.

On a shared instance, `CRONBELL_PROJECT_MAX_CHECKS=20` limits each project to 20 checks.

## Upgrade

Back up, then pull the new image and recreate the container:

```bash
./backup.sh
docker compose pull
docker compose up -d
docker image prune -f      # remove the old image
```

Set `CRONBELL_VERSION` in `.env` to the new version before pulling. Database migrations run on
start. Read the [changelog](../CHANGELOG.md) before a new minor version; releases and their notes
are also on the [GitHub releases page](https://github.com/steelreed/cronbell/releases).

To go back, set `CRONBELL_VERSION` to the previous version and run `docker compose up -d`. A newer
version may have migrated the database, so restore the backup made before the upgrade if the
older version does not start (see [Restore a backup](#restore-a-backup)).

## Logs and status

```bash
docker compose ps                     # state and health
docker compose logs -f cronbell       # server log
docker compose logs -f caddy          # requests and certificates
docker stats --no-stream              # memory and CPU
```

Logs are rotated by Docker (3 files of 10 MB per container).

## PostgreSQL instead of SQLite

SQLite is enough for one server. Use PostgreSQL when you already run one and back it up, or to run
several Cronbell instances. Set the URL in `.env`:

```bash
CRONBELL_DATABASE_URL=postgres://cronbell:<password>@db.example.com:5432/cronbell?sslmode=require
```

Cronbell creates its tables on start. Back up with `pg_dump` instead of `backup.sh`. To run
PostgreSQL in the same compose project, add a service in a `docker-compose.override.yml` next to
the compose file (Compose reads it automatically):

```yaml
services:
  postgres:
    image: postgres:18
    restart: unless-stopped
    environment:
      POSTGRES_USER: cronbell
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?set POSTGRES_PASSWORD in .env}
      POSTGRES_DB: cronbell
    volumes:
      - postgres-data:/var/lib/postgresql
  cronbell:
    depends_on:
      - postgres

volumes:
  postgres-data:
```

with `POSTGRES_PASSWORD` in `.env` and
`CRONBELL_DATABASE_URL=postgres://cronbell:<the same password>@postgres:5432/cronbell`.

## Trying it on a laptop

With `CRONBELL_DOMAIN=localhost`, Caddy uses a certificate from its own local authority instead of
Let's Encrypt; `curl -k https://localhost` or accept the browser warning. Without HTTPS at all,
`docker run -p 8080:8080 -v cronbell-data:/data ghcr.io/steelreed/cronbell:0.1.0-beta.2` is enough; see
[`server/README.md`](../server/README.md) for every setting.
