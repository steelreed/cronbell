import { describe, expect, it } from "vitest";
import { aCheck } from "@/test/fixtures";
import { crontabSnippet, firstCheckSnippets, kubernetesSnippet, periodCron } from "./snippets";

const every = (periodSeconds: number) =>
  aCheck({ scheduleKind: "PERIOD", cron: null, periodSeconds, timeZone: "UTC" });

describe("periodCron", () => {
  it("maps periods that divide an hour or a day", () => {
    expect(periodCron(60)).toBe("* * * * *");
    expect(periodCron(300)).toBe("*/5 * * * *");
    expect(periodCron(1_800)).toBe("*/30 * * * *");
    expect(periodCron(3_600)).toBe("0 * * * *");
    expect(periodCron(6 * 3_600)).toBe("0 */6 * * *");
    expect(periodCron(86_400)).toBe("0 0 * * *");
  });

  it("has no cron line for periods that would drift at the hour or the day", () => {
    expect(periodCron(7 * 60)).toBeNull();
    expect(periodCron(90 * 60)).toBeNull();
    expect(periodCron(5 * 3_600)).toBeNull();
    expect(periodCron(7 * 86_400)).toBeNull();
    expect(periodCron(90)).toBeNull();
  });
});

describe("crontabSnippet", () => {
  it("runs a cron check on its schedule in its zone", () => {
    expect(crontabSnippet(aCheck())).toBe(
      "CRON_TZ=Asia/Ho_Chi_Minh\n0 3 * * * /path/to/job.sh; curl -fsS -m 10 --retry 5 -o /dev/null https://cron.example.com/ping/0b7c3a52-5b8e-4a3a-9a52-2b8e8f9c6d11/$?",
    );
  });

  it("runs a period check on the cron line of its period", () => {
    expect(crontabSnippet(every(3_600))).toMatch(/^0 \* \* \* \* \/path\/to\/job\.sh;/);
    expect(crontabSnippet(every(900))).toMatch(/^\*\/15 \* \* \* \* /);
  });

  it("says so when the period has no cron line", () => {
    const lines = crontabSnippet(every(7 * 60)).split("\n");
    expect(lines[0]).toBe("# every 7m has no exact cron schedule: replace <schedule>");
    expect(lines[1]).toMatch(/^<schedule> \/path\/to\/job\.sh;/);
  });

  it("drops a zero seconds field, which crontab does not have", () => {
    expect(crontabSnippet(aCheck({ cron: "0 30 2 * * *", timeZone: "UTC" }))).toMatch(
      /^30 2 \* \* \* /,
    );
    expect(crontabSnippet(aCheck({ cron: "*/20 * * * * *", timeZone: "UTC" }))).toMatch(
      /^# \*\/20 \* \* \* \* \* has no exact cron schedule/,
    );
  });
});

describe("kubernetesSnippet", () => {
  it("schedules a period check by its cron line", () => {
    expect(kubernetesSnippet(every(2 * 3_600))).toContain('schedule: "0 */2 * * *"');
  });

  it("leaves a placeholder with a note when the period has no cron line", () => {
    const yaml = kubernetesSnippet(every(45 * 60));
    expect(yaml.startsWith("# every 45m has no exact cron schedule")).toBe(true);
    expect(yaml).toContain('schedule: "<schedule>"');
  });
});

describe("firstCheckSnippets", () => {
  it("points the API, the ping and the starter at this server", () => {
    const snippets = firstCheckSnippets(
      "https://cron.example.com",
      "https://cron.example.com/ping/",
      "UTC",
    );
    expect(snippets.create).toContain(
      "curl -X PUT https://cron.example.com/api/v1/checks/nightly-backup",
    );
    expect(snippets.ping).toContain("https://cron.example.com/ping/<pingKey>");
    expect(snippets.spring).toContain(
      "cronbell.url=https://cron.example.com\ncronbell.api-key=${CRONBELL_API_KEY}",
    );
  });
});
