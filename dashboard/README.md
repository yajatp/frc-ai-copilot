# Dashboard

A local web UI over the copilot's live telemetry and analysis layer — one screen instead of tabbing
between Elastic, AdvantageScope, PathPlanner and the Driver Station.

The point is not that it plots signals; AdvantageScope already does that well. The point is that the
15 analysis primitives in `:analysis` were only reachable by asking an AI to run an MCP tool and
reading prose back. This surfaces them directly, continuously, with no agent in the loop.

## Run it

```bash
export JAVA_HOME=~/wpilib/2026/jdk
export GRADLE_USER_HOME="$PWD/.gradle-home"

./gradlew :dashboard:run --args="--sim"     # built-in fake robot, no hardware needed
./gradlew :dashboard:run --args="--team 6369"   # connect to 10.63.69.2
./gradlew :dashboard:run --args="--host localhost"  # desktop simulation
```

Then open <http://localhost:5800>.

| Flag | Meaning |
|---|---|
| `--sim` | Run a built-in simulated robot and connect to it |
| `--host <addr>` | NetworkTables server address |
| `--team <n>` | Derive the roboRIO address as `10.TE.AM.2` (default 6369) |
| `--port <n>` | Web port (default 5800) |
| `--nt-port <n>` | NetworkTables port (default 5810) |

## How it fits together

```
NT4 ──► TelemetryHub ──► RollingBuffer ──► Series ──► analysis primitives ──► verdicts
                                                                                 │
                        DashboardServer ──► SSE (10 Hz) ──► React UI ◄───────────┘
```

`RollingBuffer.toSeries()` is the whole trick: `Series` is just parallel value/timestamp arrays, so
the primitives cannot tell a rolling NetworkTables window from a `.wpilog`. Live health is therefore
the *same code path* as Mode A's post-match report, and both speak `ModeA.Severity`.

Two cadences: raw values tick at 10 Hz so gauges feel live, health verdicts recompute at 2 Hz
because re-running the primitives over the full window ten times a second is pointless garbage for a
number that moves on the scale of seconds.

### Sampling

The NT subscription uses `NtClient.monitorAll(…)` with `sendAll(true)` and a 20 ms period. NT4's
default subscription delivers a periodic snapshot every 100 ms, which decimates a 50 Hz robot about
5:1 — fine for a voltage gauge, **wrong** for anything that counts occurrences rather than reading a
level, because the dropped samples are exactly the anomalous ones. Loop-overrun counts were roughly
5× low before this was fixed.

### Safety

- The server binds to **loopback only**. Robot telemetry does not go on the field network, and there
  is no CORS surface (the Vite dev server proxies to it instead).
- The dashboard is **read-only**. It never touches `NtWriteGuard`, the sole write path in this
  codebase, so nothing here can command the robot.

## Web UI

Vite + React, built to static files that the Java process serves. **Node is a build-time tool only** —
the shipped distribution is one JVM and a folder of assets, which is what you want on a pit laptop
with no internet.

```bash
cd dashboard/web
npm install
npm run dev      # dev server on :5173, proxies /api to :5800
npm run build    # emits dist/, which the Java server serves
```

`./gradlew :dashboard:installDist` runs the web build and bundles `dist/` into the distribution.

The design system (`src/globals.css`) is the same Linear-derived token set as the team's ScoutPanda
dashboard, so the two tools look like one family. Components style themselves from those variables
inline; there is no Tailwind.

## Status

Phase 1 ships the **Live** page: connection state, the four health verdicts, live charts, and signal
coverage. The remaining pages (Pit, Match, Signals, Paths, Trends, Profile) are listed in the nav as
disabled so the intended shape of the tool is visible.

Signal coverage is reported rather than hidden: a check whose input the robot does not publish reads
as "cannot see this", never as "fine".
