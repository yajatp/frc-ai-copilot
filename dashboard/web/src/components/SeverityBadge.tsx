import type { Severity } from "../types";
import { severityStyle } from "../severity";

interface SeverityBadgeProps {
  severity: Severity;
}

/**
 * Inline severity pill — matches the badge pattern from HealthTile's header.
 * Uses the .badge CSS class for layout, severity tokens for colors.
 */
export default function SeverityBadge({ severity }: SeverityBadgeProps) {
  const style = severityStyle(severity);

  return (
    <span
      className="badge"
      style={{
        color: style.text,
        background: style.bg,
        border: severity !== "OK" ? `1px solid ${style.border}` : "1px solid var(--theme-border)",
      }}
    >
      <span
        aria-hidden
        className={severity === "CRITICAL" ? "dot-pulse" : undefined}
        style={{
          width: "5px",
          height: "5px",
          borderRadius: "50%",
          background: style.dot,
        }}
      />
      {severity}
    </span>
  );
}
