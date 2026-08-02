# FRC AI Copilot — Workspace Guidelines & Future AI Instructions

## Project Overview
This repository contains an open-source, team-agnostic AI copilot for FRC robot code built by Team 6369 (Mercenary Robotics) / 6773. It exposes 21 MCP tools, closed-loop sim/replay capabilities, Mode A competition diagnostics, and a local web dashboard.

## Dashboard Status
- **Phase 1 & Phase 2 Complete**: All 7 dashboard sections (Live, Pit, Match, Signals, Paths, Trends, Profile) are built, verified, and active.
- Served locally on `http://localhost:5800`. Run via `./gradlew :dashboard:run --args="--sim"`.

## Future Expansion Roadmap
When starting a new session or working on next features in this repository, check [ROADMAP.md](../ROADMAP.md) for full horizon details:

1. **Horizon 1 — Agentic Sim/Replay (Module 6)**: Echo maple-sim game piece expansion, automated match regression test suite generator.
2. **Horizon 2 — Pit Automations (Mode A)**: USB log-watcher daemon for auto-ingest, automated PathPlanner waypoint tuner.
3. **Horizon 3 — Vision & Driver Input Analytics (Module 3)**: PhotonVision/Limelight ambiguity & latency primitives, driver throttle/steering efficiency primitives.
4. **Horizon 4 — Open-Source Distribution**: `copilot init` team scaffolding wizard, license finalization, Chief Delphi documentation.
