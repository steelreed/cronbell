import { formatSchedule } from "./format";
import type { Check } from "./types";

const CURL = "curl -fsS -m 10 --retry 5 -o /dev/null";

/** Stands in for a schedule that cron cannot express; the comment above it says why. */
const NO_SCHEDULE = "<schedule>";

/**
 * The cron line of a period: every N minutes for a divisor of an hour, every N hours for a
 * divisor of a day, or daily. Null for any other period: cron restarts its steps at every
 * hour and day, so "every 7 minutes" would run at :56 and then :00.
 */
export function periodCron(seconds: number): string | null {
  if (seconds === 60) return "* * * * *";
  if (seconds === 3_600) return "0 * * * *";
  if (seconds === 86_400) return "0 0 * * *";
  const minutes = seconds / 60;
  if (Number.isInteger(minutes) && minutes < 60 && 60 % minutes === 0) {
    return `*/${minutes} * * * *`;
  }
  const hours = seconds / 3_600;
  if (Number.isInteger(hours) && hours < 24 && 24 % hours === 0) return `0 */${hours} * * *`;
  return null;
}

/**
 * The check's schedule as 5-field cron, for crontab and Kubernetes. A 6-field cron keeps its
 * minutes only when its seconds field is 0; neither has a seconds field.
 */
export function cronSchedule(
  check: Pick<Check, "scheduleKind" | "cron" | "periodSeconds">,
): string | null {
  if (check.scheduleKind === "PERIOD") return periodCron(check.periodSeconds ?? 0);
  const fields = (check.cron ?? "").trim().split(/\s+/);
  if (fields.length !== 6) return fields.join(" ");
  return fields[0] === "0" ? fields.slice(1).join(" ") : null;
}

function noScheduleNote(check: Check): string {
  return `# ${formatSchedule(check)} has no exact cron schedule: replace ${NO_SCHEDULE}`;
}

/** A crontab line: run the job, then report its exit code. */
export function crontabSnippet(check: Check): string {
  const schedule = cronSchedule(check);
  const lines: string[] = [];
  if (schedule === null) lines.push(noScheduleNote(check));
  if (check.scheduleKind === "CRON" && check.timeZone !== "UTC") {
    lines.push(`CRON_TZ=${check.timeZone}`);
  }
  lines.push(`${schedule ?? NO_SCHEDULE} /path/to/job.sh; ${CURL} ${check.pingUrl}/$?`);
  return lines.join("\n");
}

/** Start, exit code and the last 10 KB of output. */
export function shellSnippet(check: Check): string {
  return [
    `${CURL} ${check.pingUrl}/start`,
    "/path/to/job.sh > /tmp/job.log 2>&1; code=$?",
    `tail -c 10000 /tmp/job.log | ${CURL} --data-binary @- ${check.pingUrl}/$code`,
  ].join("\n");
}

export function kubernetesSnippet(check: Check): string {
  const schedule = cronSchedule(check);
  return `${schedule === null ? `${noScheduleNote(check)}\n` : ""}apiVersion: batch/v1
kind: CronJob
metadata:
  name: ${check.slug.toLowerCase().replace(/[^a-z0-9-]/g, "-")}
spec:
  schedule: "${schedule ?? NO_SCHEDULE}"
  timeZone: "${check.timeZone}"
  jobTemplate:
    spec:
      template:
        spec:
          restartPolicy: Never
          containers:
            - name: job
              image: your-image   # needs curl
              command: ["/bin/sh", "-c"]
              args:
                - |
                  ${CURL} ${check.pingUrl}/start
                  /app/job.sh; code=$?
                  ${CURL} ${check.pingUrl}/$code
                  exit $code`;
}

/** The empty state: create a check with the API, then ping it. */
export function firstCheckSnippets(origin: string, pingBaseUrl: string, timeZone: string) {
  return {
    create: `curl -X PUT ${origin}/api/v1/checks/nightly-backup \\
  -H "Authorization: Bearer $CRONBELL_KEY" \\
  -H "Content-Type: application/json" \\
  -d '{"cron": "0 3 * * *", "timeZone": "${timeZone}", "graceSeconds": 1800}'`,
    ping: `# at the end of the job, with the pingKey from the answer
${CURL} ${pingBaseUrl}<pingKey>`,
  };
}

/** The Spring Boot starter: name the check on a @Scheduled method. */
export function springSnippet(check: Check): string {
  const schedule =
    check.scheduleKind === "CRON"
      ? `cron = "${springCron(check.cron ?? "")}", zone = "${check.timeZone}"`
      : `fixedRate = ${check.periodSeconds ?? 3600}, timeUnit = TimeUnit.SECONDS`;
  return `@Scheduled(${schedule})
@CronbellCheck("${check.slug}")
void ${camel(check.slug)}() {
    // the job; a thrown exception reports a failed run
}`;
}

/** Spring cron has a seconds field first; 5-field Unix cron runs at second 0. */
function springCron(cron: string): string {
  const fields = cron.trim().split(/\s+/);
  return fields.length === 5 ? `0 ${cron.trim()}` : cron.trim();
}

function camel(slug: string): string {
  const words = slug.split(/[^A-Za-z0-9]+/).filter(Boolean);
  const name = words
    .map((word, i) =>
      i === 0 ? word.toLowerCase() : word[0].toUpperCase() + word.slice(1).toLowerCase(),
    )
    .join("");
  return /^[A-Za-z]/.test(name) ? name : `run${name}`;
}
