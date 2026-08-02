import { useState } from "react";
import DataTable, { type Column } from "../components/DataTable";
import FilterBar from "../components/FilterBar";
import MiniChart from "../components/MiniChart";
import PageHeader from "../components/PageHeader";
import SignalChart from "../components/SignalChart";
import type { Telemetry } from "../useTelemetry";

interface TopicRow {
  name: string;
  value: number | null;
  samples: number;
}

export default function Signals({ tick, series }: Telemetry) {
  const [query, setQuery] = useState("");
  const [prefixFilter, setPrefixFilter] = useState<string | null>(null);

  // Extract all topics from current telemetry frame signals or raw topics
  const topics: TopicRow[] = Object.entries(tick?.signals ?? {}).map(([role, s]) => ({
    name: s.key || role,
    value: s.value,
    samples: series[role]?.length ?? 0,
  }));

  // Unique prefixes for filter chips (e.g. /Drive, /PowerDistribution, /FMSInfo)
  const prefixes = Array.from(
    new Set(
      topics
        .map((t) => {
          const parts = t.name.split("/").filter(Boolean);
          return parts.length > 1 ? `/${parts[0]}` : null;
        })
        .filter(Boolean) as string[]
    )
  );

  const filteredTopics = topics.filter((t) => {
    const matchesQuery = t.name.toLowerCase().includes(query.toLowerCase());
    const matchesPrefix = prefixFilter ? t.name.startsWith(prefixFilter) : true;
    return matchesQuery && matchesPrefix;
  });

  const columns: Column<TopicRow>[] = [
    {
      key: "name",
      header: "Topic Name",
      sortable: true,
      sortValue: (r) => r.name,
      render: (r) => (
        <span
          style={{
            fontFamily: "var(--mono)",
            fontSize: "12px",
            color: "var(--theme-text)",
          }}
          title={r.name}
        >
          {r.name}
        </span>
      ),
    },
    {
      key: "value",
      header: "Value",
      sortable: true,
      align: "right",
      width: "120px",
      sortValue: (r) => r.value ?? -999999,
      render: (r) => (
        <span className="numeric" style={{ fontWeight: 500 }}>
          {r.value !== null && Number.isFinite(r.value)
            ? r.value.toFixed(Math.abs(r.value) >= 100 ? 0 : 2)
            : "—"}
        </span>
      ),
    },
    {
      key: "samples",
      header: "Samples",
      sortable: true,
      align: "right",
      width: "100px",
      sortValue: (r) => r.samples,
      render: (r) => (
        <span className="numeric" style={{ color: "var(--theme-textMuted)", fontSize: "11px" }}>
          {r.samples}
        </span>
      ),
    },
    {
      key: "sparkline",
      header: "Live Trace",
      width: "100px",
      align: "right",
      render: (r) => {
        const role = Object.keys(tick?.signals ?? {}).find((k) => tick?.signals[k].key === r.name);
        const points = role ? series[role] ?? [] : [];
        return <MiniChart points={points} />;
      },
    },
  ];

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "24px", padding: "24px" }}>
      <PageHeader
        title="Signals"
        subtitle={`Live NetworkTables topic browser — ${topics.length} active topics published`}
      />

      <FilterBar
        query={query}
        onQueryChange={setQuery}
        placeholder="Filter by NT path..."
        chips={[
          { id: "all", label: "All", active: prefixFilter === null, onClick: () => setPrefixFilter(null) },
          ...prefixes.map((p) => ({
            id: p,
            label: p,
            active: prefixFilter === p,
            onClick: () => setPrefixFilter(prefixFilter === p ? null : p),
          })),
        ]}
      />

      <DataTable
        columns={columns}
        data={filteredTopics}
        keyExtractor={(r) => r.name}
        emptyMessage="No NetworkTables topics found matching query."
        renderExpandedRow={(r) => {
          const role = Object.keys(tick?.signals ?? {}).find((k) => tick?.signals[k].key === r.name);
          const points = role ? series[role] ?? [] : [];
          return (
            <div style={{ height: "180px" }}>
              <SignalChart title={r.name} unit="" points={points} color="var(--accent)" />
            </div>
          );
        }}
      />
    </div>
  );
}
