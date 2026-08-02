import ThemeToggle from "./ThemeToggle";
import type { Theme } from "../useTheme";

interface NavEntry {
  id: string;
  label: string;
  /** Pages arriving in later phases are listed but disabled, so the shape of the tool is visible. */
  ready: boolean;
}

const NAV: { section: string; items: NavEntry[] }[] = [
  {
    section: "Robot",
    items: [
      { id: "live", label: "Live", ready: true },
      { id: "pit", label: "Pit", ready: true },
      { id: "match", label: "Match", ready: true },
    ],
  },
  {
    section: "Analysis",
    items: [
      { id: "signals", label: "Signals", ready: true },
      { id: "paths", label: "Paths", ready: true },
      { id: "trends", label: "Trends", ready: true },
    ],
  },
  {
    section: "Setup",
    items: [{ id: "robot", label: "Profile & coverage", ready: true }],
  },
];


interface SidebarProps {
  active: string;
  onSelect: (id: string) => void;
  connected: boolean;
  theme: Theme;
  onToggleTheme: () => void;
}

export default function Sidebar({ active, onSelect, connected, theme, onToggleTheme }: SidebarProps) {
  return (
    <nav className="shell-sidebar" aria-label="Sections">
      <div
        className="sidebar-head"
        style={{
          padding: "16px 16px 14px",
          display: "flex",
          alignItems: "center",
          gap: "9px",
          flexShrink: 0,
        }}
      >
        <div
          aria-hidden
          style={{
            width: "18px",
            height: "18px",
            borderRadius: "5px",
            background: "var(--accent)",
            flexShrink: 0,
          }}
        />
        <div style={{ fontSize: "13px", fontWeight: 600, letterSpacing: "-0.02em" }}>
          FRC Copilot
        </div>
      </div>

      <div className="sidebar-nav">
        {NAV.map((group) => (
          <div key={group.section} className="sidebar-group" style={{ marginBottom: "16px" }}>
            <div className="eyebrow sidebar-group-label" style={{ padding: "0 8px 6px" }}>
              {group.section}
            </div>
            {group.items.map((item) => {
              const isActive = item.id === active;
              return (
                <button
                  key={item.id}
                  className={item.ready ? "nav-item" : undefined}
                  onClick={() => item.ready && onSelect(item.id)}
                  disabled={!item.ready}
                  aria-current={isActive ? "page" : undefined}
                  style={{
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "space-between",
                    width: "100%",
                    padding: "6px 8px",
                    marginBottom: "1px",
                    border: "none",
                    borderRadius: "5px",
                    font: "inherit",
                    fontSize: "13px",
                    textAlign: "left",
                    cursor: item.ready ? "pointer" : "default",
                    background: isActive ? "var(--theme-selected)" : "transparent",
                    color: item.ready
                      ? isActive
                        ? "var(--theme-text)"
                        : "var(--theme-text2)"
                      : "var(--theme-textGhost)",
                  }}
                >
                  <span>{item.label}</span>
                  {!item.ready && (
                    <span className="eyebrow" style={{ fontSize: "9px" }}>
                      Soon
                    </span>
                  )}
                </button>
              );
            })}
          </div>
        ))}
      </div>

      <div className="sidebar-foot">
        <div style={{ display: "flex", alignItems: "center", gap: "7px", minWidth: 0 }}>
          <span
            aria-hidden
            style={{
              width: "6px",
              height: "6px",
              borderRadius: "50%",
              background: connected ? "var(--theme-green)" : "var(--theme-textGhost)",
              flexShrink: 0,
            }}
          />
          <span className="sidebar-foot-label" style={{ fontSize: "12px", color: "var(--theme-textMuted)" }}>
            {connected ? "Robot connected" : "No robot"}
          </span>
        </div>
        <ThemeToggle theme={theme} onToggle={onToggleTheme} />
      </div>
    </nav>
  );
}
