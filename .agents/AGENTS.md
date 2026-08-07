# FRC AI Copilot — Workspace Guidelines & Future AI Instructions

## Project Overview
This repository contains an open-source, team-agnostic AI copilot for FRC robot code built by Team 6369 (Mercenary Robotics) / 6773. It exposes 21 MCP tools, closed-loop sim/replay capabilities, Mode A competition diagnostics, and a local web dashboard.

## Dashboard Status
- **Phase 1 & Phase 2 Complete**: All 7 dashboard sections (Live, Pit, Match, Signals, Paths, Trends, Profile) are built, verified, and active.
- Served locally on `http://localhost:5800`. Run via `./gradlew :dashboard:run --args="--sim"`.

## ✅ Competitor Research Complete (2026-08-02)
Deep-dive across **8 FRC AI tools** completed. Full report: [docs/competitive-intelligence.md](../docs/competitive-intelligence.md).
Key finding: we are the most comprehensive tool in the ecosystem. Main gap is **documentation/knowledge access** — every competitor has doc search and we don't.

See [ROADMAP.md](../ROADMAP.md) for full horizon details:

0. **Horizon 0 — Close Competitive Gaps**: `search_docs` MCP tool (doc RAG), `search_manual` (game manual), `analyze_cycles` (scoring efficiency), modular skill packs.
1. **Horizon 1 — Agentic Sim/Replay (Module 6)**: Echo maple-sim game piece expansion, automated match regression test suite generator.
2. **Horizon 2 — Pit Automations (Mode A)**: USB log-watcher daemon for auto-ingest, automated PathPlanner waypoint tuner.
3. **Horizon 3 — Vision & Driver Input Analytics (Module 3)**: PhotonVision/Limelight ambiguity & latency primitives, driver throttle/steering efficiency primitives.
4. **Horizon 4 — Open-Source Distribution**: `copilot init` team scaffolding wizard, license finalization, Chief Delphi documentation.

