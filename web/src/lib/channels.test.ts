import { describe, expect, it } from "vitest";
import { channelConfig, channelTarget } from "./channels";

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

  it("names the Discord webhook field in its error", () => {
    expect(channelConfig("discord", { webhookUrl: "" }, false).errors).toEqual({
      webhookUrl: "Webhook URL is required.",
    });
  });

  it("needs only the topic for ntfy", () => {
    expect(channelConfig("ntfy", { serverUrl: "", topic: "ops", token: "" }, false)).toEqual({
      config: { topic: "ops" },
      errors: {},
    });
  });
});

describe("channelTarget", () => {
  it("shows an ntfy channel as server and topic", () => {
    expect(channelTarget("ntfy", { serverUrl: "https://ntfy.sh", topic: "ops" })).toBe(
      "ntfy.sh/ops",
    );
    expect(channelTarget("discord", { webhookUrl: "https://discord.com/…" })).toBe(
      "https://discord.com/…",
    );
  });
});
