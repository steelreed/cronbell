import type { Check } from "./types";

const CURL = "curl -fsS -m 10 --retry 5 -o /dev/null";

/** A crontab line: run the job, then report its exit code. */
export function crontabSnippet(check: Check): string {
  const schedule = check.scheduleKind === "CRON" ? (check.cron ?? "* * * * *") : "*/5 * * * *";
  const lines: string[] = [];
  if (check.scheduleKind === "PERIOD") lines.push("# match the check's period");
  if (check.timeZone !== "UTC") lines.push(`CRON_TZ=${check.timeZone}`);
  lines.push(`${schedule} /path/to/job.sh; ${CURL} ${check.pingUrl}/$?`);
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
  const schedule = check.scheduleKind === "CRON" ? (check.cron ?? "") : "*/5 * * * *";
  return `apiVersion: batch/v1
kind: CronJob
metadata:
  name: ${check.slug.toLowerCase().replace(/[^a-z0-9-]/g, "-")}
spec:
  schedule: "${schedule}"
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
