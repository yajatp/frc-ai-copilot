import HealthTile from "../components/HealthTile";
import PageHeader from "../components/PageHeader";
import SeverityBadge from "../components/SeverityBadge";
import SignalChart from "../components/SignalChart";
import type { Severity, Verdict } from "../types";
import type { Telemetry } from "../useTelemetry";


const MATCH_TYPES = ["—", "Practice", "Qualification", "Elimination"];

export default function Match({ tick, series }: Telemetry) {
  const health = tick?.health ?? [];
  const fms = tick?.fms;

  // Calculate worst severity across all health verdicts
  const worstSeverity: Severity = health.reduce((acc, v) => {
    if (v.severity === "CRITICAL" || acc === "CRITICAL") return "CRITICAL";
    if (v.severity === "WATCH" || acc === "WATCH") return "WATCH";
    return "OK";
  }, "OK" as Severity);

  // Missing signals count
  const missingVerdicts = health.filter((v) => v.signal === null);
  const coveredVerdicts = health.filter((v) => v.signal !== null);

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "28px", padding: "24px" }}>
      <PageHeader
        title="Match Report (Mode A)"
        subtitle="Post-match safety pass audit — flags, brownout events, CAN health, and loop timing"
      />

      {/* Match Overview Summary Banner */}
      <section className="card" style={{ padding: "18px 20px", display: "flex", flexDirection: "column", gap: "16px" }}>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: "16px", flexWrap: "wrap" }}>
          <div>
            <span className="eyebrow">Overall Status</span>
            <div style={{ marginTop: "6px", display: "flex", alignItems: "center", gap: "10px" }}>
              <SeverityBadge severity={worstSeverity} />
              <span style={{ fontSize: "14px", fontWeight: 600 }}>
                {worstSeverity === "OK" ? "Match Cleared — No Safety Flags" : `${health.filter(v => v.severity !== "OK").length} Diagnostic Flags Detected`}
              </span>
            </div>
          </div>

          <div style={{ display: "flex", gap: "20px", alignItems: "center" }}>
            <div>
              <span className="eyebrow">Match Context</span>
              <div style={{ fontSize: "13px", fontWeight: 500, marginTop: "2px" }}>
                {fms?.attached
                  ? `${fms.eventName} — ${MATCH_TYPES[fms.matchType]} #${fms.matchNumber}`
                  : "Bench / Telemetry Session"}
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* Diagnostic Flags List */}
      <section style={{ display: "flex", flexDirection: "column", gap: "10px" }}>
        <h2 className="eyebrow" style={{ margin: 0 }}>
          Safety & Reliability Flags
        </h2>
        {health.filter((v) => v.severity !== "OK").length === 0 ? (
          <div className="card" style={{ padding: "24px", textAlign: "center", color: "var(--theme-textMuted)", fontSize: "12.5px" }}>
            Clean match — no brownouts, CAN faults, or loop timing overruns detected.
          </div>
        ) : (
          <div style={{ display: "flex", flexDirection: "column", gap: "8px" }}>
            {health
              .filter((v) => v.severity !== "OK")
              .map((v: Verdict) => (
                <div
                  key={v.role}
                  className={`card ${v.severity === "CRITICAL" ? "card--critical" : "card--watch"}`}
                  style={{ padding: "14px 16px", display: "flex", flexDirection: "column", gap: "6px" }}
                >
                  <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
                    <div style={{ display: "flex", alignItems: "center", gap: "8px" }}>
                      <SeverityBadge severity={v.severity} />
                      <span style={{ fontWeight: 600, fontSize: "13px" }}>{v.label}</span>
                    </div>
                    <span className="eyebrow" style={{ color: "var(--theme-textMuted)" }}>
                      {v.signal ?? "No NT Topic"}
                    </span>
                  </div>
                  <p style={{ margin: "4px 0 0", fontSize: "12.5px", lineHeight: 1.5, color: "var(--theme-text2)" }}>
                    {v.assessment}
                  </p>
                </div>
              ))}
          </div>
        )}
      </section>

      {/* Freeze-frame Health Checks */}
      <section style={{ display: "flex", flexDirection: "column", gap: "10px" }}>
        <h2 className="eyebrow" style={{ margin: 0 }}>
          Health Checks Overview ({coveredVerdicts.length}/{health.length} Active)
        </h2>
        <div className="tile-grid">
          {health.map((v) => (
            <HealthTile key={v.role} verdict={v} />
          ))}
        </div>
      </section>

      {/* Raw Telemetry Snapshot Charts */}
      <section style={{ display: "flex", flexDirection: "column", gap: "10px" }}>
        <h2 className="eyebrow" style={{ margin: 0 }}>
          Telemetry Window Snapshot
        </h2>
        <div className="chart-grid">
          <SignalChart
            title="Battery Voltage"
            unit="V"
            points={series.battery_voltage ?? []}
            color="var(--accent)"
            threshold={{ value: 6.8, label: "brownout" }}
            domain={[4, 14]}
          />
          <SignalChart
            title="Total Current"
            unit="A"
            points={series.total_current ?? []}
            color="var(--theme-blue)"
          />
          <SignalChart
            title="Loop Timing"
            unit="ms"
            points={series.loop_ms ?? []}
            color="var(--theme-amberDot)"
            threshold={{ value: 20, label: "20ms budget" }}
          />
          <SignalChart
            title="CAN Error Accumulation"
            unit="errors"
            points={series.can_errors ?? []}
            color="var(--theme-red)"
          />
        </div>
      </section>

      {/* Missing Signals Notice */}
      {missingVerdicts.length > 0 && (
        <section className="card" style={{ padding: "16px 20px", borderStyle: "dashed" }}>
          <h3 className="eyebrow" style={{ margin: "0 0 8px", color: "var(--theme-amberDot)" }}>
            Unverified Health Checks ({missingVerdicts.length})
          </h3>
          <p style={{ margin: 0, fontSize: "12.5px", color: "var(--theme-textMuted)", lineHeight: 1.5 }}>
            The robot code is not publishing inputs for:{" "}
            <strong>{missingVerdicts.map((v) => v.label).join(", ")}</strong>. Publish these NT topics to enable automated Mode A flags.
          </p>
        </section>
      )}
    </div>
  );
}
