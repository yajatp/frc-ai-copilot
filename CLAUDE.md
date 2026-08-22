# FRC AI Copilot — working in this repo

An AI copilot for FRC robot code (Team 6369 "Echo" / 6773 "AlphaBot"). Java 17, multi-module
Gradle. It ships as an MCP server plus skills, so it runs inside the AI tool you already use.

**If the user asks to get started, set things up, or what this tool can do — use the
`frc-copilot-start` skill.** It detects whether this checkout is fresh or already built, does only
the missing work, opens the dashboard, and hands them a menu. Don't improvise setup steps.

## Environment — required for every build or CLI call

```bash
export JAVA_HOME=~/wpilib/2026/jdk
export GRADLE_USER_HOME="$PWD/.gradle-home"
```

Shell state does not persist between tool calls, so re-export both in **every** bash call. All
Gradle state stays inside the checkout — never let it fall back to `~/.gradle`. Build offline
(`--offline`); WPILib artifacts resolve from `~/wpilib/2026/maven`.

**This repo's path contains a space.** Quote every path you pass to a shell command.

## The thing that bites most often

Every CLI and the MCP server run from `*/build/install/*/bin/`, **not** from source. After editing
Java, re-run `./gradlew installDist --offline` or you are testing the old binary with no error to
tell you so.

## Layout

| Module | What it does |
|---|---|
| `core-ingest` | `.wpilog` parsing, struct decoding, SQLite trend store |
| `profile` | Robot profile bootstrapped from a robot repo |
| `analysis` | The 15 analysis primitives and their confidence guardrails |
| `write-layer` | PathPlanner `.path` / `.auto` edits as reviewable diffs |
| `live-nt` | NT4 telemetry read, plus the one scoped write path |
| `simreplay` | The closed loop: build → run → verify → diagnose → iterate |
| `modes` | Mode A orchestration and the log-watcher daemon |
| `knowledge` | Offline docs and game-manual index (SQLite FTS5) |
| `mcp-server` | The MCP server exposing all 46 tools |
| `dashboard` | Local web UI on :5800 |
| `example-robot` | A real command-based robot on simulated physics |

Domain skills live in `skills/` (also linked into `.claude/skills/`): `frc-reference` for the
team's AdvantageKit/CTRE/PathPlanner conventions — read it before writing robot code — plus
PathPlanner, Phoenix 6, PhotonVision, REV, scaffolding, and sim-in-the-loop.

## Two invariants that are not negotiable

**Nothing writes without showing the user first.** Every edit is a reviewable diff, dry-run by
default, written to a file the user names. The original is never overwritten in place.

**The live robot connection is read-only.** `NtWriteGuard` is the only write path: default-deny,
with a hard denylist on actuator and Driver Station / FMS tables, and doubles only. Nothing in this
codebase can command a motor or change the enable state. Don't add a path around it.

## Confidence language

Analysis results carry a sample count, a timing-regularity check and a confidence level, and the
wording is hedged to match. A single match is rarely conclusive. Don't strip that hedge when
summarizing — "only 2 samples, treat as weak" becoming a confident pit instruction is the failure
mode this guards against.

## Local state that is gitignored (absent on a fresh clone — not a bug)

`.knowledge/` (docs index), `.loop/` (closed-loop history and baselines), `reference/` (read-only
clones of the team robot repos), `*.wpilog`, `build/`, `.gradle-home*/`.

## Testing

`./gradlew build --offline` runs everything (200 tests). NT tests run a real loopback NT4
server/client and skip themselves via `assumeTrue` if the environment blocks loopback — a skip
there is not a failure.
