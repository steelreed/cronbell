import { describe, expect, it } from "vitest";
import { canonicalZone, offsetText, parseOffset, zoneMatches } from "./zones";

describe("zones", () => {
  it("shows legacy zone names under their current IANA name", () => {
    expect(canonicalZone("Asia/Saigon")).toBe("Asia/Ho_Chi_Minh");
    expect(canonicalZone("Asia/Calcutta")).toBe("Asia/Kolkata");
    expect(canonicalZone("Europe/Berlin")).toBe("Europe/Berlin");
  });

  it("finds zones by city, alias or UTC offset", () => {
    expect(zoneMatches("Asia/Ho_Chi_Minh", "saigon")).toBe(true);
    expect(zoneMatches("Asia/Ho_Chi_Minh", "vietnam")).toBe(true);
    expect(zoneMatches("Asia/Ho_Chi_Minh", "+7", Date.parse("2026-01-05T00:00:00Z"))).toBe(true);
    expect(zoneMatches("Europe/Berlin", "+7", Date.parse("2026-01-05T00:00:00Z"))).toBe(false);
    expect(parseOffset("UTC-3:30")).toBe(-210);
    expect(offsetText(420)).toBe("+07:00");
  });
});
