# FRC AI Copilot — Future Roadmap & Expansion Points

This document outlines the planned future expansion horizons for the **FRC AI Copilot** codebase. AI agents working in this repository in future sessions should refer to this document for direction on next features and architectural enhancements.

---

## ✅ COMPLETED: Ecosystem Competitor & Tool Research (2026-08-02)
*Deep-dive research completed across 8 FRC AI tools. Full report: [docs/competitive-intelligence.md](docs/competitive-intelligence.md)*

**Tools surveyed**: Arcinator (Team 6014), wpilog-mcp (Team 2363), Agentic-CSA, ChatFRC (Team 971), FRC-RAG-MCP, FTC-Claude (NCSSM), DragonScout (Team 6014), FRCTools.

**Key finding**: Our copilot is the most comprehensive tool in the ecosystem (no competitor combines live telemetry + 15 analysis primitives + PathPlanner editing + sim/replay + Mode A + dashboard + 21 MCP tools). The main gap is **documentation/knowledge access** — every competitor offers doc search and we have none.

---

## ✅ COMPLETED: Horizon 0 — Close Competitive Gaps (2026-08-07)
*Every capability the competitors had and we lacked is now shipped.*

- ✅ **Documentation search (`search_docs`, `search_manual`, `knowledge_status`)** — new
  `:knowledge` module. Local SQLite **FTS5/bm25** index over WPILib, CTRE Phoenix 6,
  PhotonVision, and PathPlanner docs plus the game manual PDF (page-cited).
  `knowledge sync` shallow/sparse-clones and indexes all four corpora in one command
  (~3,600 chunks, ~6 MB, a couple of minutes).
  - *Design note:* lexical, not embedding-based — no model download, no network, works on a pit
    laptop with dead wifi. The questions teams ask are dominated by exact API names, where
    lexical search wins anyway.
  - *REV is excluded* — it publishes no public docs repo. `knowledge index <db> rev <folder>`
    picks up a local copy.
- ✅ **`analyze_cycles`** — the primitive already existed in `:analysis` but was unreachable from
  outside Java. Now exposed, along with six other orphaned primitives.
- ✅ **Modular skill packs** — `skills/frc-ctre-phoenix6`, `frc-rev`, `frc-pathplanner`,
  `frc-photonvision`. They teach durable patterns and pitfalls and delegate versioned API
  specifics to `search_docs`, since a skill that hardcodes signatures goes stale every season.
  - *Location note:* these live in `skills/` with the existing four, not a second
    `.agents/skills/` tree as originally sketched — one skills directory, not two.
- ✅ **MIT license** + full analysis CLI + a synthetic match-log generator for onboarding.

MCP server is now at **34 tools**.

---


## 🧭 Horizon 1 — Deepening Agentic Sim/Replay (Module 6)
*Target: 254-style closed-loop robot code iteration in headless simulation.*

### ✅ COMPLETE: the closed loop itself (2026-08-07)
*Full documentation: [docs/CLOSED-LOOP.md](docs/CLOSED-LOOP.md)*

The harness previously proved only observe→verify. The full **edit → build → run → verify →
diagnose → iterate** cycle now runs against real sim-in-the-loop as a single command
(`loop iterate` / the `loop_iterate` MCP tool):

- ✅ **`loop.yaml` project declaration** — a robot project states how to build, how to run, where the
  log lands, and what "working" means, so a turn takes no arguments. Discovered by walking up from
  the working directory, the way `git` finds its root.
- ✅ **Distinct outcomes** — `BUILD_FAILED` (with the compiler output), `RUN_FAILED` / `NO_LOG`,
  `CHECKS_FAILED`, `PASSED`. These call for different next actions, so they are not collapsed into
  one FAIL. A failing build short-circuits before the simulator runs.
- ✅ **Failure diagnosis** — failures are classified by shape (`SIGNAL_CONSTANT` = the mechanism
  never ran; `SHORTFALL` = it ran and fell short; `SIGNAL_ABSENT` = probably a renamed log key, with
  the closest present keys named; `NO_SAMPLES_IN_PHASE`), because each points at a different file.
- ✅ **Baseline signal divergence (`loop_diff`)** — ranks every signal by how far the run moved from a
  known-good log, normalized per signal so different units stay comparable. The largest divergence
  usually sits closest to the cause.
- ✅ **Iteration journal (`loop_history`)** — records which source files changed (content hashes, not
  timestamps) and how every checked value moved, on disk rather than in conversation context. "Did
  my last edit help" survives a session restart.
- ✅ **Regression suite generator (`loop_generate`)** — derives scenarios from a known-good `.wpilog`,
  proposing checks only for signals with a shape worth defending (counters, battery voltage, loop
  period, converging errors). Measures inside the same phase window the checks evaluate in, so a
  generated scenario always passes the log it came from.
- ✅ **`example-robot` wired as a worked example** — a real WPILib command-based robot on maple-sim
  physics, running headless through the actual command scheduler in ~1 s per turn.

MCP server is now at **38 tools**.

---

## ✅ COMPLETED: PRD Part 3 spec gaps closed (2026-08-12)
*An audit against the PRD found four gaps. All four are closed; the server is now at **44 tools**.*

- ✅ **Mode A's automatic trigger** — the log-watcher daemon, recorded under Horizon 2 below.
- ✅ **PathPlanner timing edits** — event-marker retiming and constraint zones are now editable, not
  just preserved on write (`pathplanner_move_marker`, `pathplanner_set_zone`). Positions are
  waypoint-relative rather than seconds, which the tool descriptions state outright.
- ✅ **The replay path is proven** — `{inputLog}` names the log a run replays, `--input-log` overrides
  it per turn. Demonstrated against a recorded match that scored 0: replayed through fixed code it
  reports 5 and passes, through the broken code it reproduces the 0 and fails.
  - *Honest limit:* replay is only valid for code downstream of the signals in the log. And the
    proof uses `example-robot`'s own replay source — an AdvantageKit `REPLAY` task fits the same
    contract, but no AdvantageKit project exists here, so that integration is untested, not verified.
- ✅ **`smallmodel` reachable** — its API took a raw numeric matrix, so exposing it needed the missing
  halves first: labeled-example extraction from a `.wpilog` and JSON model persistence
  (`smallmodel_train`, `smallmodel_score`).

### Remaining
- **Echo Swerve Simulation depth**:
  - Extend the `example-robot` physics model (`maple-sim`) to include 3D game piece intake, hopper packing, and scoring physics. The loop mechanics are done; this deepens what the simulation can express.
  - Teleop routines end-to-end (autonomous is covered today).
- **Regression suites from historical match logs**:
  - `loop_generate` derives scenarios from a single known-good run. The next step is deriving them across a season's real match logs, so the envelope reflects many runs rather than one.
  - Gate deployment on the full historical suite.

---

## ⚡ Horizon 2 — Pit Automations & Post-Match Workflow (Mode A)
*Target: Zero-friction telemetry analysis between competition matches.*

- ✅ **Automatic Post-Match Log Ingest Daemon (2026-08-12)** — shipped early, because the PRD
  specifies Mode A running automatically and a manual-only `mode_a` was a spec gap, not an extension.
  `modes watch <db> <dir>...` polls USB mount points and the Driver Station log directory, waits for
  a file's size to stabilize before ingesting (a wpilog mid-flush is valid-but-truncated), dedupes
  against the `TrendStore` so restarts do not re-analyze, and runs the Mode A pass on each new log.
  Results reach the pit crew via the daemon's console and via the `TrendStore` the dashboard already
  reads — one SQLite file is the whole integration. `mode_a_scan` is the request/response-shaped
  catch-up sweep for use from MCP; the daemon itself stays CLI-only because a tool call has to return.
- **Auto-Tuning Trajectory Optimizer**:
  - Integrate the `write-layer` (`PathFile` + `PathDiff`) with an automated tuning loop that compares planned PathPlanner paths against actual swerve pose logs.
  - Generates recommended `.path` waypoint adjustments for driver review between matches.

---

## 🎯 Horizon 3 — Analytical Primitives & Vision Diagnostics (Module 3)
*Target: Expanding signal analysis depth for advanced sensors and drivers.*

- **PhotonVision / Limelight Ambiguity & Latency Primitives**:
  - Build dedicated vision diagnostic tools in `:analysis` to detect multi-tag ambiguity spikes, frame latency jitter, and pose estimation jump severity against wheel odometry.
- **Driver Input & Throttle Efficiency Analysis**:
  - Parse Driver Station joystick input logs to measure driver response latency, scrubbed turns, and energy-inefficient rapid throttle oscillations.

---

## 📦 Horizon 4 — Open-Source Distribution & Team Onboarding
*Target: Making the copilot plug-and-play for any FRC team.*

- ✅ **Cross-platform build (2026-08-13)** — this was the real blocker for any external release, and
  it was not originally on this roadmap. The project built on exactly one machine: the Gradle wrapper
  and `org.gradle.java.home` both pinned absolute paths in one contributor's home directory, and every
  module hardcoded the macOS native classifier. Most FRC pit laptops run Windows. Now resolves natives
  from `os.name`/`os.arch`, and the wrapper uses the official Gradle distribution (SHA-256 pinned).
- ✅ **CI (2026-08-13)** — also not originally on this roadmap, and arguably more important than any
  feature here: builds and tests on Ubuntu / Windows / macOS, asserts platform-correct natives, and
  smoke-tests the MCP server over JSON-RPC. Without it, nothing stops the next contributor
  re-introducing exactly the single-platform breakage above.
- ✅ **Team on-ramp (2026-08-13)** — `profile init` already did the hard part (scan a robot repo for
  CAN IDs, drivetrain geometry, PathPlanner settings, vendordeps). What was missing was reachability:
  it is now the `profile_init` MCP tool and the "Start here" section of the README, rather than a
  footnote in `profiles/README.md`.
- ✅ **Permissive license** — MIT, already in place.
- **Remaining before a public release**:
  - Chief Delphi release documentation and announcement (writing, not capability).
  - Season-dating: profiles and bundled game data assume `REBUILT` / 2026, so a public release is
    coupled to the season rollover.
  - The editor installers are only exercised by hand; CI covers the server, not them.
