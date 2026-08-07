# FRC AI Copilot — Workspace Guidelines & Future AI Instructions

## Project Overview
This repository contains an open-source, team-agnostic AI copilot for FRC robot code built by Team 6369 (Mercenary Robotics) / 6773. It exposes 34 MCP tools, offline documentation + game-manual search, closed-loop sim/replay capabilities, Mode A competition diagnostics, and a local web dashboard. Licensed MIT.

## Dashboard Status
- **Phase 1 & Phase 2 Complete**: All 7 dashboard sections (Live, Pit, Match, Signals, Paths, Trends, Profile) are built, verified, and active.
- Served locally on `http://localhost:5800`. Run via `./gradlew :dashboard:run --args="--sim"`.

## ✅ Competitor Research Complete (2026-08-02)
Deep-dive across **8 FRC AI tools** completed. Full report: [docs/competitive-intelligence.md](../docs/competitive-intelligence.md).
Key finding: we are the most comprehensive tool in the ecosystem. The documentation-access gap it identified is now **closed** (see Horizon 0 below).

See [ROADMAP.md](../ROADMAP.md) for full horizon details:

0. ✅ **Horizon 0 — COMPLETE (2026-08-07)**: `:knowledge` module — `search_docs` / `search_manual` / `knowledge_status` over a local SQLite FTS5 index (WPILib, CTRE Phoenix 6, PhotonVision, PathPlanner + game manual PDF); `analyze_cycles` and six other orphaned primitives exposed; four vendor skill packs; MIT license.
1. **Horizon 1 — Agentic Sim/Replay (Module 6)**: Echo maple-sim game piece expansion, automated match regression test suite generator.
2. **Horizon 2 — Pit Automations (Mode A)**: USB log-watcher daemon for auto-ingest, automated PathPlanner waypoint tuner.
3. **Horizon 3 — Vision & Driver Input Analytics (Module 3)**: PhotonVision/Limelight ambiguity & latency primitives, driver throttle/steering efficiency primitives.
4. **Horizon 4 — Open-Source Distribution**: `copilot init` team scaffolding wizard, license finalization, Chief Delphi documentation.


## Knowledge Index (not checked in)
`search_docs` / `search_manual` read `.knowledge/frc.kdb`, which is **gitignored** — it is built,
not committed. On a fresh checkout:

```bash
./gradlew :knowledge:installDist
knowledge/build/install/knowledge/bin/knowledge sync .knowledge/frc.kdb .knowledge
knowledge/build/install/knowledge/bin/knowledge manual .knowledge/frc.kdb <game-manual.pdf>
```

If a knowledge tool reports "No knowledge index", that is the fix — not a bug.

## Next Priority
**The 254-style agentic closed loop (Horizon 1)** is the largest remaining piece and the
project's stated north star. `simreplay` proves the observe→verify path; what is missing is the
full edit→run→verify→iterate cycle against real Echo sim-in-the-loop.
