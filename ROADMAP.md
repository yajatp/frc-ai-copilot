# FRC AI Copilot — Future Roadmap & Expansion Points

This document outlines the planned future expansion horizons for the **FRC AI Copilot** codebase. AI agents working in this repository in future sessions should refer to this document for direction on next features and architectural enhancements.

---

## ✅ COMPLETED: Ecosystem Competitor & Tool Research (2026-08-02)
*Deep-dive research completed across 8 FRC AI tools. Full report: [docs/competitive-intelligence.md](docs/competitive-intelligence.md)*

**Tools surveyed**: Arcinator (Team 6014), wpilog-mcp (Team 2363), Agentic-CSA, ChatFRC (Team 971), FRC-RAG-MCP, FTC-Claude (NCSSM), DragonScout (Team 6014), FRCTools.

**Key finding**: Our copilot is the most comprehensive tool in the ecosystem (no competitor combines live telemetry + 15 analysis primitives + PathPlanner editing + sim/replay + Mode A + dashboard + 21 MCP tools). The main gap is **documentation/knowledge access** — every competitor offers doc search and we have none.

---

## 🎯 Horizon 0 — Close Competitive Gaps (Highest Priority)
*Target: Cover the capabilities every competitor has that we're missing.*

- **Documentation RAG Search (`search_docs` MCP tool)**:
  - Index WPILib, CTRE Phoenix 6, REV, and PhotonVision docs for semantic search
  - This is the #1 AI use case for FRC teams and the #1 gap in our tool
- **Game Manual Rule Lookup (`search_manual` MCP tool)**:
  - Index the current season's game manual PDF for quick rule references during coding
- **Cycle Time / Scoring Efficiency Analysis (`analyze_cycles` primitive)**:
  - Measure intake→score cycle duration from .wpilog data (only wpilog-mcp has this)
- **Modular Skill Packs (`.agents/skills/`)**:
  - Create per-vendor skill files (CTRE, REV, PathPlanner, PhotonVision) with API patterns, pitfalls, and code templates
  - Inspired by FTC-Claude's marketplace model

---


## 🧭 Horizon 1 — Deepening Agentic Sim/Replay (Module 6)
*Target: 254-style closed-loop robot code iteration in headless simulation.*

- **Echo Swerve Simulation in the Loop**:
  - Extend the `example-robot` physics model (`maple-sim`) to include 3D game piece intake, hopper packing, and scoring physics.
  - Enable AI agents to run headless auto and teleop routines end-to-end, observe logged telemetry, diagnose mapping or PID flaws, edit code, and re-verify.
- **Automated Match Regression Test Suite Generator**:
  - Build a tool that automatically generates standing regression test cases from historical match `.wpilog` files.
  - Any proposed robot code change must pass the entire historical regression suite before deployment.

---

## ⚡ Horizon 2 — Pit Automations & Post-Match Workflow (Mode A)
*Target: Zero-friction telemetry analysis between competition matches.*

- **Automatic Post-Match Log Ingest Daemon**:
  - A background file-watcher daemon running on the pit laptop that detects newly attached USB drives or Driver Station log directory updates.
  - Automatically parses new `.wpilog` files, runs Mode A safety analysis, records metrics/events to the SQLite `TrendStore`, and notifies pit crew via the dashboard.
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

- **One-Command Setup Wizard (`copilot init`)**:
  - Scans a team's robot repository, extracts CAN IDs and PathPlanner settings, generates their `robot_profile.json`, and installs the MCP server into VS Code / Cursor automatically.
- **Open-Source License & Public Documentation**:
  - Finalize permissive licensing (MIT / Apache 2.0) and prepare Chief Delphi release documentation.
