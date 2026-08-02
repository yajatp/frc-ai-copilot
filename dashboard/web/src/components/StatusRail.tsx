import type { Tick, Verdict } from "../types";

const MATCH_TYPES = ["—", "Practice", "Qualification", "Elimination"];

interface StatusRailProps {
  tick: Tick | null;
  streamOpen: boolean;
}

/**
 * The persistent right-hand context rail: connection state, match context, and — the honest part —
 * which health checks can actually run against what this robot publishes.
 */
export default function StatusRail({ tick, streamOpen }: StatusRailProps) {
  const covered = (tick?.health ?? []).filter((v) => v.signal !== null);
  const missing = (tick?.health ?? []).filter((v) => v.signal === null);
  const total = covered.length + missing.length;
  const pct = total === 0 ? 0 : Math.round((covered.length / total) * 100);

  return (
    <aside className="shell-rail" aria-label="Status">
      <Section title="Connection">
        <Row label="Stream" value={streamOpen ? "Open" : "Reconnecting…"} ok={streamOpen} />
        <Row label="Robot" value={tick?.connected ? "Connected" : "Not connected"} ok={tick?.connected} />
        <Row label="Topics" value={tick ? String(tick.topics) : "—"} />
      </Section>

      <Section title="Match">
        {tick?.fms.attached ? (
          <>
            <Row label="Event" value={tick.fms.eventName || "—"} />
            <Row label="Type" value={MATCH_TYPES[tick.fms.matchType] ?? "—"} />
            <Row label="Number" value={String(tick.fms.matchNumber)} />
            <Row
              label="Station"
              value={
                tick.fms.isRedAlliance === null
                  ? "—"
                  : `${tick.fms.isRedAlliance ? "Red" : "Blue"} ${tick.fms.station}`
              }
            />
          </>
        ) : (
          <p style={{ margin: 0, fontSize: "12.5px", lineHeight: 1.5, color: "var(--theme-textMuted)" }}>
            No Driver Station attached, so there is no match context to show.
          </p>
        )}
      </Section>

      <Section title="Signal coverage">
        <div style={{ display: "flex", alignItems: "baseline", gap: "6px" }}>
          <span className="numeric" style={{ fontSize: "26px", fontWeight: 600 }}>
            {pct}%
          </span>
          <span style={{ fontSize: "12.5px", color: "var(--theme-textMuted)" }}>
            {covered.length} of {total} checks
          </span>
        </div>
        <div
          role="progressbar"
          aria-valuenow={pct}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-label={`Signal coverage: ${pct}%`}
          style={{ display: "flex", height: "6px", borderRadius: "3px", background: "var(--theme-track)", overflow: "hidden" }}
        >
          <div className="coverage-bar" style={{ height: "100%", background: "var(--theme-trackFill)", width: `${pct}%` }} />
        </div>
        {missing.length > 0 && (
          <div style={{ display: "flex", flexDirection: "column", gap: "5px", marginTop: "2px" }}>
            <p style={{ margin: 0, fontSize: "12.5px", lineHeight: 1.5, color: "var(--theme-textMuted)" }}>
              These checks cannot run because the robot code does not publish an input for them:
            </p>
            {missing.map((v: Verdict) => (
              <span key={v.role} className="eyebrow" style={{ color: "var(--theme-textFaint2)" }}>
                {v.label}
              </span>
            ))}
          </div>
        )}
      </Section>
    </aside>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="rail-section">
      <h2 className="eyebrow" style={{ margin: 0 }}>
        {title}
      </h2>
      {children}
    </div>
  );
}

function Row({ label, value, ok }: { label: string; value: string; ok?: boolean }) {
  return (
    <div className="row-hover" style={{
      display: "flex",
      alignItems: "baseline",
      justifyContent: "space-between",
      gap: "10px",
      padding: "2px 0",
      borderRadius: "4px",
    }}>
      <span style={{ fontSize: "12.5px", color: "var(--theme-textMuted)", flexShrink: 0 }}>{label}</span>
      <span
        style={{
          fontSize: "12.5px",
          color: ok === true ? "var(--theme-green)" : ok === false ? "var(--theme-red)" : "var(--theme-text2)",
          textAlign: "right",
          overflow: "hidden",
          textOverflow: "ellipsis",
          whiteSpace: "nowrap",
          fontWeight: ok !== undefined ? 500 : 400,
        }}
        title={value}
      >
        {value}
      </span>
    </div>
  );
}
