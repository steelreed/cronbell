import { CheckIcon, CopyIcon } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { t } from "@/lib/strings";

export function CopyButton({ value, label = t.common.copy }: { value: string; label?: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <Button
      type="button"
      variant="outline"
      size="sm"
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(value);
          setCopied(true);
          setTimeout(() => setCopied(false), 1500);
        } catch {
          // Clipboard blocked (plain HTTP); the value stays selectable.
        }
      }}
    >
      {copied ? <CheckIcon /> : <CopyIcon />}
      {copied ? t.common.copied : label}
    </Button>
  );
}

/** A block of code with a copy button. */
export function CodeBlock({ code, label }: { code: string; label?: string }) {
  return (
    <div className="relative rounded-md border bg-surface">
      <pre className="whitespace-pre-wrap break-all p-3 pr-24 text-[13px] leading-relaxed">
        <code>{code}</code>
      </pre>
      <div className="absolute top-2 right-2">
        <CopyButton value={code} label={label} />
      </div>
    </div>
  );
}
