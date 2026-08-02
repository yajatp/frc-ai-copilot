import { confidenceLabel, formatValue, severityStyle } from "../severity";
import type { Verdict } from "../types";

/**
 * One verdict from the analysis layer.
 *
 * <p>The tile always shows the confidence backing the number and the NT topic it resolved to.
 * That is the whole epistemic point of the primitives: a reading from a sparse, choppy signal must
 * not look as authoritative as one from a dense one, and a missing signal must read as "cannot
 * see this" rather than "fine".
 */
export default function HealthTile({ verdict }: { verdict: Verdict }) {
  const style = severityStyle(verdict.severity);
  const missing = verdict.signal === null;

  const cardClass =
    verdict.severity === "CRITICAL"
      ? "card card--critical"
      : verdict.severity === "WATCH"
        ? "card card--watch"
        : "card";

  return (
    <article
      className={cardClass}
      style={{
        padding: "14px 15px",
        display: "flex",
        flexDirection: "column",
        gap: "10px",
        minWidth: 0,
      }}
    >
      <header style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: "8px" }}>
        <div style={{ display: "flex", alignItems: "center", gap: "7px", minWidth: 0 }}>
          <span
            aria-hidden
            className={verdict.severity === "CRITICAL" ? "dot-pulse" : undefined}
            style={{
              width: "6px",
              height: "6px",
              borderRadius: "50%",
              background: missing ? "var(--theme-textGhost)" : style.dot,
              flexShrink: 0,
            }}
          />
          <h3
            style={{
              margin: 0,
              fontSize: "12.5px",
              fontWeight: 500,
              color: "var(--theme-text2)",
              whiteSpace: "nowrap",
              overflow: "hidden",
              textOverflow: "ellipsis",
            }}
          >
            {verdict.label}
          </h3>
        </div>
        <span className="eyebrow" style={{ color: missing ? "var(--theme-textGhost)" : style.text, flexShrink: 0 }}>
          {missing ? "No signal" : style.label}
        </span>
      </header>

      <div
        className="numeric"
        style={{
          fontSize: "26px",
          fontWeight: 600,
          color: missing ? "var(--theme-textGhost)" : "var(--theme-text)",
          lineHeight: 1,
        }}
      >
        {formatValue(verdict.value, verdict.unit)}
      </div>

      <p
        style={{
          margin: 0,
          fontSize: "12.5px",
          lineHeight: 1.5,
          color: "var(--theme-textMuted)",
          display: "-webkit-box",
          WebkitLineClamp: 3,
          WebkitBoxOrient: "vertical",
          overflow: "hidden",
        }}
      >
        {verdict.assessment}
      </p>

      <footer
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          gap: "8px",
          paddingTop: "2px",
          borderTop: "1px solid var(--theme-border4)",
          marginTop: "auto",
        }}
      >
        <span
          className="eyebrow"
          style={{ paddingTop: "8px", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}
          title={verdict.signal ?? "Not published by the robot"}
        >
          {verdict.signal ?? "Not published"}
        </span>
        <span className="eyebrow" style={{ paddingTop: "8px", flexShrink: 0 }}>
          {confidenceLabel(verdict.confidence)}
        </span>
      </footer>
    </article>
  );
}
