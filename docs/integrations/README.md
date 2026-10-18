# Integrations

Every job reports to Cronbell with plain HTTP requests to its check's ping URL, so anything that
can run `curl` (or open a URL) works. Pick the page for where your job runs:

| Page | For |
|---|---|
| [crontab](crontab.md) | Jobs in a crontab: exit code, output, run time, overlapping runs |
| [systemd timers](systemd-timers.md) | A `.service` and `.timer` pair |
| [Kubernetes CronJob](kubernetes-cronjob.md) | A `CronJob` with a shell wrapper |
| [GitHub Actions](github-actions.md) | Workflows on `on.schedule` |
| [Docker](docker.md) | `docker run`, `docker compose run` and Ofelia |
| [Scripts](scripts.md) | Python, Node.js, Bash and PowerShell, with no extra libraries |

Spring Boot applications can use the
[Spring Boot starter](https://github.com/steelreed/cronbell-spring-boot-starter) instead: it
reports every `@Scheduled`, Quartz and ShedLock job without code changes.

## The ping API

Create a check in the dashboard (**New check**) or with the API (see
[self-hosting](../self-hosting.md#7-create-an-api-key-and-a-first-check)). Its page shows the ping
URL, `https://cron.example.com/ping/<pingKey>`. Then:

| Request | Means |
|---|---|
| `/ping/<pingKey>` | The run succeeded |
| `/ping/<pingKey>/start` | A run started. Optional: lets Cronbell measure the run time and report runs that take too long |
| `/ping/<pingKey>/fail` | The run failed |
| `/ping/<pingKey>/<exitCode>` | The run finished with this exit code (0-255): 0 is success, anything else a failure |
| `/ping/<pingKey>/log` | A message for the history, no change of state |

- `GET` and `POST` both work. A `POST` body (up to 10 KB, cut beyond) is stored with the ping and
  shown in DOWN alerts, so send the end of the job's output: that is where the error is.
- `?rid=<id>` (any string up to 64 characters) ties a `start` to its finish when runs can
  overlap. Without it, a finish closes the open run.
- The answer is `200 OK`, `404` for an unknown key, `429` above 5 pings per second per check.
- The ping key is the only secret: treat the URL like a password (keep it out of public
  repositories, use your CI's or cluster's secrets).

The examples use `curl -fsS -m 10 --retry 3 -o /dev/null`: fail on HTTP errors but print nothing
else, give up after 10 seconds, retry 3 times on timeouts and temporary errors (`429`, `5xx`), and drop the `OK` answer. A ping
that fails must never break the job, so the examples ignore its result, and Cronbell reports a run
it did not hear about as missed.
