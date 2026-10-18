# GitHub Actions

A scheduled workflow pings `/start` as its first step and the result as its last, with
`if: always()` so the last step also runs when the job fails.

Create a check with the workflow's cron and the time zone **UTC** (GitHub reads `on.schedule` in
UTC), and a grace of at least an hour (see [Notes](#notes)). Store the ping URL,
`https://cron.example.com/ping/<pingKey>`, as a repository secret named `CRONBELL_PING_URL`
(**Settings → Secrets and variables → Actions**, or
`gh secret set CRONBELL_PING_URL --body 'https://cron.example.com/ping/<pingKey>'`).

## Workflow

`.github/workflows/nightly-report.yml`:

```yaml
name: Nightly report

on:
  schedule:
    - cron: "17 2 * * *"
  workflow_dispatch:

jobs:
  report:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    env:
      PING_URL: ${{ secrets.CRONBELL_PING_URL }}
      RID: ${{ github.run_id }}-${{ github.run_attempt }}
    steps:
      - name: Cronbell start
        continue-on-error: true
        run: curl -fsS -m 10 --retry 3 --retry-max-time 30 -o /dev/null "$PING_URL/start?rid=$RID"

      - uses: actions/checkout@v5

      - name: Report
        run: ./scripts/nightly-report.sh

      - name: Cronbell result
        if: always()
        continue-on-error: true
        env:
          JOB_STATUS: ${{ job.status }}
        run: |
          if [ "$JOB_STATUS" = success ]; then result=0; else result=fail; fi
          curl -fsS -m 10 --retry 3 --retry-max-time 30 -o /dev/null "$PING_URL/$result?rid=$RID"
```

- `job.status` is `success`, `failure` or `cancelled` at that point; anything but `success` sends
  `/fail`.
- `continue-on-error: true` keeps a Cronbell outage from failing the workflow.
- The `${{ }}` values go through `env:`, not straight into `run:`, so they are never parsed as
  shell code.
- `workflow_dispatch` lets you run it from the **Actions** tab to test it. Manual runs ping too;
  Cronbell does not count a run that completes long before the scheduled time as the scheduled one.

## Notes

- **Scheduled runs start late.** GitHub queues them and starts them minutes after the time, more
  at busy times (on the hour especially), and may drop a run under heavy load. Pick an odd minute
  (`17 2 * * *`, not `0 2 * * *`) and give the check a grace of an hour or more. A dropped run is
  reported as missed, which is usually what you want to know.
- **Schedules stop on inactive repositories.** In a public repository, GitHub disables scheduled
  workflows after 60 days without activity. Cronbell then reports the next run as missed.
- **Schedules run on the default branch** only, from the workflow file there.
- **Send the output:** to put the error in the alert, have the job write a log file and send its
  end with the result: `tail -c 10000 report.log | curl … --data-binary @- "$PING_URL/$result?rid=$RID"`.
