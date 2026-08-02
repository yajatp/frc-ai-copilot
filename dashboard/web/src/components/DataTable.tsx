import { useState, type ReactNode } from "react";

export interface Column<T> {
  key: string;
  header: string;
  render: (row: T, index: number) => ReactNode;
  sortable?: boolean;
  sortValue?: (row: T) => string | number;
  width?: string;
  align?: "left" | "center" | "right";
}

interface DataTableProps<T> {
  columns: Column<T>[];
  data: T[];
  keyExtractor: (row: T, index: number) => string;
  emptyMessage?: string;
  onRowClick?: (row: T) => void;
  renderExpandedRow?: (row: T) => ReactNode;
}


/**
 * Reusable sortable & filterable data table matching the card and row-hover design system.
 */
export default function DataTable<T>({
  columns,
  data,
  keyExtractor,
  emptyMessage = "No matching records found.",
  onRowClick,
  renderExpandedRow,
}: DataTableProps<T>) {
  const [sortKey, setSortKey] = useState<string | null>(null);
  const [sortAsc, setSortAsc] = useState(true);
  const [expandedKey, setExpandedKey] = useState<string | null>(null);

  const handleHeaderClick = (col: Column<T>) => {
    if (!col.sortable) return;
    if (sortKey === col.key) {
      setSortAsc((prev) => !prev);
    } else {
      setSortKey(col.key);
      setSortAsc(true);
    }
  };

  const sortedData = [...data].sort((a, b) => {
    if (!sortKey) return 0;
    const col = columns.find((c) => c.key === sortKey);
    if (!col || !col.sortValue) return 0;
    const valA = col.sortValue(a);
    const valB = col.sortValue(b);
    if (valA < valB) return sortAsc ? -1 : 1;
    if (valA > valB) return sortAsc ? 1 : -1;
    return 0;
  });

  return (
    <div className="card" style={{ overflow: "hidden" }}>
      {data.length === 0 ? (
        <div
          style={{
            padding: "32px 20px",
            textAlign: "center",
            color: "var(--theme-textMuted)",
            fontSize: "12.5px",
          }}
        >
          {emptyMessage}
        </div>
      ) : (
        <div style={{ overflowX: "auto" }}>
          <table
            style={{
              width: "100%",
              borderCollapse: "collapse",
              textAlign: "left",
              fontSize: "12.5px",
            }}
          >
            <thead>
              <tr
                style={{
                  borderBottom: "1px solid var(--theme-border)",
                  background: "var(--theme-rail)",
                }}
              >
                {columns.map((col) => (
                  <th
                    key={col.key}
                    onClick={() => handleHeaderClick(col)}
                    className="eyebrow"
                    style={{
                      padding: "10px 14px",
                      width: col.width,
                      textAlign: col.align || "left",
                      cursor: col.sortable ? "pointer" : "default",
                      userSelect: "none",
                      whiteSpace: "nowrap",
                    }}
                  >
                    {col.header}
                    {col.sortable && sortKey === col.key && (
                      <span className="sort-indicator">{sortAsc ? "▲" : "▼"}</span>
                    )}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {sortedData.map((row, index) => {
                const key = keyExtractor(row, index);
                const isExpanded = expandedKey === key;

                return (
                  <tr key={key} style={{ borderBottom: "1px solid var(--theme-border4)" }}>
                    <td colSpan={columns.length} style={{ padding: 0 }}>
                      <div
                        className="row-hover"
                        onClick={() => {
                          if (renderExpandedRow) {
                            setExpandedKey(isExpanded ? null : key);
                          }
                          if (onRowClick) onRowClick(row);
                        }}
                        style={{
                          display: "grid",
                          gridTemplateColumns: columns
                            .map((c) => c.width || "1fr")
                            .join(" "),
                          alignItems: "center",
                          padding: "10px 14px",
                          cursor: onRowClick || renderExpandedRow ? "pointer" : "default",
                          background: isExpanded ? "var(--theme-selected)" : "transparent",
                        }}
                      >
                        {columns.map((col) => (
                          <div
                            key={col.key}
                            style={{
                              textAlign: col.align || "left",
                              overflow: "hidden",
                              textOverflow: "ellipsis",
                              whiteSpace: "nowrap",
                            }}
                          >
                            {col.render(row, index)}
                          </div>
                        ))}
                      </div>
                      {renderExpandedRow && isExpanded && (
                        <div
                          style={{
                            padding: "14px",
                            background: "var(--theme-panel)",
                            borderTop: "1px solid var(--theme-border4)",
                            borderBottom: "1px solid var(--theme-border2)",
                          }}
                        >
                          {renderExpandedRow(row)}
                        </div>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>

          </table>
        </div>
      )}
    </div>
  );
}
