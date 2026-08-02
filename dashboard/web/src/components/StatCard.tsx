import type { Severity } from "../types";

interface StatCardProps {
  label: string;
  value: string;
  /** Optional subtitle below the value. */
  subtitle?: string;
  /** Tints the card border/background when non-OK. */
  severity?: Severity;
}

/**
 * A compact stat display: eyebrow label, large numeric value, optional subtitle.
 * Uses the same card pattern and severity tinting as HealthTile.
 */
export default function StatCard({ label, value, subtitle, severity }: StatCardProps) {
  const cardClass =
    severity === "CRITICAL"
      ? "card card--critical"
      : severity === "WATCH"
        ? "card card--watch"
        : "card";

  return (
    <div
      className={cardClass}
      style={{
        padding: "14px 15px",
        display: "flex",
        flexDirection: "column",
        gap: "6px",
        minWidth: 0,
      }}
    >
      <span className="eyebrow" style={{ margin: 0 }}>
        {label}
      </span>
      <span
        className="numeric"
        style={{
          fontSize: "26px",
          fontWeight: 600,
          lineHeight: 1,
          color: value === "—" ? "var(--theme-textGhost)" : "var(--theme-text)",
        }}
      >
        {value}
      </span>
      {subtitle && (
        <span style={{ fontSize: "12px", color: "var(--theme-textMuted)", lineHeight: 1.4 }}>
          {subtitle}
        </span>
      )}
    </div>
  );
}
