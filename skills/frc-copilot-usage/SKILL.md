---
name: frc-copilot-usage
description: How to use the frc-ai-copilot MCP server's 44 tools (get_guide, search_docs/search_manual/knowledge_status, profile_init/profile_show, log_info/log_entries/read_entry, ingest_log, power_analysis/can_health/battery_health/loop_timing/swerve_analysis/vision_analysis/anomaly, signal_stats/data_quality/find_peaks/rate_of_change/correlate/compare_signals/analyze_cycles, pathplanner_show/fudge/set_speed/move_marker/set_zone, auto_show/auto_swap_path, loop_check/loop_suite/loop_iterate/loop_history/loop_generate/loop_diff, mode_a/mode_a_scan, smallmodel_train/smallmodel_score, nt_status/nt_get/nt_keys) across Mode A (live/competition, between matches) and Mode B (deep post-event/off-season analysis). Load this whenever asked to analyze a match log, check battery/brownout/CAN health, propose autonomous path/speed tweaks, or verify a fix against a scenario for Team 6369 or 6773.
---

# Using the frc-ai-copilot MCP tools

The `frc-copilot` MCP server exposes 44 tools over stdio (see `docs/SETUP.md` for registration).
This skill is about *how* to sequence them, not what each one does internally — see the tool
descriptions themselves (`tools/list`) for exact schemas.

| Tool | Args | Purpose |
|---|---|---|
| `get_guide` | — | Read first. Workflow + epistemic guardrails. |
| `search_docs` | `query`, `source?`, `limit?`, `db?` | Search the offline docs index (wpilib/ctre/photonvision/pathplanner). Use before answering any API question. |
| `search_manual` | `query`, `limit?`, `db?` | Search the indexed game manual PDF; returns the passage **and its page number**. |
| `knowledge_status` | `db?` | What is indexed. Call this when a search returns nothing. |
| `profile_init` | `repo`, `out?`, `team?`, `robot?`, `game?` | **Start here for a new robot.** Scan a WPILib repo and generate its profile (CAN IDs, drivetrain, PathPlanner settings). Dry-run unless `out` given. Always surface its warnings — unverified CAN IDs are guesses. |
| `profile_show` | `profile` | Print a robot's YAML profile (CAN map, drivetrain, subsystems). |
| `log_info` | `file` | Version/entry-count/duration summary of a `.wpilog`. |
| `log_entries` | `file`, `filter?` | List signal names in a log, optionally substring-filtered. |
| `read_entry` | `file`, `entry`, `limit?` | Decoded samples for one exact signal name (default cap 200). |
| `ingest_log` | `db`, `file` | Persist a log's summary + entry index into a SQLite trend store. |
| `power_analysis` | `file` | Brownout/battery analysis — hedged, quality-scored. |
| `can_health` | `file` | CAN error-count trend — hedged. |
| `battery_health` | `file` | Battery droop / internal-resistance indicator, hedged end-of-match sag projection. |
| `loop_timing` | `file` | Loop overrun analysis (vs 20 ms) from a loop-period signal. |
| `swerve_analysis` | `file`, `entry?` | Detect underdamped/oscillating closed-loop behavior (hedged PID guidance). |
| `vision_analysis` | `file` | Vision detection rate / dropouts from a hasTarget or tag-count signal. |
| `anomaly` | `file`, `entry` | Robust (MAD) outlier detection on one signal. |
| `signal_stats` | `file`, `entry` | min/max/mean/median/stdDev/p95 with a data-quality block. |
| `data_quality` | `file`, `entry` | Is this signal sampled well enough to conclude anything? |
| `find_peaks` | `file`, `entry`, `minProminence?` | Local maxima — current spikes, impact events. |
| `rate_of_change` | `file`, `entry` | Derivative stats (units/s) and when the steepest change happened. |
| `correlate` | `file`, `entry`, `entryB`, `fileB?` | Pearson correlation, nearest-timestamp aligned. Suggests, never proves. |
| `compare_signals` | `file`, `entry`, `entryB?`, `fileB?` | Compare two signals — match-over-match, or left vs right. |
| `analyze_cycles` | `file`, `entry?` | Scoring throughput from a cycle counter (count, mean/median/fastest). |
| `pathplanner_show` | `path` | Summarize a `.path` file's waypoints/constraints. |
| `pathplanner_fudge` | `path`, `index`, `dx`, `dy`, `out?` | Propose shifting one waypoint by (dx, dy) meters. Dry-run unless `out` given. |
| `pathplanner_set_speed` | `path`, `maxVelocity`, `maxAcceleration`, `out?` | Propose new global speed/accel constraints. Dry-run unless `out` given. |
| `pathplanner_move_marker` | `path`, `index`, `delta?`/`pos?`, `endPos?`, `out?` | Retime an event marker (the *timing* tweak). Positions are waypoint-relative, not seconds. Dry-run unless `out` given. |
| `pathplanner_set_zone` | `path`, `index`, `minPos?`+`maxPos?`, `key?`+`value?`, `out?` | Move a constraint zone or change a constraint inside it — slow one tight passage without slowing the whole path. |
| `auto_show` | `auto` | Summarize a `.auto` file's path references. |
| `auto_swap_path` | `auto`, `oldName`, `newName`, `out?` | Propose swapping a path reference in a `.auto`. Dry-run unless `out` given. |
| `loop_check` | `log`, `scenario` | Verify one scenario's success criteria against a log — the closed loop's "verify" step. |
| `loop_suite` | `log`, `scenarioDir` | Run a whole regression suite (a directory of scenarios) against a log. |
| `loop_iterate` | `config?`, `scenario?` | Run ONE full turn of the closed loop: rebuild the robot code, run it headless, verify every scenario, diagnose failures. Use after editing robot code. Needs a `loop.yaml`. |
| `loop_history` | `config?` | The iteration journal: previous turns, which files changed, how each checked value moved. Read when resuming work. |
| `loop_generate` | `log`, `out?`, `name?`, `phaseSignal?`, `phaseEquals?` | Derive a regression scenario from a known-good run. Dry-run unless `out` given. |
| `loop_diff` | `baseline`, `log` | Rank how far a run diverged from a known-good baseline, signal by signal. |
| `mode_a` | `db`, `file` | Run the full Mode A between-match pass and persist metrics to the trend store (wraps `power_analysis`/`battery_health`/`can_health`/`loop_timing`). |
| `mode_a_scan` | `db`, `dirs` | Sweep directories (USB, DS logs) for logs not yet ingested and run Mode A on each. For continuous ingest use the daemon: `modes watch <db> <dir>...`. |
| `smallmodel_train` | `log`, `out`, `signals`, `positives`, `negatives?`, `stride?`, `threshold?` | Train a tiny classifier from a few moments you mark in a log. Timestamps in seconds. Metrics are training-set only. |
| `smallmodel_score` | `model`, `log`, `top?` | Run a saved small model over a log to see where it fires — how you validate it on a log it was not trained on. |
| `nt_status` | `host`, `port?` | Check whether a live robot's NetworkTables connection is up. Read-only. |
| `nt_get` | `host`, `key`, `port?` | Read one live NetworkTables value by key. Read-only. |
| `nt_keys` | `host`, `prefix?`, `port?` | List live NetworkTables keys, optionally by prefix. Read-only. |

`nt_status`/`nt_get`/`nt_keys` are the only tools that touch a *live* robot rather than a log
file — useful in the pit or on the practice field, not just post-hoc. There is no NT write tool;
the server never writes to a running robot. `loop_check`/`loop_suite`/`mode_a` wrap
`simreplay`/`modes` directly, so a whole Mode-A pass or a saved regression suite is one call
instead of manually chaining the individual analysis tools.

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
- **`pathplanner_show`** / **`auto_show`** across every path/auto in the deploy tree for
  off-season path cleanup — not just one-off fudges, but auditing rotation targets, constraint
  zones, and event-marker timing holistically now that there's time to think about it.
- **`swerve_analysis`**, **`vision_analysis`**, **`loop_timing`**, **`battery_health`**, and
  **`anomaly`** are the deeper diagnostic primitives that don't belong in a rushed between-match
  pass — use them here to chase down a specific hypothesis (e.g. "is module 3 oscillating?",
  "is our vision dropping out during a specific auto phase?").
- **`loop_check(log, scenario)`** / **`loop_suite(log, scenarioDir)`** are the closed-loop
  "verify" step (see `frc-sim-in-the-loop`): after proposing and testing a robot-code fix in
  sim/replay, check the resulting log against a written success criterion — or a whole banked
  regression suite — before considering the fix done. This is what makes "the agent edited the
  code" a checked claim instead of a guess.
- **`loop_iterate`** runs the *whole* turn when the project has a `loop.yaml`: rebuild, run
  headless, verify, diagnose. Prefer it over hand-chaining a build, a run, and `loop_check` —
  it reports a build failure, a missing log, and actual misbehaviour as distinct outcomes, and
  tells you which checked values moved since your last edit. Full workflow:
  [docs/CLOSED-LOOP.md](../../docs/CLOSED-LOOP.md).
  - Read the failure *kind*, not just FAIL. `SIGNAL_CONSTANT` means the mechanism never ran
    (look for an unscheduled command or a mis-routed action); `SHORTFALL` means it ran and fell
    short (gains or timing); `SIGNAL_ABSENT` usually means a renamed log key, not a robot fault.
  - When a turn passes, bank it: `loop_generate` on that log turns the fix into a standing check,
    so the same regression cannot come back silently.
- This skill covers every tool the server currently exposes (Modules 1–6, `modes`, `smallmodel`,
  and `live-nt` — 44 tools as of this writing). Tool count and shape will keep changing as the
  server grows; re-check `tools/list` (or `get_guide`) rather than assuming this table is
  exhaustive forever.

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
