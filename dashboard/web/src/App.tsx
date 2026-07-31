import { useState } from "react";
import Sidebar from "./components/Sidebar";
import StatusRail from "./components/StatusRail";
import Live from "./pages/Live";
import { useTelemetry } from "./useTelemetry";
import { useTheme } from "./useTheme";

export default function App() {
  const telemetry = useTelemetry();
  const [theme, toggleTheme] = useTheme();
  // Phase 1 ships one page; the nav already lists the rest so the shape of the tool is legible.
  const [page, setPage] = useState("live");

  return (
    <div style={{ display: "flex", height: "100%", overflow: "hidden" }}>
      <Sidebar
        active={page}
        onSelect={setPage}
        connected={telemetry.tick?.connected ?? false}
        theme={theme}
        onToggleTheme={toggleTheme}
      />
      <main style={{ flex: 1, minWidth: 0, overflowY: "auto", background: "var(--theme-bg)" }}>
        <Live {...telemetry} />
      </main>
      <StatusRail tick={telemetry.tick} streamOpen={telemetry.streamOpen} />
    </div>
  );
}
