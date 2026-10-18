import { describe, expect, it } from "vitest";
import {
  type CheckFormValues,
  isCronLike,
  SLUG,
  slugify,
  splitSeconds,
  toDefinition,
  validateCheckForm,
} from "./validation";

const valid: CheckFormValues = {
  slug: "nightly-backup",
  name: "",
  kind: "CRON",
  cron: "0 3 * * *",
  period: "1",
  periodUnit: "h",
  timeZone: "Asia/Ho_Chi_Minh",
  grace: "30",
  graceUnit: "m",
  maxRuntime: "",
  maxRuntimeUnit: "m",
  alertTo: "all",
  channels: [],
};

describe("validateCheckForm", () => {
  it("accepts a valid cron check", () => {
    expect(validateCheckForm(valid)).toEqual({});
  });

  it("reports each invalid field", () => {
    const errors = validateCheckForm({
      ...valid,
      slug: "bad slug!",
      cron: "0 3 * *",
      timeZone: "Mars/Base",
      grace: "-1",
      maxRuntime: "0",
    });
    expect(Object.keys(errors).sort()).toEqual(["cron", "grace", "maxRuntime", "slug", "timeZone"]);
  });

  it("needs a period of at least a minute", () => {
    expect(
      validateCheckForm({ ...valid, kind: "PERIOD", period: "30", periodUnit: "s" }),
    ).toHaveProperty("period");
    expect(validateCheckForm({ ...valid, kind: "PERIOD", period: "1", periodUnit: "m" })).toEqual(
      {},
    );
  });

  it("accepts any zone the browser knows, also aliases", () => {
    expect(validateCheckForm({ ...valid, timeZone: "Asia/Saigon" })).toEqual({});
    expect(validateCheckForm({ ...valid, timeZone: "UTC" })).toEqual({});
  });

  it("knows cron macros and 6-field expressions", () => {
    expect(isCronLike("@daily")).toBe(true);
    expect(isCronLike("0 0 3 * * *")).toBe(true);
    expect(isCronLike("every day")).toBe(false);
  });

  it("needs a ticked channel when alerts go to chosen channels", () => {
    expect(validateCheckForm({ ...valid, alertTo: "picked" })).toHaveProperty("channels");
    expect(validateCheckForm({ ...valid, alertTo: "picked", channels: ["ch1"] })).toEqual({});
  });
});

describe("toDefinition", () => {
  it("converts units to seconds and leaves out empty optionals", () => {
    expect(toDefinition(valid)).toEqual({
      cron: "0 3 * * *",
      timeZone: "Asia/Ho_Chi_Minh",
      graceSeconds: 1800,
      channels: null,
    });
    expect(
      toDefinition({
        ...valid,
        name: " Backup ",
        kind: "PERIOD",
        period: "2",
        periodUnit: "h",
        maxRuntime: "90",
        maxRuntimeUnit: "s",
      }),
    ).toEqual({
      name: "Backup",
      periodSeconds: 7200,
      timeZone: "Asia/Ho_Chi_Minh",
      graceSeconds: 1800,
      maxRuntimeSeconds: 90,
      channels: null,
    });
  });

  it("sends the chosen channels, or null for every channel", () => {
    const picked = { ...valid, alertTo: "picked" as const, channels: ["ch1", "ch2"] };
    expect(toDefinition(picked).channels).toEqual(["ch1", "ch2"]);
    expect(toDefinition({ ...picked, alertTo: "all" }).channels).toBeNull();
  });
});

describe("splitSeconds", () => {
  it("picks the largest exact unit", () => {
    expect(splitSeconds(86400)).toEqual({ value: "1", unit: "d" });
    expect(splitSeconds(1800)).toEqual({ value: "30", unit: "m" });
    expect(splitSeconds(90)).toEqual({ value: "90", unit: "s" });
    expect(splitSeconds(null)).toEqual({ value: "", unit: "m" });
  });
});

describe("slugify", () => {
  it("makes an ASCII kebab-case slug that passes validation", () => {
    expect(slugify("Nightly database backup")).toBe("nightly-database-backup");
    expect(slugify("  Sao lưu đêm (prod) ")).toBe("sao-luu-dem-prod");
    expect(slugify("--Weekly report!!")).toBe("weekly-report");
    expect(slugify("db.vacuum_analyze")).toBe("db.vacuum_analyze");
    expect(slugify("")).toBe("");
    const long = slugify("x".repeat(300));
    expect(long).toHaveLength(128);
    for (const name of ["Nightly backup", "Sao lưu", "a/b c"])
      expect(SLUG.test(slugify(name))).toBe(true);
  });
});
