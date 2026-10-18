# Scripts

Report from inside a script when there is no crontab line to add a `curl` to, or when the script
knows more than its exit code. Each example:

- pings `/start`, then `/0` (or the success URL) or `/fail` with the end of the output or the error,
- ties the two with a random `rid`, so overlapping runs are told apart,
- gives up on a ping after 10 seconds and never lets a ping failure break the job: Cronbell reports
  a run it did not hear about as missed.

They read the ping URL, `https://cron.example.com/ping/<pingKey>`, from `CRONBELL_PING_URL`, and
need nothing beyond the language itself.

## Python

Python 3.8 or newer, standard library only:

```python
#!/usr/bin/env python3
import os
import sys
import time
import traceback
import urllib.request
import uuid

PING_URL = os.environ["CRONBELL_PING_URL"]


def ping(path="", body=None, rid=None):
    url = PING_URL + path + (f"?rid={rid}" if rid else "")
    data = body.encode()[-10_000:] if body else None
    for attempt in range(3):
        try:
            urllib.request.urlopen(urllib.request.Request(url, data=data), timeout=10).close()
            return
        except Exception as e:
            error = e
            if attempt < 2:
                time.sleep(attempt + 1)
    print(f"cronbell: ping failed: {error}", file=sys.stderr)


def job():
    print("exporting...")  # the job; raise an exception to report a failure


if __name__ == "__main__":
    rid = str(uuid.uuid4())
    ping("/start", rid=rid)
    try:
        job()
    except Exception:
        ping("/fail", traceback.format_exc(), rid)
        raise
    ping("", rid=rid)
```

A `POST` with a body is sent when there is something to send (the traceback), a `GET` otherwise.
To run another program instead of a function, use
`subprocess.run([...], capture_output=True, text=True)` and send `/{returncode}` with the end of
its `stdout + stderr`.

## Node.js

Node.js 18 or newer (built-in `fetch`), as an ES module (`job.mjs`):

```js
import { randomUUID } from "node:crypto";

const PING_URL = process.env.CRONBELL_PING_URL;

async function ping(path = "", { body, rid } = {}) {
  const url = PING_URL + path + (rid ? `?rid=${rid}` : "");
  try {
    const res = await fetch(url, {
      method: body ? "POST" : "GET",
      body: body?.slice(-10_000),
      signal: AbortSignal.timeout(10_000),
    });
    if (!res.ok) console.error(`cronbell: ping answered ${res.status}`);
  } catch (err) {
    console.error(`cronbell: ping failed: ${err.message}`);
  }
}

async function job() {
  console.log("exporting..."); // the job; throw to report a failure
}

const rid = randomUUID();
await ping("/start", { rid });
try {
  await job();
} catch (err) {
  await ping("/fail", { body: err.stack ?? String(err), rid });
  throw err;
}
await ping("", { rid });
```

## Bash

A function to source from several jobs. It runs a command, keeps its output on stdout (cron
still mails it), and returns its exit code:

```bash
# shellcheck shell=bash
# /usr/local/lib/cronbell.sh
# Usage: cronbell_run <ping-url> <command> [args...]
cronbell_run() {
  local url=$1 rid log code=0
  shift
  rid=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen)
  log=$(mktemp)
  curl -fsS -m 10 --retry 3 -o /dev/null "$url/start?rid=$rid" || true
  "$@" >"$log" 2>&1 || code=$?
  cat "$log"
  tail -c 10000 "$log" |
    curl -fsS -m 10 --retry 3 -o /dev/null --data-binary @- "$url/$code?rid=$rid" || true
  rm -f "$log"
  return "$code"
}
```

```bash
#!/usr/bin/env bash
set -euo pipefail
. /usr/local/lib/cronbell.sh

cronbell_run "$CRONBELL_PING_URL" pg_dump --format=custom --file=/var/backups/app.dump app
```

It works with `set -e`: the job's failure is caught to be reported, then returned.

## PowerShell

Windows PowerShell 5.1 or PowerShell 7, for a Task Scheduler job
(`powershell.exe -NoProfile -File C:\jobs\export.ps1`):

```powershell
$PingUrl = $env:CRONBELL_PING_URL

function Send-CronbellPing([string]$Path = "", [string]$Body = "", [string]$Rid = "") {
    $uri = $PingUrl + $Path
    if ($Rid) { $uri += "?rid=$Rid" }
    try {
        if ($Body) {
            $Body = $Body.Substring([Math]::Max(0, $Body.Length - 10000))
            Invoke-RestMethod -Uri $uri -Method Post -Body $Body -TimeoutSec 10 | Out-Null
        } else {
            Invoke-RestMethod -Uri $uri -TimeoutSec 10 | Out-Null
        }
    } catch {
        Write-Warning "cronbell: ping failed: $_"
    }
}

$rid = [guid]::NewGuid().ToString()
Send-CronbellPing -Path "/start" -Rid $rid
try {
    $output = & C:\jobs\export.exe 2>&1 | Out-String   # the job
    $code = $LASTEXITCODE
    Write-Output $output
    Send-CronbellPing -Path "/$code" -Body $output -Rid $rid
    exit $code
} catch {
    Send-CronbellPing -Path "/fail" -Body ($_ | Out-String) -Rid $rid
    throw
}
```

For PowerShell commands instead of a program, put them in the `try` block with
`$ErrorActionPreference = "Stop"`, and send an empty path (success) after them.
