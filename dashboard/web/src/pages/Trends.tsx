import { useEffect, useState } from "react";
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import DataTable, { type Column } from "../components/DataTable";
import PageHeader from "../components/PageHeader";
import SeverityBadge from "../components/SeverityBadge";
import StatCard from "../components/StatCard";
import type { EventRow, LogRow, TrendPoint } from "../types";

const METRICS = [
  { id: "min_voltage", label: "Min Voltage", unit: "V", floor: 6.8 },
  { id: "voltage_droop", label: "Voltage Droop", unit: "V" },
  { id: "can_error_increase", label: "CAN Error Increase", unit: "errors" },
  { id: "loop_p95_ms", label: "Loop P95", unit: "ms", ceiling: 20 },
  { id: "loop_overruns", label: "Loop Overruns", unit: "count" },
];

export default function Trends() {
  const [selectedMetric, setSelectedMetric] = useState(METRICS[0]);
  const [trendData, setTrendData] = useState<TrendPoint[]>([]);
  const [logs, setLogs] = useState<LogRow[]>([]);
  const [events, setEvents] = useState<EventRow[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([
      fetch(`/api/trends?metric=${selectedMetric.id}`).then((r) => (r.ok ? r.json() : [])),
      fetch("/api/logs").then((r) => (r.ok ? r.json() : [])),
      fetch("/api/events").then((r) => (r.ok ? r.json() : [])),
    ])
      .then(([tData, lData, eData]) => {
        setTrendData(tData);
        setLogs(lData);
        setEvents(eData);
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [selectedMetric]);

  const logColumns: Column<LogRow>[] = [
    {
      key: "matchKey",
      header: "Match / Log",
      sortable: true,
      sortValue: (r) => r.matchKey || r.path,
      render: (r) => (
        <span style={{ fontWeight: 600, fontSize: "12.5px" }}>
          {r.matchKey || r.path.split("/").pop()}
        </span>
      ),
    },
    {
      key: "durationS",
      header: "Duration",
      sortable: true,
      align: "right",
      sortValue: (r) => r.durationS,
      render: (r) => <span className="numeric">{r.durationS.toFixed(1)} s</span>,
    },
    {
      key: "gitSha",
      header: "Git Commit",
      render: (r) => (
        <span className="numeric" style={{ fontSize: "11px", color: "var(--accent)" }}>
          {r.gitSha ? r.gitSha.substring(0, 7) : "—"}
        </span>
      ),
    },
  ];

  const chartData = trendData.map((pt, i) => ({
    name: pt.matchKey || `Log #${i + 1}`,
    value: pt.value,
  }));

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "28px", padding: "24px" }}>
      <PageHeader
        title="Season Trends"
        subtitle="Multi-match telemetry metrics and event logs queryable directly from the SQLite trend store"
      />

      {loading ? (
        <div className="card" style={{ padding: "32px", textAlign: "center", color: "var(--theme-textMuted)" }}>
          Querying trend store database...
        </div>
      ) : logs.length === 0 ? (
        <div className="card" style={{ padding: "32px 20px", borderStyle: "dashed", textAlign: "center" }}>
          <p style={{ margin: "0 0 6px", fontSize: "13px", fontWeight: 500 }}>No SQLite trend store active</p>
          <p style={{ margin: 0, fontSize: "12px", color: "var(--theme-textMuted)" }}>
            Run Mode A ingestion on your <code style={{ fontFamily: "var(--mono)" }}>.wpilog</code> files or start the dashboard with <code style={{ fontFamily: "var(--mono)" }}>--db &lt;store.db&gt;</code> to view season trends.
          </p>
        </div>
      ) : (
        <>
          {/* Tab Selector */}
          <div className="tab-bar">
            {METRICS.map((m) => (
              <button
                key={m.id}
                type="button"
                className="tab-item"
                aria-selected={selectedMetric.id === m.id}
                onClick={() => setSelectedMetric(m)}
              >
                {m.label}
              </button>
            ))}
          </div>

          {/* Key Summary Stat Cards */}
          <div className="stat-grid">
            <StatCard
              label="Ingested Logs"
              value={String(logs.length)}
              subtitle="Season matches stored"
            />
            <StatCard
              label="Metric Average"
              value={
                trendData.length > 0
                  ? `${(trendData.reduce((acc, p) => acc + p.value, 0) / trendData.length).toFixed(2)} ${selectedMetric.unit}`
                  : "—"
              }
              subtitle={`Mean ${selectedMetric.label}`}
            />
            <StatCard
              label="Recorded Events"
              value={String(events.length)}
              subtitle="Total safety flags"
              severity={events.length > 0 ? "WATCH" : "OK"}
            />
          </div>

          {/* Main Bar Chart */}
          <section className="card" style={{ padding: "18px 20px 10px", display: "flex", flexDirection: "column", gap: "12px" }}>
            <h3 className="eyebrow" style={{ margin: 0 }}>
              {selectedMetric.label} Across Matches ({selectedMetric.unit})
            </h3>
            <div style={{ height: "240px" }}>
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={chartData} margin={{ top: 10, right: 10, bottom: 0, left: -10 }}>
                  <CartesianGrid stroke="var(--theme-border4)" vertical={false} />
                  <XAxis dataKey="name" tick={{ fill: "var(--theme-textFaint2)", fontSize: 10 }} axisLine={false} tickLine={false} />
                  <YAxis tick={{ fill: "var(--theme-textFaint2)", fontSize: 10 }} axisLine={false} tickLine={false} />
                  <Tooltip
                    cursor={{ fill: "var(--theme-hover)" }}
                    contentStyle={{
                      background: "var(--theme-panel)",
                      border: "1px solid var(--theme-border2)",
                      borderRadius: "6px",
                      fontSize: "12px",
                    }}
                    formatter={(val) => [`${Number(val).toFixed(2)} ${selectedMetric.unit}`, selectedMetric.label]}
                  />
                  <Bar dataKey="value" fill="var(--accent)" radius={[4, 4, 0, 0]} className="trend-bar" />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </section>

          {/* Ingested Matches Table */}
          <section style={{ display: "flex", flexDirection: "column", gap: "10px" }}>
            <h2 className="eyebrow" style={{ margin: 0 }}>
              Ingested Match Log Inventory
            </h2>
            <DataTable columns={logColumns} data={logs} keyExtractor={(r) => String(r.id)} />
          </section>

          {/* Recorded Events Timeline */}
          {events.length > 0 && (
            <section style={{ display: "flex", flexDirection: "column", gap: "10px" }}>
              <h2 className="eyebrow" style={{ margin: 0 }}>
                Safety Flag History
              </h2>
              <div style={{ display: "flex", flexDirection: "column", gap: "6px" }}>
                {events.map((e, idx) => (
                  <div key={idx} className="card" style={{ padding: "10px 14px", display: "flex", alignItems: "center", justifyContent: "space-between" }}>
                    <div style={{ display: "flex", alignItems: "center", gap: "10px" }}>
                      <SeverityBadge severity={e.severity} />
                      <span style={{ fontWeight: 600, fontSize: "12.5px" }}>{e.kind}</span>
                      <span style={{ color: "var(--theme-textMuted)", fontSize: "12px" }}>{e.detail}</span>
                    </div>
                  </div>
                ))}
              </div>
            </section>
          )}
        </>
      )}
    </div>
  );
}
