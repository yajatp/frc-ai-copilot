import HealthTile from "../components/HealthTile";
import SignalChart from "../components/SignalChart";
import type { Point } from "../types";
import type { Telemetry } from "../useTelemetry";

/** The brownout floor the power primitive checks against (PowerAnalysis.DEFAULT_BROWNOUT_VOLTS). */
const BROWNOUT_VOLTS = 6.8;

/** The 20 ms budget a default periodic loop has (LoopTiming.DEFAULT_OVERRUN_MS). */
const LOOP_BUDGET_MS = 20;

export default function Live({ tick, series }: Telemetry) {
  const health = tick?.health ?? [];
  const window = windowSeconds(series.battery_voltage ?? []);

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "22px", padding: "22px 24px" }}>
      <header style={{ display: "flex", flexDirection: "column", gap: "5px" }}>
        <h1 style={{ margin: 0, fontSize: "17px", fontWeight: 600, letterSpacing: "-0.02em" }}>Live</h1>
        <p style={{ margin: 0, fontSize: "12.5px", color: "var(--theme-textMuted)", lineHeight: 1.5 }}>
          Every verdict below is the same analysis Mode A runs after a match, applied continuously to
          the rolling telemetry window
          {window === null ? "" : ` — currently ${window} s`}.
        </p>
      </header>

      <section style={{ display: "flex", flexDirection: "column", gap: "10px" }}>
        <h2 className="eyebrow" style={{ margin: 0 }}>
          Health
        </h2>
        {health.length === 0 ? (
          <EmptyState />
        ) : (
          <div
            style={{
              display: "grid",
              gridTemplateColumns: "repeat(auto-fit, minmax(232px, 1fr))",
              gap: "10px",
            }}
          >
            {health.map((verdict) => (
              <HealthTile key={verdict.role} verdict={verdict} />
            ))}
          </div>
        )}
      </section>

      <section style={{ display: "flex", flexDirection: "column", gap: "10px" }}>
        <h2 className="eyebrow" style={{ margin: 0 }}>
          Telemetry
        </h2>
        <div
          style={{
            display: "grid",
            gridTemplateColumns: "repeat(auto-fit, minmax(320px, 1fr))",
            gap: "10px",
          }}
        >
          <SignalChart
            title="Battery voltage"
            unit="V"
            points={series.battery_voltage ?? []}
            color="var(--accent)"
            threshold={{ value: BROWNOUT_VOLTS, label: "brownout" }}
            domain={[4, 14]}
          />
          <SignalChart
            title="Total current"
            unit="A"
            points={series.total_current ?? []}
            color="var(--theme-blue)"
          />
          <SignalChart
            title="Loop time"
            unit="ms"
            points={series.loop_ms ?? []}
            color="var(--theme-amberDot)"
            threshold={{ value: LOOP_BUDGET_MS, label: "budget" }}
          />
          <SignalChart
            title="CAN errors"
            unit="errors"
            points={series.can_errors ?? []}
            color="var(--theme-red)"
          />
        </div>
      </section>
    </div>
  );
}

/**
 * How much history the analysis is actually seeing, reported rather than assumed — the window
 * depends on the robot's publish rate, so hardcoding a duration here would eventually be a lie.
 */
function windowSeconds(points: Point[]): number | null {
  if (points.length < 2) return null;
  return Math.round((points[points.length - 1].t - points[0].t) / 1000);
}

function EmptyState() {
  return (
    <div
      style={{
        border: "1px dashed var(--theme-border2)",
        borderRadius: "8px",
        padding: "28px 20px",
        textAlign: "center",
        color: "var(--theme-textMuted)",
        fontSize: "12.5px",
        lineHeight: 1.6,
      }}
    >
      <p style={{ margin: "0 0 4px" }}>Waiting for the first telemetry frame.</p>
      <p style={{ margin: 0, color: "var(--theme-textFaint)" }}>
        No robot on the bench? Start the dashboard with <code style={{ fontFamily: "var(--mono)" }}>--sim</code>{" "}
        to publish simulated telemetry.
      </p>
    </div>
  );
}
