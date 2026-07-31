import {
  Area,
  AreaChart,
  CartesianGrid,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import type { Point } from "../types";

interface SignalChartProps {
  title: string;
  unit: string;
  points: Point[];
  color: string;
  /** Optional horizontal marker, e.g. the brownout floor. */
  threshold?: { value: number; label: string };
  /** Force the y-domain; otherwise it is derived from the data with a little headroom. */
  domain?: [number, number];
}

/**
 * A live single-signal chart.
 *
 * <p>Animation is off on purpose: at a 10 Hz tick rate, Recharts' transitions would fight the
 * incoming data and make the trace look like it is lagging the robot.
 */
export default function SignalChart({ title, unit, points, color, threshold, domain }: SignalChartProps) {
  const gradientId = `fill-${title.replace(/\W/g, "")}`;
  const hasData = points.length > 1;

  // Time is shown relative to the newest sample — "how long ago", which is what you actually
  // read a live trace for. Absolute robot timestamps are meaningless to a person.
  const latest = points.length > 0 ? points[points.length - 1].t : 0;
  const data = points.map((p) => ({ age: (p.t - latest) / 1000, v: p.v }));

  return (
    <section
      style={{
        border: "1px solid var(--theme-border)",
        background: "var(--theme-card)",
        borderRadius: "8px",
        padding: "14px 15px 8px",
        display: "flex",
        flexDirection: "column",
        gap: "4px",
        minWidth: 0,
      }}
    >
      <header style={{ display: "flex", alignItems: "baseline", justifyContent: "space-between", gap: "8px" }}>
        <h3 className="eyebrow" style={{ margin: 0 }}>
          {title}
        </h3>
        <span className="numeric" style={{ fontSize: "13px", color: "var(--theme-text)" }}>
          {hasData ? `${points[points.length - 1].v.toFixed(2)} ${unit}` : "—"}
        </span>
      </header>

      <div style={{ height: "148px", marginLeft: "-8px" }}>
        {hasData ? (
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
              <defs>
                <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor={color} stopOpacity={0.22} />
                  <stop offset="100%" stopColor={color} stopOpacity={0} />
                </linearGradient>
              </defs>
              <CartesianGrid stroke="var(--theme-border4)" vertical={false} />
              <XAxis
                dataKey="age"
                type="number"
                domain={["dataMin", 0]}
                // Within half a second of the newest sample, read it as "now" — strict equality
                // misses a floating-point zero and leaves a stray "0s" tick.
                tickFormatter={(v: number) => (Math.abs(v) < 0.5 ? "now" : `${Math.round(v)}s`)}
                tick={{ fill: "var(--theme-textFaint2)", fontSize: 10 }}
                axisLine={false}
                tickLine={false}
                minTickGap={28}
              />
              <YAxis
                domain={domain ?? ["auto", "auto"]}
                tick={{ fill: "var(--theme-textFaint2)", fontSize: 10 }}
                axisLine={false}
                tickLine={false}
                width={38}
                tickFormatter={(v: number) => v.toFixed(v >= 100 ? 0 : 1)}
              />
              {threshold && (
                <ReferenceLine
                  y={threshold.value}
                  stroke="var(--theme-red)"
                  strokeDasharray="3 3"
                  strokeOpacity={0.65}
                  label={{
                    value: threshold.label,
                    position: "insideTopRight",
                    fill: "var(--theme-red)",
                    fontSize: 9.5,
                  }}
                />
              )}
              <Tooltip
                isAnimationActive={false}
                cursor={{ stroke: "var(--theme-border3)" }}
                contentStyle={{
                  background: "var(--theme-panel)",
                  border: "1px solid var(--theme-border2)",
                  borderRadius: "6px",
                  fontSize: "12px",
                }}
                labelFormatter={(label) => {
                  const age = Number(label);
                  return Math.abs(age) < 0.05 ? "now" : `${Math.abs(age).toFixed(1)} s ago`;
                }}
                formatter={(value) => [`${Number(value).toFixed(2)} ${unit}`, title]}
              />
              <Area
                type="monotone"
                dataKey="v"
                stroke={color}
                strokeWidth={1.5}
                fill={`url(#${gradientId})`}
                isAnimationActive={false}
                dot={false}
              />
            </AreaChart>
          </ResponsiveContainer>
        ) : (
          <div
            style={{
              height: "100%",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              fontSize: "12.5px",
              color: "var(--theme-textFaint)",
            }}
          >
            Waiting for samples…
          </div>
        )}
      </div>
    </section>
  );
}
