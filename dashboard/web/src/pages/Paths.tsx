import { useEffect, useState } from "react";
import DataTable, { type Column } from "../components/DataTable";
import PageHeader from "../components/PageHeader";
import StatCard from "../components/StatCard";
import type { PathData, Waypoint } from "../types";

export default function Paths() {
  const [paths, setPaths] = useState<PathData[]>([]);
  const [selectedPath, setSelectedPath] = useState<PathData | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch("/api/paths")
      .then((res) => (res.ok ? res.json() : []))
      .then((data: PathData[]) => {
        setPaths(data);
        if (data.length > 0) setSelectedPath(data[0]);
      })
      .catch(() => setPaths([]))
      .finally(() => setLoading(false));
  }, []);

  const waypoints = selectedPath?.waypoints ?? [];

  const waypointColumns: Column<Waypoint>[] = [
    {
      key: "idx",
      header: "#",
      width: "40px",
      render: (_, idx) => <span className="numeric">{idx + 1}</span>,
    },
    {
      key: "anchorX",
      header: "X (m)",
      align: "right",
      render: (w) => <span className="numeric">{w.anchor.x.toFixed(2)}</span>,
    },
    {
      key: "anchorY",
      header: "Y (m)",
      align: "right",
      render: (w) => <span className="numeric">{w.anchor.y.toFixed(2)}</span>,
    },
    {
      key: "linkedName",
      header: "Linked Event",
      render: (w) => (
        <span style={{ fontSize: "11px", color: w.linkedName ? "var(--accent)" : "var(--theme-textFaint)" }}>
          {w.linkedName || "—"}
        </span>
      ),
    },
    {
      key: "isLocked",
      header: "State",
      align: "right",
      render: (w) => (
        <span className="eyebrow" style={{ color: w.isLocked ? "var(--theme-amberDot)" : "var(--theme-green)" }}>
          {w.isLocked ? "Locked" : "Unlocked"}
        </span>
      ),
    },
  ];

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "28px", padding: "24px" }}>
      <PageHeader
        title="PathPlanner Waypoints & Autos"
        subtitle="Visualize autonomous trajectories on the 2026 REBUILT field and review Mode A waypoint diffs"
      />

      {loading ? (
        <div className="card" style={{ padding: "32px", textAlign: "center", color: "var(--theme-textMuted)" }}>
          Loading PathPlanner trajectories...
        </div>
      ) : paths.length === 0 ? (
        <div className="card" style={{ padding: "32px 20px", borderStyle: "dashed", textAlign: "center" }}>
          <p style={{ margin: "0 0 6px", fontSize: "13px", fontWeight: 500 }}>No PathPlanner paths loaded</p>
          <p style={{ margin: 0, fontSize: "12px", color: "var(--theme-textMuted)" }}>
            Pass <code style={{ fontFamily: "var(--mono)" }}>--paths &lt;dir&gt;</code> to the dashboard to inspect your robot's <code style={{ fontFamily: "var(--mono)" }}>.path</code> and <code style={{ fontFamily: "var(--mono)" }}>.auto</code> files.
          </p>
        </div>
      ) : (
        <div style={{ display: "grid", gridTemplateColumns: "240px 1fr", gap: "16px" }}>
          {/* Path List Sidebar */}
          <div style={{ display: "flex", flexDirection: "column", gap: "8px" }}>
            <h2 className="eyebrow" style={{ margin: 0 }}>
              Trajectories ({paths.length})
            </h2>
            {paths.map((p) => {
              const active = selectedPath?.name === p.name;
              return (
                <button
                  key={p.name}
                  type="button"
                  onClick={() => setSelectedPath(p)}
                  className="card"
                  style={{
                    padding: "12px 14px",
                    textAlign: "left",
                    cursor: "pointer",
                    background: active ? "var(--theme-selected)" : "var(--theme-card)",
                    borderColor: active ? "var(--accent)" : "var(--theme-border)",
                    display: "flex",
                    flexDirection: "column",
                    gap: "4px",
                  }}
                >
                  <span style={{ fontWeight: 600, fontSize: "13px", color: active ? "var(--accent)" : "var(--theme-text)" }}>
                    {p.name}
                  </span>
                  <span className="eyebrow" style={{ color: "var(--theme-textMuted)" }}>
                    {p.waypoints.length} waypoints • Max {p.globalConstraints.maxVelocity} m/s
                  </span>
                </button>
              );
            })}
          </div>

          {/* Path Details & Field Canvas */}
          <div style={{ display: "flex", flexDirection: "column", gap: "20px" }}>
            {selectedPath && (
              <>
                {/* Stat cards for selected path */}
                <div className="stat-grid">
                  <StatCard
                    label="Max Velocity"
                    value={`${selectedPath.globalConstraints.maxVelocity} m/s`}
                    subtitle="Global path limit"
                  />
                  <StatCard
                    label="Max Accel"
                    value={`${selectedPath.globalConstraints.maxAcceleration} m/s²`}
                    subtitle="Kinematic constraint"
                  />
                  <StatCard
                    label="Waypoints"
                    value={String(selectedPath.waypoints.length)}
                    subtitle={`${selectedPath.waypoints.filter((w) => w.isLocked).length} locked`}
                  />
                  <StatCard
                    label="End Goal Speed"
                    value={`${selectedPath.goalEndState?.velocity ?? 0} m/s`}
                    subtitle={`Heading: ${selectedPath.goalEndState?.rotation ?? 0}°`}
                  />
                </div>

                {/* Field Visualizer SVG Canvas */}
                <div className="field-canvas" style={{ position: "relative" }}>
                  <svg
                    viewBox="0 0 16.54 8.21"
                    style={{ width: "100%", height: "100%", background: "var(--theme-panel)" }}
                  >
                    {/* Field Grid / Midline */}
                    <line x1="8.27" y1="0" x2="8.27" y2="8.21" stroke="var(--theme-border2)" strokeDasharray="0.2 0.2" strokeWidth="0.05" />
                    <rect x="0" y="0" width="16.54" height="8.21" fill="none" stroke="var(--theme-border3)" strokeWidth="0.1" />

                    {/* Polyline Trajectory */}
                    {waypoints.length > 1 && (
                      <polyline
                        points={waypoints.map((w) => `${w.anchor.x},${8.21 - w.anchor.y}`).join(" ")}
                        fill="none"
                        stroke="var(--accent)"
                        strokeWidth="0.08"
                        strokeLinecap="round"
                      />
                    )}

                    {/* Waypoint Dots */}
                    {waypoints.map((w, idx) => (
                      <g key={idx}>
                        <circle
                          cx={w.anchor.x}
                          cy={8.21 - w.anchor.y}
                          r="0.18"
                          fill={idx === 0 ? "var(--theme-green)" : idx === waypoints.length - 1 ? "var(--theme-red)" : "var(--accent)"}
                        />
                        <text
                          x={w.anchor.x}
                          y={8.21 - w.anchor.y + 0.4}
                          fontSize="0.3"
                          fill="var(--theme-text)"
                          textAnchor="middle"
                          fontFamily="var(--mono)"
                        >
                          W{idx + 1}
                        </text>
                      </g>
                    ))}
                  </svg>
                </div>

                {/* Waypoints DataTable */}
                <section style={{ display: "flex", flexDirection: "column", gap: "10px" }}>
                  <h2 className="eyebrow" style={{ margin: 0 }}>
                    Waypoint Coordinate Table
                  </h2>
                  <DataTable
                    columns={waypointColumns}
                    data={waypoints}
                    keyExtractor={(_, i) => String(i)}
                  />
                </section>
              </>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
