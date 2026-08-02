import { useState } from "react";
import Sidebar from "./components/Sidebar";
import StatusRail from "./components/StatusRail";
import Live from "./pages/Live";
import Match from "./pages/Match";
import Paths from "./pages/Paths";
import Pit from "./pages/Pit";
import Profile from "./pages/Profile";
import Signals from "./pages/Signals";
import Trends from "./pages/Trends";
import { useTelemetry } from "./useTelemetry";
import { useTheme } from "./useTheme";

export default function App() {
  const telemetry = useTelemetry();
  const [theme, toggleTheme] = useTheme();
  const [page, setPage] = useState("live");

  const renderPage = () => {
    switch (page) {
      case "live":
        return <Live {...telemetry} />;
      case "pit":
        return <Pit {...telemetry} />;
      case "match":
        return <Match {...telemetry} />;
      case "signals":
        return <Signals {...telemetry} />;
      case "paths":
        return <Paths />;
      case "trends":
        return <Trends />;
      case "robot":
        return <Profile {...telemetry} />;
      default:
        return <Live {...telemetry} />;
    }
  };

  return (
    <div className="shell">
      <Sidebar
        active={page}
        onSelect={setPage}
        connected={telemetry.tick?.connected ?? false}
        theme={theme}
        onToggleTheme={toggleTheme}
      />
      <main className="shell-main">{renderPage()}</main>
      <StatusRail tick={telemetry.tick} streamOpen={telemetry.streamOpen} />
    </div>
  );
}

