import type { Point } from "../types";
import { Area, AreaChart, ResponsiveContainer } from "recharts";

interface MiniChartProps {
  points: Point[];
  color?: string;
}

/**
 * A small inline sparkline (80x28 px) for embedding inside data table rows and stat cards.
 * Uses Recharts AreaChart with zero axes, margins, or tooltips for maximum performance.
 */
export default function MiniChart({ points, color = "var(--accent)" }: MiniChartProps) {
  if (points.length < 2) {
    return (
      <div
        style={{
          width: "80px",
          height: "28px",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          color: "var(--theme-textGhost)",
          fontSize: "10px",
          fontFamily: "var(--mono)",
        }}
      >
        —
      </div>
    );
  }

  const latest = points[points.length - 1].t;
  const data = points.map((p) => ({ age: (p.t - latest) / 1000, v: p.v }));
  const gradientId = `mini-${Math.random().toString(36).substring(2, 7)}`;

  return (
    <div style={{ width: "80px", height: "28px" }}>
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={data} margin={{ top: 2, right: 2, bottom: 2, left: 2 }}>
          <defs>
            <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={color} stopOpacity={0.3} />
              <stop offset="100%" stopColor={color} stopOpacity={0} />
            </linearGradient>
          </defs>
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
    </div>
  );
}
