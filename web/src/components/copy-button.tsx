import { CheckIcon, CopyIcon } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { t } from "@/lib/strings";
import { cn } from "@/lib/utils";

export async function copyText(value: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(value);
    return true;
  } catch {
    // Clipboard blocked (plain HTTP); the value stays selectable.
    return false;
  }
}

export function CopyButton({
  value,
  label = t.common.copy,
  variant = "outline",
  size = "sm",
  className,
}: {
  value: string;
  label?: string;
  variant?: "outline" | "default" | "ghost";
  size?: "sm" | "default" | "lg";
  className?: string;
}) {
  const [copied, setCopied] = useState(false);
  return (
    <Button
      type="button"
      variant={variant}
      size={size}
      className={className}
      onClick={async () => {
        if (await copyText(value)) {
          setCopied(true);
          setTimeout(() => setCopied(false), 1500);
        }
      }}
    >
      {copied ? <CheckIcon aria-hidden /> : <CopyIcon aria-hidden />}
      <span role="status">{copied ? t.common.copied : label}</span>
    </Button>
  );
}

/** A block of code with an icon-only copy button in the corner; long lines wrap. */
export function CodeBlock({ code, className }: { code: string; className?: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className={cn("relative min-w-0 rounded-lg border bg-page", className)}>
      <pre className="whitespace-pre-wrap break-all p-3 pr-12 font-mono text-[13px] leading-relaxed">
        <code>{code}</code>
      </pre>
      <Button
        type="button"
        variant="ghost"
        size="icon-sm"
        aria-label={copied ? t.common.copied : t.common.copy}
        title={copied ? t.common.copied : t.common.copy}
        className="absolute top-2 right-2 text-muted-foreground hover:text-foreground"
        onClick={async () => {
          if (await copyText(code)) {
            setCopied(true);
            setTimeout(() => setCopied(false), 1500);
          }
        }}
      >
        {copied ? <CheckIcon aria-hidden /> : <CopyIcon aria-hidden />}
      </Button>
    </div>
  );
}
