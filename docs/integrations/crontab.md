# crontab

Create a check with the same schedule as the crontab line (`0 2 * * *`) and the time zone the
cron daemon uses (see [Time zones](#time-zones)). Replace `<pingKey>` below with the check's ping
key. Edit the crontab with `crontab -e`.

## Report success

```cron
0 2 * * * /usr/local/bin/backup.sh && curl -fsS -m 10 --retry 3 -o /dev/null https://cron.example.com/ping/<pingKey>
```

The ping only goes out when the job exits with 0. A failure is reported as a missed run, one grace
period later.

## Report the exit code

```cron
0 2 * * * /usr/local/bin/backup.sh; curl -fsS -m 10 --retry 3 -o /dev/null https://cron.example.com/ping/<pingKey>/$?
```

`$?` is the job's exit code: `/0` is a success, anything else turns the check DOWN at once, with
the alert telling you the run failed.

## Send the output

Cronbell stores up to 10 KB of the request body with the ping and puts it in the DOWN alert. Keep
the end of the output, where the error is:

```cron
0 2 * * * /usr/local/bin/backup.sh > /tmp/backup.log 2>&1; code=$?; tail -c 10000 /tmp/backup.log | curl -fsS -m 10 --retry 3 -o /dev/null --data-binary @- https://cron.example.com/ping/<pingKey>/$code
```

Use `--data-binary` (`-d @-` would strip the newlines). In a crontab line, `%` means a newline:
write `\%` (for example `date +\%F`) or move the commands into a script.

## Measure the run time

A `/start` ping at the beginning lets Cronbell show how long each run takes and alert you when a
run takes longer than the check's max runtime (the grace period unless set). Past one command, a
small wrapper script is easier to read than a crontab line:

```bash
#!/bin/sh
# /usr/local/bin/backup-monitored.sh
url=https://cron.example.com/ping/<pingKey>
log=$(mktemp)

curl -fsS -m 10 --retry 3 -o /dev/null "$url/start"
/usr/local/bin/backup.sh > "$log" 2>&1
code=$?
tail -c 10000 "$log" | curl -fsS -m 10 --retry 3 -o /dev/null --data-binary @- "$url/$code"
rm -f "$log"
exit "$code"
```

```cron
0 2 * * * /usr/local/bin/backup-monitored.sh
```

[scripts.md](scripts.md#bash) has the same as a Bash function to reuse in several jobs.

## Prevent overlapping runs

If a run can last longer than the time between two runs, wrap the job and its pings in `flock`
(util-linux, on every Linux distribution): a second run that finds the lock taken exits at once
without pinging, and the run still going reports when it ends.

```cron
*/10 * * * * flock -n /tmp/sync.lock /usr/local/bin/sync-monitored.sh
```

A run that never ends is still caught: without a finish ping, the check goes DOWN once the max
runtime is over. If you would rather hear about every skipped run, put `flock` inside the script,
around the job only: the lock failure (exit code 1) is then reported as a failed run.

## Time zones

Cron runs jobs in the system time zone (`timedatectl` shows it). Set the same zone on the check, or
the check expects runs at the wrong time.

cronie (Fedora, RHEL, Arch Linux) also reads `CRON_TZ` for the lines below it:

```cron
CRON_TZ=Asia/Ho_Chi_Minh
0 2 * * * /usr/local/bin/backup-monitored.sh
```

Debian and Ubuntu cron ignore `CRON_TZ` and use the system time zone. Check which one you have
before relying on it.

## Test it

Run the line by hand, as the user whose crontab it is, and watch the check's page:

```bash
curl -fsS -m 10 https://cron.example.com/ping/<pingKey>/0   # -> OK
```

Cron runs commands with `/bin/sh` and a short `PATH` (`/usr/bin:/bin`): use full paths in the
crontab, or set `PATH=` at its top.
