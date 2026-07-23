---
name: frc-copilot-usage
description: How to use the frc-ai-copilot MCP server's tools (get_guide, profile_show, log_info/log_entries/read_entry, ingest_log, power_analysis, can_health, pathplanner_show/pathplanner_fudge/pathplanner_set_speed) across Mode A (live/competition, between matches) and Mode B (deep post-event/off-season analysis). Load this whenever asked to analyze a match log, check battery/brownout/CAN health, or propose autonomous path/speed tweaks for Team 6369 or 6773.
---

# Using the frc-ai-copilot MCP tools

The `frc-copilot` MCP server exposes 10 tools over stdio (see `docs/SETUP.md` for registration).
This skill is about *how* to sequence them, not what each one does internally — see the tool
descriptions themselves (`tools/list`) for exact schemas.

| Tool | Args | Purpose |
|---|---|---|
| `get_guide` | — | Read first. Workflow + epistemic guardrails. |
| `profile_show` | `profile` | Print a robot's YAML profile (CAN map, drivetrain, subsystems). |
| `log_info` | `file` | Version/entry-count/duration summary of a `.wpilog`. |
| `log_entries` | `file`, `filter?` | List signal names in a log, optionally substring-filtered. |
| `read_entry` | `file`, `entry`, `limit?` | Decoded samples for one exact signal name (default cap 200). |
| `ingest_log` | `db`, `file` | Persist a log's summary + entry index into a SQLite trend store. |
| `power_analysis` | `file` | Brownout/battery analysis — hedged, quality-scored. |
| `can_health` | `file` | CAN error-count trend — hedged. |
| `pathplanner_show` | `path` | Summarize a `.path` file's waypoints/constraints. |
| `pathplanner_fudge` | `path`, `index`, `dx`, `dy`, `out?` | Propose shifting one waypoint by (dx, dy) meters. Dry-run unless `out` given. |
| `pathplanner_set_speed` | `path`, `maxVelocity`, `maxAcceleration`, `out?` | Propose new global speed/accel constraints. Dry-run unless `out` given. |

## Always start here

1. **`get_guide`** — cheap, call it once per session/context before anything else. It restates
   the workflow below and the epistemic guardrails; the server's `initialize` response also
   embeds a one-line reminder ("Call `get_guide` first, then `profile_show` for the team's
   robot"), so there's no excuse to skip it.
2. **`profile_show <profile>`** on the right team — `profiles/6369-echo.yaml` (Echo, pure CTRE)
   or `profiles/6773-alphabot.yaml` (AlphaBot, CTRE+REV) — before interpreting *any* log. A
   `power_analysis` or `can_health` result means something different once you know the robot's
   real drive current limit, CAN device map, and subsystem list — don't give generic FRC advice
   when a specific profile is one call away.

## Mode A — live/competition (between matches)

Budget: a few minutes, between one match ending and the next needing the robot on the field.
Scope is deliberately narrow because **teams do not recompile robot code at events** — the only
things they can actually change are autonomous paths/speeds and pit-level hardware fixes
(battery, wiring, breaker). Mode A exists to surface exactly those two kinds of actionable
findings, fast, from a log that just came off the robot.

Sequence:
1. `get_guide` (if not already called this session) → `profile_show` for the competing robot.
2. `power_analysis(file)` — the 2026 "energy management" meta (per Team 254, REBUILT's hidden
   meta is battery/breaker management, not raw speed). Read the whole hedged assessment,
   including any brownout events (`start–end, min volts, duration`), and pass it on faithfully —
   don't compress "1 brownout-risk event, min 6.51 V, this may indicate battery/wiring/current-draw
   issues (3 samples — treat as a weak signal only)" down to just "brownout detected." The
   confidence caveat is part of the finding, not decoration.
3. `can_health(file)` — CAN error-count trend, same hedging discipline.
4. If either flags something autonomous-adjustable (e.g. the robot stalled and missed a
   waypoint, or the alliance partner needs a path nudged to avoid a collision): `pathplanner_show(path)`
   to see current waypoints/constraints, then `pathplanner_fudge` or `pathplanner_set_speed` to
   propose the change.
5. **Never treat a write tool's dry-run output as done.** `pathplanner_fudge`/`pathplanner_set_speed`
   without `out` only print a diff — the original `.path` is untouched. Show the diff to the
   human (drive team / programming lead) and let *them* decide the `out` path — usually writing
   back over the actual deploy path once they've confirmed it, since that's what the robot will
   load next match.

**Out of scope for Mode A:** deep swerve/PID audits, sim sweeps, season-trend queries, anything
that needs more than a couple minutes or touches Java source. That's Mode B — don't wander into
it under time pressure between matches.

Example turn (illustrative shape, actual numbers vary):
```
> power_analysis(file="match-quals-12.wpilog")
signal: /PowerDistribution/Voltage
2 brownout-risk events detected (voltage below 6.8 V; min 6.41 V). This may indicate
battery/wiring/current-draw issues. (140 samples, regular timing — reasonably well supported)
  - 41.20s–41.42s min 6.55 V (220 ms)
  - 88.90s–89.35s min 6.41 V (450 ms)
```
→ report this plainly (two brownout events, second one deeper/longer, well-supported by sample
count) and suggest the pit-actionable next step (check battery/connections) — don't editorialize
past what the data supports.

## Mode B — post-event / off-season deep analysis

No time pressure. This is where season-long and multi-log reasoning happens.

- **`ingest_log(db, file)`** for every log worth keeping — persists a summary + entry index into
  a SQLite trend store so later season-level questions don't require re-parsing raw `.wpilog`
  files. Use one `db` path consistently per team/season so trends accumulate.
- **`log_info` / `log_entries` / `read_entry`** to explore a log signal-by-signal:
  `log_entries(file, filter="CAN")` to find candidate signal names (substring match,
  case-insensitive), then `read_entry(file, entry="<exact name>")` — entry names must match
  exactly for `read_entry` even though `filter` is fuzzy.
- Cross-reference with `profile_show` when interpreting a specific subsystem's raw samples (e.g.
  is a current spike on `/Intake/...` within that subsystem's configured stator limit?).
- **`pathplanner_show`** across every path/auto in the deploy tree for off-season path cleanup —
  not just one-off fudges, but auditing rotation targets, constraint zones, and event-marker
  timing holistically now that there's time to think about it.
- This skill covers Modules 1–4 (ingestion, profile, analysis primitives, PathPlanner write
  layer) as currently exposed by the server. Deeper Mode-B capability — swerve/PID oscillation
  analysis, replay-drift, the sim/replay agentic fix loop, a persistent regression suite — is
  planned (Modules 5/6) and will show up as new tools; re-check `tools/list` (or `get_guide`) as
  the server evolves rather than assuming this table is exhaustive forever.

## Epistemic guardrails (non-negotiable)

- Every analysis tool returns a hedged assessment plus a confidence signal derived from actual
  sample count/timing regularity (LOW/MEDIUM/HIGH, roughly: <20 samples or gappy timing → LOW,
  ≥20 → MEDIUM, ≥100 with regular timing → HIGH). **Always surface this**, don't round a MEDIUM or
  LOW finding up to a confident claim.
- **One match is rarely conclusive.** Corroborate anything that matters across multiple matches
  (via `ingest_log` + comparing across logs) before calling it a systemic issue rather than a
  one-off.
- If a tool can't find the signal it needs (no voltage signal, no CAN error signal in this log),
  it says so plainly ("No battery-voltage signal found; cannot assess brownout risk.") — relay
  that directly. Never fabricate an assessment to fill the gap.

## Write/deploy boundary

The PathPlanner tools are the **only** write surface this server currently exposes, and they are
dry-run by default (no `out` → print-only diff; original file untouched). Nothing here edits
robot Java source, builds/deploys code, or writes to a live robot over NetworkTables. If a task
seems to call for any of that, it's outside this server's current scope — say so rather than
improvising a workaround.
