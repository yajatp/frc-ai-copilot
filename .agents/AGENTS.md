# FRC AI Copilot — Workspace Guidelines & Future AI Instructions

## Project Overview
This repository contains an open-source, team-agnostic AI copilot for FRC robot code built by Team 6369 (Mercenary Robotics) / 6773. It exposes 44 MCP tools, offline documentation + game-manual search, closed-loop sim/replay capabilities, Mode A competition diagnostics, and a local web dashboard. Licensed MIT.

## Dashboard Status
- **Phase 1 & Phase 2 Complete**: All 7 dashboard sections (Live, Pit, Match, Signals, Paths, Trends, Profile) are built, verified, and active.
- Served locally on `http://localhost:5800`. Run via `./gradlew :dashboard:run --args="--sim"`.

## ✅ Capability review complete (2026-08-02)
A review of what this tool needs to be useful day to day. The one clear gap it found —
documentation and game-manual access — is now **closed** (see Horizon 0 below).

See [ROADMAP.md](../ROADMAP.md) for full horizon details:

0. ✅ **Horizon 0 — COMPLETE (2026-08-07)**: `:knowledge` module — `search_docs` / `search_manual` / `knowledge_status` over a local SQLite FTS5 index (WPILib, CTRE Phoenix 6, PhotonVision, PathPlanner + game manual PDF); `analyze_cycles` and six other orphaned primitives exposed; four vendor skill packs; MIT license.
1. ✅ **Horizon 1 — Closed loop COMPLETE (2026-08-07)**: full edit→build→run→verify→diagnose→iterate cycle (`loop_iterate`, `loop_history`, `loop_generate`, `loop_diff`). Remaining: maple-sim game-piece depth, and generating regression suites across a season of real match logs rather than one run.
2. **Horizon 2 — Pit Automations (Mode A)**: USB log-watcher daemon for auto-ingest, automated PathPlanner waypoint tuner.
3. **Horizon 3 — Vision & Driver Input Analytics (Module 3)**: PhotonVision/Limelight ambiguity & latency primitives, driver throttle/steering efficiency primitives.
4. **Horizon 4 — Open-Source Distribution**: `copilot init` team scaffolding wizard, license finalization, public release documentation.


## Knowledge Index (not checked in)
`search_docs` / `search_manual` read `.knowledge/frc.kdb`, which is **gitignored** — it is built,
not committed. On a fresh checkout:

```bash
./gradlew :knowledge:installDist
knowledge/build/install/knowledge/bin/knowledge sync .knowledge/frc.kdb .knowledge
knowledge/build/install/knowledge/bin/knowledge manual .knowledge/frc.kdb <game-manual.pdf>
```

If a knowledge tool reports "No knowledge index", that is the fix — not a bug.

## Closed-loop iteration state (not checked in)
`.loop/` (per-iteration logs, `session.json` journal, adopted baseline) is **gitignored** — it is
local state, not source. A fresh checkout starts with no history; that is expected, not a bug.

## Next Priority
The PRD's Part 3 spec is now met: the four gaps a 2026-08-12 audit found against it are closed —
Mode A's automatic trigger (`modes watch` log-watcher daemon), PathPlanner event-marker/constraint-zone
timing edits, a proven replay path (`{inputLog}`), and `smallmodel` on the MCP surface. The dashboard
was already complete; the doc that said otherwise was stale.

Next work is the roadmap extensions (Horizons 2-4) — see [ROADMAP.md](../ROADMAP.md). Note that
Horizon 2's log-watcher daemon shipped early, as part of closing the Mode A spec gap.

Two things worth knowing before extending the loop:
- `example-robot/loop.yaml` is the worked example. `simreplay iterate example-robot/loop.yaml`
  runs a real turn in about a second (Gradle daemon + headless maple-sim).
- The loop spawns builds, so it sets `GRADLE_USER_HOME` from `loop.yaml`'s `env:` block and passes
  `JAVA_HOME` down from its own JVM. Keep that — an MCP server started by an editor inherits no
  shell profile, and without it a spawned Gradle would fall back to `~/.gradle`.
