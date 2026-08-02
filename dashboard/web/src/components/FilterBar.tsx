interface FilterBarProps {
  query: string;
  onQueryChange: (query: string) => void;
  placeholder?: string;
  /** Optional filter chip buttons. */
  chips?: { id: string; label: string; active: boolean; onClick: () => void }[];
}

/**
 * Filter bar with a search input and optional filter chips.
 * Carries over the search-input styling from globals.css.
 */
export default function FilterBar({
  query,
  onQueryChange,
  placeholder = "Filter by name or key...",
  chips,
}: FilterBarProps) {
  return (
    <div
      style={{
        display: "flex",
        alignItems: "center",
        justifyContent: "space-between",
        gap: "12px",
        flexWrap: "wrap",
      }}
    >
      <div style={{ position: "relative", flex: 1, minWidth: "220px", maxWidth: "360px" }}>
        <svg
          width="14"
          height="14"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          style={{
            position: "absolute",
            left: "10px",
            top: "50%",
            transform: "translateY(-50%)",
            color: "var(--theme-textFaint)",
            pointerEvents: "none",
          }}
          aria-hidden
        >
          <circle cx="11" cy="11" r="8" />
          <path d="m21 21-4.3-4.3" />
        </svg>
        <input
          type="text"
          value={query}
          onChange={(e) => onQueryChange(e.target.value)}
          placeholder={placeholder}
          className="search-input"
        />
      </div>

      {chips && chips.length > 0 && (
        <div style={{ display: "flex", alignItems: "center", gap: "6px", flexWrap: "wrap" }}>
          {chips.map((chip) => (
            <button
              key={chip.id}
              type="button"
              onClick={chip.onClick}
              style={{
                padding: "4px 10px",
                borderRadius: "12px",
                border: chip.active ? "1px solid var(--accent)" : "1px solid var(--theme-border)",
                background: chip.active ? "var(--theme-blueBg)" : "var(--theme-card)",
                color: chip.active ? "var(--accent)" : "var(--theme-textMuted)",
                fontSize: "11px",
                fontFamily: "var(--mono)",
                fontWeight: chip.active ? 600 : 400,
                cursor: "pointer",
                transition: "all var(--dur) var(--ease)",
              }}
            >
              {chip.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
