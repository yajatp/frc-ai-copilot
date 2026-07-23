# FRC AI Copilot

An open-source, team-agnostic AI copilot for FRC robot code, built by Team 6369 (Mercenary
Robotics) / 6773 for use across both teams. It combines deep telemetry analysis, a live
competition mode, and — the north star — a **254-style agentic closed loop that observes logs,
edits robot code, runs it in sim/replay, verifies against success criteria, and iterates.**

It ships as an MCP server + Claude Code skills (Claude Code is the harness — we make it
FRC-fluent, we don't build a coding agent).

> Status: all core modules (1–6) + the MCP server are implemented and tested. The agentic
> closed loop's observe→verify path is proven on the 254 "auto scores 0 balls" case. See the plan
> for what deepens next (real Echo sim-in-the-loop, live-NT tooling in the server).

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
| 4 · `write-layer` | ✅ built + tested | PathPlanner `.path` reader/writer; propose waypoint/timing edits as reviewable diffs, dry-run by default |
| 5 · `live-nt` | ✅ built + tested | NT4 live telemetry (read) + a safety-scoped write boundary: default-deny whitelist, hard denylist that beats misconfig, doubles-only, no CLI write |
| 6 · `simreplay` | ✅ built + tested | The agentic closed loop's observe→verify core: assertion framework (phase-aware) + regression suite + sim/replay runner |
| — · `mcp-server` | ✅ built + tested | Self-contained JSON-RPC stdio server exposing 13 tools from Modules 1–4 + 6; `get_guide` discovery |

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
