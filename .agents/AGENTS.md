# FRC AI Copilot — Workspace Guidelines & Future AI Instructions

## Project Overview
This repository contains an open-source, team-agnostic AI copilot for FRC robot code built by Team 6369 (Mercenary Robotics) / 6773. It exposes 21 MCP tools, closed-loop sim/replay capabilities, Mode A competition diagnostics, and a local web dashboard.

## Dashboard Status
- **Phase 1 & Phase 2 Complete**: All 7 dashboard sections (Live, Pit, Match, Signals, Paths, Trends, Profile) are built, verified, and active.
- Served locally on `http://localhost:5800`. Run via `./gradlew :dashboard:run --args="--sim"`.

## 🚩 High Priority Research Flags (Check Before Next Feature Build)
Before starting new implementation, perform a deep dive into these 3 ecosystem tools to extract their best features for our internal copilot:
1. **Curatorfrc (`Curator FRC`)**: FRC documentation curation & search tool.
2. **Arcinator FRC (`Arcinator` by Team 6014)**: RAG AI assistant (game manuals 2014-2026, WPILib docs, TBA data, 150+ languages, "Turbo" engine).
3. **Frctools (`FRC Tools`)**: Community FRC software & analysis utility suite.

See [ROADMAP.md](../ROADMAP.md) for full horizon details:

1. **Horizon 1 — Agentic Sim/Replay (Module 6)**: Echo maple-sim game piece expansion, automated match regression test suite generator.
2. **Horizon 2 — Pit Automations (Mode A)**: USB log-watcher daemon for auto-ingest, automated PathPlanner waypoint tuner.
3. **Horizon 3 — Vision & Driver Input Analytics (Module 3)**: PhotonVision/Limelight ambiguity & latency primitives, driver throttle/steering efficiency primitives.
4. **Horizon 4 — Open-Source Distribution**: `copilot init` team scaffolding wizard, license finalization, Chief Delphi documentation.

