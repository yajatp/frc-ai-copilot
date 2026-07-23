# FRC AI Copilot

An open-source, team-agnostic AI copilot for FRC robot code, built by Team 6369 (Mercenary
Robotics) / 6773 for use across both teams. It combines deep telemetry analysis, a live
competition mode, and — the north star — a **254-style agentic closed loop that observes logs,
edits robot code, runs it in sim/replay, verifies against success criteria, and iterates.**

It ships as an MCP server + Claude Code skills (Claude Code is the harness — we make it
FRC-fluent, we don't build a coding agent).

> Status: early build. Modules 1–3 are implemented and tested; see the plan for the full roadmap.

## Why this exists
Teams do not recompile robot code at competition — they tweak autonomous paths. So the tool has
two modes:
- **Mode A (live/competition):** fast, cheap — brownout/CAN/battery safety flags + PathPlanner
  waypoint suggestions between matches. (The 2026 REBUILT "hidden meta" is energy management, so
  power analysis is the centerpiece.)
- **Mode B (post-event/off-season):** the deep work — full audits, season trends, and the
  agentic sim/replay code-improvement loop.

## Modules
| Module | Status | What it does |
|---|---|---|
| 1 · `core-ingest` | ✅ built + tested | `.wpilog` parsing via WPILib `DataLogReader`; SQLite trend store (no re-parsing for season queries) |
| 2 · `profile` | ✅ built + tested | Team/robot profile **bootstrapped** from the repo (pathplanner settings, `*Constants.java` CAN IDs, vendordeps) |
| 3 · `analysis` | ✅ built + tested | Primitive, composable analysis with epistemic guardrails (power/brownout, CAN health, statistics) |
| 4 · write-layer | ⏳ planned | PathPlanner `.path`/`.auto` writer + safety-scoped tunable NT writes |
| 5 · `live-nt` | ⏳ planned | NT4 live telemetry |
| 6 · `simreplay` | ⏳ planned | The agentic closed-loop code improvement (replay → sim → assertions → regression suite) |
| — · `mcp-server` | ⏳ planned | Wires tools over MCP stdio + skills |

## Build
Requires the WPILib 2026 install (provides the JDK + offline Maven repo). Everything stays
project-local:

```bash
export JAVA_HOME=~/wpilib/2026/jdk
export GRADLE_USER_HOME="$PWD/.gradle-home"
./gradlew build          # compile + test all modules
```

Try a module CLI:
```bash
./gradlew :analysis:installDist
analysis/build/install/analysis/bin/analysis analyze <match.wpilog>
```

## Acknowledgments
Built independently, but informed by prior art in the FRC ecosystem — WPILib, AdvantageKit
(Team 6328), PathPlanner, maple-sim (IronMaple), wpilog-mcp (Team 2363), and ClaudeScope. Team
254's 2026 Championship talk directly inspired the agentic closed-loop design.

## License
TBD (intended to be permissive / open-source). Not yet finalized.
