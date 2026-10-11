import { t } from "./strings";
import type { ChannelType } from "./types";

export interface ChannelFieldSpec {
  key: string;
  secret: boolean;
  required: boolean;
  inputType: "text" | "email" | "url" | "password";
  placeholder?: string;
  hint?: string;
}

export const CHANNEL_TYPES: ChannelType[] = ["email", "telegram", "slack", "webhook"];

/** Config fields per type (DESIGN.md §8). Secrets come back masked and are kept when left empty. */
export const CHANNEL_FIELDS: Record<ChannelType, ChannelFieldSpec[]> = {
  email: [
    {
      key: "address",
      secret: false,
      required: true,
      inputType: "email",
      placeholder: "ops@example.com",
      hint: t.channels.hints.email,
    },
  ],
  telegram: [
    {
      key: "botToken",
      secret: true,
      required: true,
      inputType: "password",
      placeholder: "123456789:AA…",
    },
    {
      key: "chatId",
      secret: false,
      required: true,
      inputType: "text",
      placeholder: "-1001234567890",
      hint: t.channels.hints.chatId,
    },
  ],
  slack: [
    {
      key: "webhookUrl",
      secret: true,
      required: true,
      inputType: "url",
      placeholder: "https://hooks.slack.com/services/…",
      hint: t.channels.hints.webhookUrl,
    },
  ],
  webhook: [
    {
      key: "url",
      secret: true,
      required: true,
      inputType: "url",
      placeholder: "https://example.com/cronbell",
    },
    {
      key: "secret",
      secret: true,
      required: false,
      inputType: "password",
      hint: t.channels.hints.secret,
    },
  ],
};

/**
 * The config to send. When editing, an empty secret is left out so the server keeps the
 * stored value; on create, required fields must be filled.
 */
export function channelConfig(
  type: ChannelType,
  values: Record<string, string>,
  editing: boolean,
): { config: Record<string, string>; errors: Record<string, string> } {
  const config: Record<string, string> = {};
  const errors: Record<string, string> = {};
  for (const field of CHANNEL_FIELDS[type]) {
    const value = (values[field.key] ?? "").trim();
    if (value) {
      config[field.key] = value;
    } else if (field.required && !(editing && field.secret)) {
      errors[field.key] =
        `${t.channels.fields[field.key as keyof typeof t.channels.fields]} is required.`;
    }
  }
  return { config, errors };
}

/** One line describing where the channel sends to, from its masked config. */
export function channelTarget(type: ChannelType, config: Record<string, string>): string {
  switch (type) {
    case "email":
      return config.address ?? "";
    case "telegram":
      return `chat ${config.chatId ?? ""}`;
    case "slack":
      return config.webhookUrl ?? "";
    case "webhook":
      return config.url ?? "";
  }
}
