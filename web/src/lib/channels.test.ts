import { describe, expect, it } from "vitest";
import { channelConfig } from "./channels";

describe("channelConfig", () => {
  it("requires every required field on create", () => {
    const { errors } = channelConfig("telegram", { botToken: "", chatId: "" }, false);
    expect(Object.keys(errors).sort()).toEqual(["botToken", "chatId"]);
  });

  it("leaves out empty secrets on edit so the server keeps them", () => {
    const { config, errors } = channelConfig("webhook", { url: "", secret: "" }, true);
    expect(errors).toEqual({});
    expect(config).toEqual({});
  });

  it("sends a replaced secret", () => {
    expect(
      channelConfig("slack", { webhookUrl: " https://hooks.slack.com/x " }, true).config,
    ).toEqual({
      webhookUrl: "https://hooks.slack.com/x",
    });
  });
});
