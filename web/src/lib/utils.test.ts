import { describe, expect, it } from "vitest";
import { cn } from "./utils";

describe("cn", () => {
  it("joins class names and drops falsy ones", () => {
    expect(cn("px-2", false && "hidden", undefined, "text-sm")).toBe("px-2 text-sm");
  });

  it("lets a later Tailwind class override an earlier one", () => {
    expect(cn("px-2 py-1", "px-4")).toBe("py-1 px-4");
  });
});
