import type { ReactNode } from "react";

interface PageHeaderProps {
  title: string;
  subtitle?: ReactNode;
  /** Optional status badge shown next to the title (e.g. "Connected" pill on Live). */
  badge?: ReactNode;
}

/**
 * Consistent page header across all dashboard pages — title, subtitle, optional badge.
 * Extracted from Live's header pattern so every page uses the same 18/12.5 px heading layout.
 */
export default function PageHeader({ title, subtitle, badge }: PageHeaderProps) {
  return (
    <header style={{ display: "flex", flexDirection: "column", gap: "6px" }}>
      <div style={{ display: "flex", alignItems: "center", gap: "10px" }}>
        <h1 style={{ margin: 0, fontSize: "18px", fontWeight: 600, letterSpacing: "-0.02em" }}>
          {title}
        </h1>
        {badge}
      </div>
      {subtitle && (
        <p
          style={{
            margin: 0,
            fontSize: "12.5px",
            color: "var(--theme-textMuted)",
            lineHeight: 1.5,
            maxWidth: "640px",
          }}
        >
          {subtitle}
        </p>
      )}
    </header>
  );
}
