import HealthTile from "../components/HealthTile";
import PageHeader from "../components/PageHeader";
import SignalChart from "../components/SignalChart";
import StatCard from "../components/StatCard";
import type { Telemetry } from "../useTelemetry";

const MATCH_TYPES = ["—", "Practice", "Qualification", "Elimination"];

export default function Pit({ tick, series }: Telemetry) {
  const health = tick?.health ?? [];
  const signals = tick?.signals ?? {};

  // Extract key KPI numbers
  const voltage = signals.battery_voltage?.value ?? null;
  const current = signals.total_current?.value ?? null;
  const canErrors = signals.can_errors?.value ?? null;
  const loopMs = signals.loop_ms?.value ?? null;

  // Determine card severities
  const voltageSev = voltage !== null && voltage < 6.8 ? "CRITICAL" : voltage !== null && voltage < 10.5 ? "WATCH" : "OK";
  const canSev = canErrors !== null && canErrors > 0 ? "WATCH" : "OK";
  const loopSev = loopMs !== null && loopMs > 20 ? "WATCH" : "OK";

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "28px", padding: "24px" }}>
      <PageHeader
        title="Pit Monitor"
        subtitle="Glanceable health metrics and live telemetry formatted for the pit cart display"
      />

      {/* Top row - KPI Cards */}
      <section style={{ display: "flex", flexDirection: "column", gap: "10px" }}>
        <h2 className="eyebrow" style={{ margin: 0 }}>
          Primary Diagnostics
        </h2>
        <div className="stat-grid">
          <StatCard
            label="Battery Voltage"
            value={voltage !== null ? `${voltage.toFixed(2)} V` : "—"}
            subtitle="Brownout limit: 6.8V"
            severity={voltageSev}
          />
          <StatCard
            label="Total Current"
            value={current !== null ? `${current.toFixed(1)} A` : "—"}
            subtitle="Combined PDP/PDH draw"
          />
          <StatCard
            label="CAN Bus Errors"
            value={canErrors !== null ? String(canErrors) : "—"}
            subtitle="Receive error count"
            severity={canSev}
          />
          <StatCard
            label="Loop Time P95"
            value={loopMs !== null ? `${loopMs.toFixed(1)} ms` : "—"}
            subtitle="Periodic budget: 20ms"
            severity={loopSev}
          />
        </div>
      </section>

      {/* Middle row - Health Verdicts */}
      <section style={{ display: "flex", flexDirection: "column", gap: "10px" }}>
        <h2 className="eyebrow" style={{ margin: 0 }}>
          Continuous Health Checks
        </h2>
        <div className="tile-grid">
          {health.map((v) => (
            <HealthTile key={v.role} verdict={v} />
          ))}
        </div>
      </section>

      {/* Bottom section - Real-time Charts */}
      <section style={{ display: "flex", flexDirection: "column", gap: "10px" }}>
        <h2 className="eyebrow" style={{ margin: 0 }}>
          Telemetry Stream
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
            threshold={{ value: 20, label: "20ms loop" }}
          />
          <SignalChart
            title="CAN Error Rate"
            unit="errors"
            points={series.can_errors ?? []}
            color="var(--theme-red)"
          />
        </div>
      </section>

      {/* FMS Match Info Footer Card */}
      {tick?.fms.attached && (
        <section className="card" style={{ padding: "16px 20px", display: "flex", alignItems: "center", justifyContent: "space-between", gap: "16px", flexWrap: "wrap" }}>
          <div>
            <span className="eyebrow">FMS Attached</span>
            <h3 style={{ margin: "4px 0 0", fontSize: "15px", fontWeight: 600 }}>
              {tick.fms.eventName || "Competition Event"}
            </h3>
          </div>
          <div style={{ display: "flex", gap: "24px" }}>
            <div>
              <span className="eyebrow">Match</span>
              <div className="numeric" style={{ fontSize: "14px", fontWeight: 600 }}>
                {MATCH_TYPES[tick.fms.matchType] ?? "Match"} #{tick.fms.matchNumber}
              </div>
            </div>
            <div>
              <span className="eyebrow">Alliance Station</span>
              <div className="numeric" style={{ fontSize: "14px", fontWeight: 600, color: tick.fms.isRedAlliance ? "var(--theme-red)" : "var(--theme-blue)" }}>
                {tick.fms.isRedAlliance ? "Red" : "Blue"} {tick.fms.station}
              </div>
            </div>
          </div>
        </section>
      )}
    </div>
  );
}
