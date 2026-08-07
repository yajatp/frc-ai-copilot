# FRC AI Ecosystem — Competitive Intelligence Summary

> Generated 2026-08-02. For the full detailed report with feature matrices and per-tool breakdowns,
> see the conversation artifact. This file captures the actionable conclusions for future agents.

## Tools Surveyed (8 total)

| Tool | Builder | What It Does |
|------|---------|-------------|
| **Arcinator** | Team 6014 (ARC) | RAG chatbot — game manuals 2014–2026, WPILib docs, TBA data, 150+ languages |
| **wpilog-mcp** | Team 2363 (Triple Helix) | MCP server — 24 tools for .wpilog/.revlog analysis (cycles, MOI, vision, replay drift) |
| **Agentic-CSA** | FIRST community | MCP server — doc search across WPILib, REV, CTRE, Redux, PhotonVision |
| **ChatFRC** | Team 971 (Spartan) | GPT-4 Turbo RAG chatbot — software, mechanical, electrical, strategy |
| **FRC-RAG-MCP** | Community | MCP server — semantic doc search via LangChain + vector DB |
| **FTC-Claude** | NCSSM Robotics | Claude Code skill marketplace — modular per-library plugin skills |
| **DragonScout** | Team 6014 (ARC) | Mobile scouting app — offline-first, QR code data transfer |
| **FRCTools** | Community | Web tool — instant game manual keyword search |

## Competitive Gaps to Close

### Tier 1 — High Priority (every competitor has these, we don't)

1. **Documentation RAG Search (`search_docs` MCP tool)**
   - Index WPILib, CTRE Phoenix, REV, PhotonVision docs
   - Every competitor (Arcinator, Agentic-CSA, ChatFRC, FRC-RAG-MCP) offers doc search
   - This is the #1 AI use case for FRC teams

2. **Game Manual Rule Lookup (`search_manual` MCP tool)**
   - Index the current season's game manual PDF for quick rule references
   - Arcinator and FRCTools both offer this

3. **Cycle Time / Scoring Efficiency Analysis (`analyze_cycles` primitive)**
   - Measure intake→score cycle duration from .wpilog data
   - Only wpilog-mcp has this; critical for match strategy optimization

### Tier 2 — Medium Priority (strong differentiators)

4. **Vision analysis primitive** — PhotonVision/Limelight ambiguity + latency (already in Horizon 3 roadmap)
5. **MOI regression primitive** — calculate moment of inertia from swerve rotation telemetry
6. **DS timeline parsing** — extract mode transitions from .dslog/.dsevents files
7. **Modular skill packs** — `.agents/skills/ctre/`, `.agents/skills/rev/`, `.agents/skills/pathplanner/`
8. **Code template generation** — curated subsystem boilerplate (swerve, elevator, intake)

### Tier 3 — Low Priority / Already Covered

- Multi-language support — not needed (English-only team)
- Scouting — separate domain
- LLM guardrails — ✅ already core design principle
- Offline-first — ✅ already fully local
- VS Code extension — ✅ already built

## Our Unique Advantages (no competitor has these)

- Live NT4 telemetry dashboard with continuous health verdicts
- 15 composable analysis primitives with epistemic confidence levels
- PathPlanner .path/.auto editing as reviewable diffs
- Closed-loop sim/replay with phase-aware assertions
- Mode A between-match safety orchestrator
- Season-long SQLite trend store
- Small model training from labeled log examples
- Write safety boundary (default-deny, hard denylist)
- Robot profile auto-bootstrap from repo scanning
