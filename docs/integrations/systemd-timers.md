# systemd timers

A timer starts a service; the service pings Cronbell before and after the job. Create a check with
the timer's schedule (here every day at 02:00 in `Asia/Ho_Chi_Minh`) and put its ping URL in an
environment file, readable only by root:

```bash
sudo mkdir -p /etc/cronbell
echo 'PING_URL=https://cron.example.com/ping/<pingKey>' | sudo tee /etc/cronbell/backup.env >/dev/null
sudo chmod 600 /etc/cronbell/backup.env
```

## Service and timer

`/etc/systemd/system/backup.service`:

```ini
[Unit]
Description=Nightly backup

[Service]
Type=oneshot
EnvironmentFile=/etc/cronbell/backup.env
ExecStartPre=-/usr/bin/curl -fsS -m 10 --retry 3 --retry-max-time 30 -o /dev/null ${PING_URL}/start
ExecStart=/usr/local/bin/backup.sh
ExecStopPost=/bin/sh -c 'if [ "$$SERVICE_RESULT" = success ]; then s=0; elif [ "$$EXIT_CODE" = exited ]; then s=$$EXIT_STATUS; else s=fail; fi; /usr/bin/curl -fsS -m 10 --retry 3 --retry-max-time 30 -o /dev/null "$$PING_URL/$$s" || true'
```

`/etc/systemd/system/backup.timer`:

```ini
[Unit]
Description=Nightly backup at 02:00

[Timer]
OnCalendar=*-*-* 02:00:00 Asia/Ho_Chi_Minh
Persistent=true

[Install]
WantedBy=timers.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now backup.timer
systemctl list-timers backup.timer      # next run
sudo systemctl start backup.service     # run it once now, then look at the check
```

How it works:

- `ExecStartPre=-` sends `/start`. The `-` keeps the job running when the ping fails.
- `ExecStopPost` runs after the job whatever happened, with `$SERVICE_RESULT`, `$EXIT_CODE` and
  `$EXIT_STATUS` set by systemd: a success sends `/0`, an exit code is sent as it is, a kill (signal,
  `TimeoutStartSec`, out of memory) sends `/fail`. `$$` is how a unit file writes a `$` for the
  shell.
- `OnCalendar` takes a time zone (systemd 235 or newer); without one it uses the system zone. Give
  the check the same zone.
- `Persistent=true` runs a missed run when the machine comes back up. That run pings late, so
  Cronbell has already reported it missed, then reports the recovery.
- With `RandomizedDelaySec=`, make the check's grace longer than the delay.

## Send the output

The journal keeps the job's output. Add a second `ExecStopPost` line that sends the end of this
run's log with a `/log` ping, which changes nothing but shows up in the check's history and in the
next DOWN alert:

```ini
ExecStopPost=/bin/sh -c 'journalctl -q -o cat _SYSTEMD_INVOCATION_ID="$$INVOCATION_ID" | tail -c 10000 | /usr/bin/curl -fsS -m 10 --retry 3 --retry-max-time 30 -o /dev/null --data-binary @- "$$PING_URL/log" || true'
```

Put it above the result line, so the log ping is stored first. Reading the journal needs root or
the `systemd-journal` group; services with `User=` need that group.

## Or: OnFailure

To report only failures (and let missed runs cover the rest), skip `ExecStopPost` and start a
helper unit when the service fails. `/etc/systemd/system/cronbell-fail@.service`:

```ini
[Unit]
Description=Report %i failure to Cronbell

[Service]
Type=oneshot
EnvironmentFile=/etc/cronbell/%i.env
ExecStart=/usr/bin/curl -fsS -m 10 --retry 3 --retry-max-time 30 -o /dev/null ${PING_URL}/fail
```

and in `backup.service`:

```ini
[Unit]
Description=Nightly backup
OnFailure=cronbell-fail@%N.service

[Service]
Type=oneshot
EnvironmentFile=/etc/cronbell/backup.env
ExecStart=/usr/local/bin/backup.sh
ExecStartPost=-/usr/bin/curl -fsS -m 10 --retry 3 --retry-max-time 30 -o /dev/null ${PING_URL}
```

`%N` is the unit name without `.service` (`backup`), so the helper reads
`/etc/cronbell/backup.env`, and the same helper serves every job. For a `Type=oneshot` service,
`ExecStartPost` runs only after `ExecStart` succeeded, so it sends the success ping.
