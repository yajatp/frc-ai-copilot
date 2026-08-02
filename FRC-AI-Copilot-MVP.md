# FRC AI Copilot — Full Project Brief & Build Instructions for Claude Code

**Audience:** This document is written to be read by Claude Code (Opus/Fable-tier) at the start of a fresh session. It contains full conversational context, the complete research trail behind every design decision, the detailed product spec, and explicit instructions for what to do *before* writing any code.

**Owner:** Yajat Parmar, rising senior, FRC Team 6369 (Mercenary Robotics). This is a personal/team project — not a commercial product. No monetization concerns. Primary goal: build something 6369 can actually use this season, then open-source it, with honest attribution to everyone whose ideas informed it.

---

## PART 1 — HOW WE GOT HERE (conversation context)

This project did not start as "build an FRC AI tool." Here is the actual path, in order, so you understand *why* the spec looks the way it does and don't second-guess decisions that were already deliberately made:

1. **Started by looking at Drift (godrift.ai)** — a general robotics-simulation AI agent (ROS2/Gazebo/MuJoCo/Isaac Sim). Understood what it does and doesn't do (sim scaffolding, not real-hardware deployment, not policy training yet).

2. **Realized Drift doesn't touch FRC/FTC at all**, and had the idea: "Claude Code, but for FRC/FTC robotics." Initial vision was vague — maybe a VS Code extension, maybe its own IDE.

3. **Did a full landscape survey of the non-AI FRC/FTC software ecosystem first**, on the reasoning that you can't design a tool for a domain you don't understand. This covered: WPILib (Java/C++/Python), the command-based framework, GradleRIO, vendor libraries (CTRE Phoenix/Tuner X, REV/Hardware Client, YAGSL), vision (PhotonVision, Limelight), path planning (PathPlanner, Choreo, deprecated PathWeaver), NetworkTables as the central data bus, AdvantageKit + AdvantageScope (logging/replay), dashboards (SmartDashboard/Shuffleboard deprecated, Elastic is the modern one, Glass is the programmer tool), Driver Station + FMS, WPILib's built-in simulator, SysId, and the FTC side (Blocks/OnBot Java/Android Studio, Road Runner, FTC Dashboard).

4. **Concluded NetworkTables is the real integration surface** (almost everything talks over it) and **AdvantageKit's replay logging is the single most valuable existing debugging primitive** for an AI agent to plug into.

5. **First product brainstorm** landed on a tiered structure:
   - Tier 1: live NetworkTables read/write + build/deploy wrapping + vendor config sanity checks
   - Tier 2 (the real differentiator): sim-verified iteration — write code, run headless WPILib sim, check against assertions, fix, repeat (Claude-Code-style loop, but for robot code)
   - Tier 3: pit debugging copilot grounded in AdvantageKit logs
   - Tier 4: team knowledge continuity across seasons (deferred, not a v1 priority)
   - Decided: MCP server + skill files plugged into an existing agent (Claude Code/Cursor/Copilot), NOT a forked IDE. WPILib already ships its own VS Code distribution — no need to replace it.

6. **Was told to check for existing competition before building anything.** This is where the project changed shape substantially. Found:
   - **ClaudeScope / TheFRCSuite** (github.com/rylero) — a Claude Code plugin (Go CLI + skills) doing log parsing (.wpilog) and live NetworkTables queries, with a strong demoed example (diagnosing underdamped swerve PID from voltage/velocity oscillation data). **No LICENSE file found in the repo** — GitHub's sidebar shows only "Readme," no license entry, meaning default all-rights-reserved copyright applies even though the code is publicly visible. Decision made: do NOT copy this code; treat it as inspiration/prior art only, and consider reaching out to the author before shipping anything similar.
   - **Team 254 (Cheesy Poofs)** presented "The Next Revolution: AI in FRC" at the 2026 FIRST Championship (April 29, 2026). This is the closest real prior art to the "Tier 2" sim-verification idea. Full details extracted from the actual recording — see Part 2, Source 6, for the complete transcript excerpts. Nothing about their system is open-sourced; it's a conference talk only, no repo, no code release.
   - **wpilog-mcp** (github.com/TripleHelixProgramming, built by FRC Team 2363 Triple Helix) — a mature, **MIT-licensed** MCP server with 45 tools doing log analysis, brownout/CAN diagnostics, swerve/PID oscillation detection, cycle-time analysis, TBA match-data integration, and REV log synchronization, with a genuinely good design principle (epistemic guardrails against LLM overconfidence baked into tool outputs). This is a much more direct and mature competitor than ClaudeScope for the "log analysis" layer.
   - **refinery-roborio-mcp** and **ros-mcp-server** — smaller/earlier MCP servers exposing live NetworkTables/robot state to AI agents. Confirmed the "live telemetry via MCP" wedge is validated, not novel.
   - **OpenAI has a Codex-for-FRC program**, offering seats to FRC teams directly, which confirms big-lab interest in this exact space.

7. **Explicitly decided NOT to fork any of the above.** For ClaudeScope: no license to fork anyway. For wpilog-mcp: MIT license means forking would be legally fine, but the deliberate decision was made to study it deeply and rebuild from scratch for architectural cohesion — see Part 1, Item 9 below for the reasoning. This is legally sound: ideas, workflows, and methods are not copyrightable, only literal code expression is. Building an independent implementation of a publicly-described approach is standard, legitimate practice in this space (the whole FRC ecosystem — PathPlanner, AdvantageKit, YAGSL, Choreo — already works this way, openly building on and crediting each other).

8. **Reframed the "pit debugging" feature after pushback from the project owner.** Original assumption was wrong: FRC teams do NOT recompile/redeploy robot code during competition. Code is frozen days before an event so drive teams can practice on the final build. The only thing that changes at competition is autonomous paths, via PathPlanner's GUI (no code, no recompile, no redeploy). This was confirmed by research (see Part 2, Sources 9-10). The feature was redesigned around this reality: an in-competition "live mode" that outputs **PathPlanner waypoint/timing adjustments**, not code fixes, plus flagging of any match-ending/DQ-risk issues (brownouts, CAN faults, comms drops, battery health) that a pit crew would want to know about immediately even though the fix is electrical/mechanical, not code.

9. **Settled on a two-mode architecture**, proposed by the project owner and refined together:
   - **Live/Competition mode**: fast, cheap, narrow. Runs automatically right after each match. Scope: PathPlanner waypoint/timing suggestions + critical safety/reliability flags only (brownouts, CAN faults, comms drops, battery health prediction). Deliberately excludes expensive sim/replay sweeps or deep swerve/PID audits — no time or budget for that between matches.
   - **Post-competition/off-season mode**: slow, thorough, resource-heavy. Full swerve/PID oscillation audits, replay-drift analysis, season-long trend tracking across all matches/events (via TBA), and the full 254-style sim/replay closed-loop code-improvement cycle. This is where actual code changes happen — reviewed carefully, verified in sim before ever going near the robot again.

10. **Final decision on build philosophy**: study wpilog-mcp and ClaudeScope deeply as architectural references, adopt their genuinely good decisions (WPILib's official `DataLogReader` for parsing — don't reinvent this; primitive/composable tool design; epistemic guardrails in tool output), but rebuild the whole system from scratch as one cohesive architecture rather than forking and patching, because several needed capabilities (team-specific robot profiles, persistent structured storage for trend queries, a safety-scoped write layer, live NT, sim/replay, PathPlanner output, the two-mode split) don't exist in either reference project and would be bolted-on afterthoughts rather than first-class design if built on top of an existing, differently-scoped codebase.

**This is the point this document picks up from. Everything below is the actual spec.**

---

## PART 2 — COMPLETE SOURCE LIST

Claude Code should treat this as the starting bibliography, but is explicitly instructed (see Part 4) to re-verify, re-search, and go deeper on all of these — this list reflects research done by a prior, less-capable model pass (Claude Sonnet) and may be incomplete, stale, or subtly wrong in places.

### Source 1 — Drift (general robotics sim agent, initial inspiration only, not FRC-specific)
- https://www.godrift.ai/
- Company: Ramankind Inc, backed by Antler.
- Relevance: showed the "Claude Code for domain X" pattern is fundable/buildable; not itself FRC-relevant since it's ROS2/Gazebo/MuJoCo/Isaac Sim focused, sim-only, no real hardware deployment.

### Source 2 — Minor/early FRC tools surveyed (low relevance, listed for completeness)
- "FTC for VS Code" — community extension, snippets/debugger only, not AI.
- "FRC Jumpstart" — VS Code template generator, not AI.
- "FRC StratBot" — wrapped GPT chatbot, not agentic, low effort.
- An FTC-specific Claude Code skill template referenced on mcpmarket.com (exact URL not captured — Claude Code should re-search "FTC Claude Code skill mcpmarket" if this needs verifying).

### Source 3 — ClaudeScope / TheFRCSuite (rylero)
- Main repo: https://github.com/rylero/ClaudeScope
- Install command referenced in README: `/plugin marketplace add rylero/TheFRCSuite`
- Chief Delphi announcement thread: https://www.chiefdelphi.com/t/claudescope-a-tool-for-debugging-and-data-analysis/519547 (posted ~April 28, 2026; could not be fetched directly due to bot-detection blocking — only the search snippet was available: "ClaudeScope is a tool that allows Claude to process and understand wpilib log files and live network tables data, allowing it to be debugged in real time.")
- **License status: NO LICENSE FILE FOUND.** GitHub repo sidebar shows only "Readme" under Resources — no License entry. Per GitHub's own documentation, absence of a license means default copyright applies (all rights reserved; no one may reproduce, distribute, or create derivative works without permission). **Do not copy code from this repo.** Treat only as architectural inspiration.
- Author (rylero) also maintains a separate project, DeepstreamYOLO (github.com/rylero/DeepstreamYOLO) — a Jetson Orin Nano + DeepStream + YOLO vision pipeline that streams results to a robot over NetworkTables. Confirms rylero is an active, serious FRC-adjacent developer; a direct outreach/collaboration conversation before shipping anything similar would be in keeping with FRC's "gracious professionalism" norms.
- Confirmed capabilities from README example: parses `.wpilog` files and live NetworkTables; two Claude Code skills, `/scope` (log + live NT query) and `/wpilib-reference` (static WPILib pattern knowledge); built on top of Jesse Vincent's "Superpowers" Claude Code skill framework (referenced via a `docs/superpowers` folder in the repo) rather than a fully custom harness.
- Demoed example: correctly diagnosed underdamped swerve module PID (oscillating voltage/velocity data) and recommended a `kD` increase, with correct follow-up reasoning about `kP` vs. velocity saturation math.
- Confirmed open TODO items in the repo at time of research: "testing reference skill" and "AdvantageKit reference skill" were both unstarted.
- Project scale at time of research: 2 GitHub stars, single contributor, v1.0.0 released ~April 17, 2026.
- **Gaps identified relative to this project's goals:** single-log analysis only (no multi-log/season trend analysis); no AdvantageKit-aware deterministic replay (their own TODO confirms this is unbuilt); no CAN/vendor-fault correlation; unclear/undocumented whether live NetworkTables access is read-only or can write (a safety concern if unresolved); no simulation integration at all; no testing/eval harness for the skills themselves.

### Source 4 — Team 254 (Cheesy Poofs) "The Next Revolution: AI in FRC" — 2026 FIRST Championship Conference Presentation
- Chief Delphi announcement thread (5 pages of community discussion): https://www.chiefdelphi.com/t/2026-championship-conference-presentation-the-next-revolution-ai-in-frc-by-254/519529
  - Page 2: https://www.chiefdelphi.com/t/2026-championship-conference-presentation-the-next-revolution-ai-in-frc-by-254/519529?page=2
  - Page 3: https://www.chiefdelphi.com/t/2026-championship-conference-presentation-the-next-revolution-ai-in-frc-by-254/519529?page=3
  - Page 4: https://www.chiefdelphi.com/t/2026-championship-conference-presentation-the-next-revolution-ai-in-frc-by-254/519529?page=4
  - Page 5: https://www.chiefdelphi.com/t/2026-championship-conference-presentation-the-next-revolution-ai-in-frc-by-254/519529?page=5
  - Specific post (#44, community debate on AI-use pedagogy, quoted in full below): https://www.chiefdelphi.com/t/2026-championship-conference-presentation-the-next-revolution-ai-in-frc-by-254/519529/44
- **YouTube recording (primary source — full transcript extracted via Cloudglue video-analysis tool and reproduced in Source 6 below):** https://www.youtube.com/watch?v=oTcimMwxRoM
  - Channel: Team 254: The Cheesy Poofs
  - Published: May 4, 2026. Duration: 48:32. Presenters: Jared Russell, Tom Bottiglieri, Solomon Cheng (referred to in transcript as "Solomon Jang" at one point — likely a transcription artifact; his own self-introduction later in the talk says "Solomon Cheng").
  - Note: as of research time, this video had only 815 views and no separate slide deck was found published anywhere.
- **Community discussion finding (post #44 by CJ_Elliott, quoted for context on the "should teams use AI" debate):** a mentor of a 2-person programming team (1 student, 1 low-FRC-experience mentor) described using AI to survive a mid-season swap to new swerve modules (MK5n) plus adding vision, with almost no lead time, arguing AI literally saved their season's programming — while acknowging the standard counter-concern (over-reliance without understanding fundamentals) is real and worth balancing, not dismissing.

### Source 5 — wpilog-mcp (TripleHelixProgramming / FRC Team 2363 Triple Helix)
- Main repo: https://github.com/TripleHelixProgramming/wpilog-mcp
- **License: MIT** (confirmed directly in repo — LICENSE file present, no ambiguity).
- LobeHub MCP marketplace listing (useful secondary description, captured an older 34-tool version snapshot from March 14, 2026 — current main branch has grown to 45 tools, confirming active development velocity): https://lobehub.com/mcp/triplehelixprogramming-wpilog-mcp
- Architecture facts confirmed:
  - Written in Java (98.3% of repo), built with Gradle, targets JDK 17 binary compatibility, recommends using the WPILib-bundled JDK (auto-detected).
  - Uses WPILib's **official `DataLogReader`** class for parsing `.wpilog` files — guarantees format compatibility rather than reverse-engineering the format.
  - Distributed two ways: a VS Code extension ("WPILog Analyzer" — handles Java detection, server startup, MCP registration automatically) and a standalone install (`./gradlew install`, configured via `servers.yaml`, works with Claude Desktop/Claude Code CLI/Gemini/any MCP client).
  - Also correlates `.revlog` files (REV hardware motor-controller telemetry) with `.wpilog` data via a two-phase timestamp sync: coarse alignment via `systemTime` entries/filename timestamps, then fine alignment via Pearson cross-correlation of matching signals (e.g., duty cycle), reporting a confidence level (HIGH/MEDIUM/LOW/FAILED); handles linear clock drift correction for recordings longer than 15 minutes.
  - Integrates with The Blue Alliance API (free key) to enrich logs with match scores, win/loss, alliance color, and to correct midnight-rollover timestamps from FMS.
  - Bundles static game-info data for 2024 Crescendo, 2025 Reefscape, and 2026 REBUILT (this year's game) — scoring zones, match timing, field geometry.
  - Explicit design philosophy, stated in their own README: "primitive tool design" — small composable tools (`get_statistics`, `find_peaks`, `power_analysis`, etc.) rather than one big opaque "diagnose_robot" tool, specifically so the LLM's reasoning stays transparent/auditable across multiple tool calls.
  - Explicit **epistemic-guardrails design**, stated directly in their README: every tool response includes a data-quality/confidence assessment based on sample count, data gaps, and timing regularity; tool descriptions and response metadata deliberately use hedged language ("suggests," "may indicate") instead of "proves"/"confirms"; the model is explicitly reminded a single match is never enough to draw a definitive conclusion.
  - `get_server_guide` is meant to be called first by the agent, before any analysis tool, specifically to prevent the LLM from writing custom one-off analysis code when a built-in tool already covers the need.
  - Full tool list (45 tools) by category, exactly as documented in their README:
    - **Discovery:** `get_server_guide`, `suggest_tools`
    - **Core:** `list_available_logs`, `list_loaded_logs`, `list_entries`, `read_entry`, `get_entry_info`, `list_struct_types`, `health_check`
    - **Query:** `search_entries`, `get_types`, `find_condition`, `search_strings`
    - **Statistics:** `get_statistics`, `compare_entries`, `detect_anomalies`, `find_peaks`, `rate_of_change`, `time_correlate`
    - **Robot Analysis:** `get_match_phases`, `analyze_swerve`, `power_analysis`, `can_health`, `compare_matches`, `get_code_metadata`, `moi_regression`
    - **FRC Domain:** `get_ds_timeline`, `analyze_vision`, `profile_mechanism`, `analyze_auto`, `analyze_cycles`, `analyze_replay_drift`, `analyze_loop_timing`, `predict_battery_health`, `get_game_info`, `analyze_can_bus`
    - **TBA:** `get_tba_status`, `get_tba_match_data`
    - **RevLog:** `list_revlog_signals`, `get_revlog_data`, `sync_status`, `set_revlog_offset`, `wait_for_sync`
    - **Export:** `export_csv`, `generate_report`
  - Decoded WPILib type support confirmed: primitives (boolean/int64/float/double/string/raw/json + arrays), geometry types (Pose2d/3d, Translation2d/3d, Rotation2d/3d, Transform2d/3d, Twist2d/3d), kinematics types (ChassisSpeeds, SwerveModuleState, SwerveModulePosition), and vision/autonomous types (TargetObservation, PoseObservation with MEGATAG_1/MEGATAG_2/PHOTONVISION enum decoding, SwerveSample).
  - Documented worked examples exist in the repo's `doc/` folder — referenced but **not yet fetched/read in full** by this research pass: `doc/VACHE_POWER_ANALYSIS.md` (described as "a stellar demonstration of the system's strict insistence against over-interpretation") and `doc/VAALE_EVENT_ANALYSIS.md` (comprehensive event analysis). **Claude Code should fetch and read both of these in full during the deep-research phase (Part 4) — they were only referenced by title/description in this research pass, not read.**
  - Also referenced but not yet read: `doc/TOOLS.md` (full tool reference with parameters), `doc/DEVELOPMENT.md` (build/architecture/contributing docs), `doc/STANDALONE.md` (install/config/Docker). **Claude Code should fetch all of these.**
  - Acknowledgments section of their README explicitly credits WPILib, AdvantageKit ("for pioneering FRC replay logging"), Anthropic (for MCP and Claude), and FRC Team 2363 itself.
- **Gaps identified relative to this project's goals:** no live NetworkTables (log-file/post-match only); no sim/replay closed-loop code iteration; no team-specific robot-profile awareness (game data is generic/bundled, not team-mechanism-specific); no PathPlanner-output/write capability of any kind (fully read-only by design); no explicit lightweight/heavyweight mode split; no persistent structured storage across logs (each query appears to re-parse/re-analyze from raw log data — needs confirmation during deep research, see Part 4).

### Source 6 — Full transcript excerpts from the 254 YouTube presentation (extracted via Cloudglue video-analysis tool, scene-by-scene with timestamps; this is the actual primary-source content, not a summary)

> **[00:00–02:40]** Introduction. Speakers identify themselves as Jared Russell, Tom Bottiglieri, and Solomon (Cheng). Framing: "what AI is doing right now is the most transformative change that we've seen in our careers... FRC is at its best when it is a mirror of real-world engineering." Explicit disclaimers: this is a snapshot as of April 2026 ("this field is changing really really fast... just because something doesn't work today doesn't mean it won't work tomorrow"), not a deep dive on how AI works, not a complete tutorial, not a discussion of AI ethics/literacy/socioeconomic impact ("you should talk to your students, talk to your mentors about these things"), and explicitly framed as one point of view from self-described "techno-optimists" from "the Bay Area."

> **[15:00–19:20]** Spectrum-of-development framing: "surgical" edits (small, precise, e.g. ChatGPT copy-paste) vs. AI IDEs (Cursor/Antigravity, code-first view with a helper) vs. "vibe coding" (Claude Code/Codex, agent-first, conversational). Strong emphasis on **plan mode**: "if you're trying to do anything serious, like turn on plan mode immediately... you should be spending significantly more time iterating and reviewing this plan than you do building the code or testing the code. Like this is the most important part." Plan should "say exactly what it's going to do and it needs to do it in the minimum amount of steps possible."

> **[19:40–23:30]** **The core closed-loop cycle (this is the key section — read carefully):**
> "The next thing... this is really the thing that accelerates you is this idea of closed-loop agent building... set up your AI in a cycle to make a change, run it, figure out what it did, and then fix itself over and over and over and over again. In this circle we start here. The first thing you need to do is observe the behavior. So in the FRC [context] you should be logging everything. You need to log as fast as you can every metadata from every motor — what's the voltage that the motor's applying, what's the current that it's drawing, what command state am I in — just anything you can think of needs to get logged... you probably need to have some tool that allows you to pull that information out [and] put it into a form that the agent can understand. The agent will then take that information, look at it, and say 'oh yeah this is wrong, I know how to fix that,' and then it will make the change and then it will [re-run to] understand that it made a change... and then at the end it'll compare, and it just keeps doing this until it gets the right answer."
>
> Four specific **skill files** described (plain text files in a `skills/` directory, same pattern as Claude Code skills):
> 1. **Simulation agent skill** — "we have made a way to run a simulation of our robot both teleop and auto that... requires no user intervention. Here is what we want the gamepad inputs to be. Here's what the autonomous [routines] should be. And the agent can just run the whole teleop and get a log and see what happens. And just run it in a loop."
> 2. **Robot/game info skill** — static knowledge of the robot's degrees of freedom, mechanisms, and capabilities (e.g., "we can shoot the balls out this way and that way").
> 3. **Replay testing skill** — "sometimes you don't want to do a full resimulation. You just want to say if I had this one variant in that log and I were to have had a code change happen, what would that code do? Replay is really great for that... AdvantageKit stuff has this built in. This is great because then you can iterate on a very specific piece of code, run it over the log through it and see exactly what would change. And so long as you set up the success criteria, you should be [good]."
> 4. **Log reading skill** — "how do you get those WPI logs into a format that the LLM understands. We have some tool that finds it and puts it in, turns it from a WPI log into a CSV" (explicitly described as basic/simple, to be released publicly "at the end of the year").

> **[23:40–28:30]** **Case Study 1 — "Tip Detect":** Goal: detect whether the robot tips going over the bump or over a ball, with zero false positives but high true-positive catch rate. Instead of manually reviewing 10-20 logs, they prompted an agent. The agent already knew the autonomous period structure and where the bump is on the field from the "game info" skill context (not re-prompted). The agent ran a **replay sweep across every single log from their District Championship simultaneously**, tuning the tip-detection threshold per log. Result: the agent correctly identified two known true positives, proposed a specific threshold (they'd suggested 50ms as a starting point; the agent evaluated it, found it acceptable, but also flagged a match they'd forgotten about — "tipping Q96" — as a likely additional true positive the mentors had genuinely forgotten. Direct quote: "I actually forgot that we actually also had a tipping Q96. And then I looked at this and said, oh yeah, the agent's actually right." Output included a table of every match, peak/max angle logged, and the flagged qualification match.

> **[26:20–28:34]** **Case Study 2 — Broken Autonomous ("first flight" auto):** Bug: an autonomous routine that collects balls, drives over the bump, and is supposed to shoot, but doesn't. Root cause turned out to be a typo — "the shoot action was incorrectly triggering... a passing sequence." Prompt to the agent included: (a) understanding "scoring zero balls" as an implicit failure condition without needing it spelled out, (b) instructions to verify any fix by running multiple different autos, (c) a check that every auto run scores non-zero and exceeds a target. The agent (they mention "Gemini" specifically ran this one, notable — they use multiple model providers, not just Claude) ran the sim end-to-end, found zero balls scored, diagnosed the mapping typo, fixed it, and **re-ran two different autonomous routines on its own initiative** (not told which ones to pick) to verify the fix generalized, reporting back "both tests exceeded the target."

> **[28:40–32:20]** **Sim engineering itself, vibe-coded:** The bump-traversal 3D physics simulation was built by extending an existing off-the-shelf 2D physics simulator called **MapleSim** into 3D — NOT built from scratch. Process: gave the agent a GitHub link to MapleSim in **plan mode**, went back and forth "for maybe half an hour," asked for three possible approaches with pros/cons of each, picked the simplest-sounding one. Explicit reasoning for why they were comfortable vibe-coding this: "this is sort of the simulator. It's not running on the robot. It's going to be pretty obvious it's not working." They also vibe-coded: a ball/"fuel" physics simulator (models game pieces interacting with robots on the field, used to detect if an expanding-hopper mechanism would grow too tall to fit under the field's trench obstacle) and a robot-collision-recovery path replanner (detects "lost" state from sim + live telemetry, plans a new time-parameterized recovery path when the robot collides with another robot during auto) — both tested in sim first, field-tested only after simulation success.

> **[32:20–34:38]** **"Using big AI to make a small AI" (distinct technique, worth understanding but NOT part of the core sim/replay loop):** Problem: wanted to detect the optimal moment to "stage" balls in a hopper/shooter mechanism (enough balls collected to pack the shoot tube well, but not so late the driver bails out) — no room/time to add a physical sensor before the next event. Solution: captured practice video + synced logs via AdvantageScope, manually marked ~30 examples of "this would be a good time to press the button," then asked the agent to train a tiny specialized model on that labeled data to predict the right moment. The agent suggested the approach, trained the model, tested it in replay ("I tried it in replay, it had this success rate"), iterated ("in a loop, like data better, better, better"), then reported it was ready. Deployed to the real robot; "worked perfectly the first time, we literally never thought about it again." This is a genuinely distinct capability from the sim/replay code-verification loop — it's actual small-model training, informed/orchestrated by a larger LLM agent, not just code generation.

> **[34:40 onward]** Transitions into non-robot-code uses of AI (code review workflows, etc.) — **this research pass stopped extraction at this point (~35 minutes into a 48:32 video). Claude Code should watch/extract the remainder (35:00–48:32) during the deep-research phase — it has not been reviewed and may contain additional relevant content.**

### Source 7 — Community/practice research on what teams actually change at competition
- Chief Delphi, "Maturity: How did your team handle problems that arose during matches?" — https://www.chiefdelphi.com/t/maturity-how-did-your-team-handle-problems-that-arose-during-matches/508983 (key quote, directly informing the live/post-event mode split): a team's pit analyst uses AdvantageScope between matches to check drivetrain accuracy and vision detections against known problem spots; "control engineering... will take input from both the log analysis and video review to make (judicious) changes between matches. **The most common between-match adjustment in the past year has been fudging waypoints**" (i.e., PathPlanner-style GUI tweaks, not code).
- Chief Delphi, "How can we improve the accuracy of our autonomous?" — https://www.chiefdelphi.com/t/how-can-we-improve-the-accuracy-of-our-autonomous/442919 (general PathPlanner/swerve tuning discussion, confirms PathPlanner as the standard tool for this).
- Chief Delphi, "Is it worth it to make autonomous easy and fast to change? What are the software solutions around it?" (FTC-side, but directly relevant) — https://www.chiefdelphi.com/t/is-it-worth-it-to-make-autonomous-easy-and-fast-to-change-what-are-the-software-solutions-around-it/521459 (confirms roughly half of surveyed FTC teams at a World Championship see fast-autonomous-adaptation as an active unsolved problem).
- Chief Delphi, "2 Autonomous Codes for Competition" (older thread, 2016, general background on switching between pre-built autonomous options) — https://www.chiefdelphi.com/t/2-autonomous-codes-for-competition/149505

### Source 8 — Brownout/CAN/power diagnostics reference material (informs what "critical, flag immediately" means for live mode)
- WPILib official docs, roboRIO brownout behavior: https://docs.wpilib.org/en/stable/docs/software/roborio-info/roborio-brownouts.html
- frc-docs GitHub source for the same: https://github.com/wpilibsuite/frc-docs/blob/main/source/docs/software/roborio-info/roborio-brownouts.rst
- WPILib Driver Station docs (12V faults, CAN bus utilization/faults, battery voltage indicators): https://docs.wpilib.org/en/stable/docs/software/driverstation/driver-station.html
- WPILib Driver Station Log Viewer docs: https://docs.wpilib.org/en/stable/docs/software/driverstation/driver-station-log-viewer.html
- FirstMnCsa community write-up on reading the DS log viewer (brownout markers, watchdog markers, PDP voltage graph interpretation): https://firstmncsa.org/2019/02/15/the-driver-station-log-file-viewer/
- Chief Delphi, older thread on a real brownout debugging case: https://www.chiefdelphi.com/t/brownout-protection-error-on-driverstation/167013
- 135 Consul FRC programming framework write-up (confirms sim can model battery draw/brownouts, and documents a real team's live telemetry monitoring setup combining AdvantageScope + Elastic + Driver Station simultaneously): https://oa.pennrobotics.org/135-consul-frc-programming-framework/

### Source 9 — General WPILib/FRC ecosystem research (broad landscape survey, many individual searches, key facts consolidated)
- docs.wpilib.org (general WPILib documentation, command-based framework, simulation GUI, SysId)
- github.com/wpilibsuite/allwpilib and frc-docs (official WPILib source/docs repos)
- Wikipedia: FIRST Robotics Competition — https://en.wikipedia.org/wiki/FIRST_Robotics_Competition (2026 season stats: 3,724 teams, 30 countries)
- Wikipedia: Rebuilt (FIRST) — https://en.wikipedia.org/wiki/Rebuilt_(FIRST) (2026 game, archaeology/"reimagining the past" theme, championship at George R. Brown Convention Center, Houston, April 29–May 2 2026)
- General research covered (no single canonical URL per item, consolidated from multiple searches): PathPlanner, Choreo, deprecated PathWeaver; CTRE Phoenix 5/6 + Phoenix Tuner X; REV Hardware Client; YAGSL; PhotonVision; Limelight; AdvantageKit + AdvantageScope (Team 6328); Elastic (Team 353) vs. deprecated Shuffleboard/SmartDashboard vs. Glass; NetworkTables as central pub-sub bus; FRC Driver Station (NI/LabVIEW) + FMS.

### Source 10 — FTC ecosystem research (secondary priority — project has since decided to go FRC-only, but this informed the earlier comparison)
- General research on: Blocks (Blockly visual editor), OnBot Java (browser-based IDE), Android Studio (advanced path); Road Runner + FTC Dashboard (acmerobotics, community-standard path-following, FTC's equivalent of PathPlanner).

### Source 11 — Real FRC team CLAUDE.md files found during research (useful as reference for how teams already structure AI-agent context)
- FRC Team 360's actual CLAUDE.md for their 2026 robot codebase ("RainMaker26") — https://github.com/FRCTeam360/RainMaker26/blob/main/CLAUDE.md — documents real conventions: IO-layer-per-hardware-implementation pattern (following Team 6328/AdvantageKit's architecture), Noop IO implementations for absent hardware, PR-review scoping rules (only review diff lines, don't nitpick style, focus on runtime-breaking changes).
- rylero's DeepstreamYOLO CLAUDE.md — https://github.com/rylero/DeepstreamYOLO/blob/main/CLAUDE.md — documents a Jetson Orin Nano + DeepStream + YOLO + NetworkTables vision pipeline, useful reference for how a non-roboRIO coprocessor project structures its own agent-facing docs.

### Source 13 — Additional Ecosystem Competitors Flagged for Deep-Dive Research (Prior Art)
- **Curatorfrc (`Curator FRC`)**: Curation, search, and knowledge management tool for FRC documentation and team assets.
- **Arcinator FRC (`Arcinator` by FRC Team 6014 ARC)**: OpenAI/RAG-powered FRC chatbot assistant trained on 2014–2026 game manuals, WPILib docs, and TBA data with 150+ language translation and "Turbo" engine.
- **Frctools (`FRC Tools`)**: FRC software utility and telemetry/code analysis tools.
- **Instruction**: Prior to further implementation, AI agents should deep dive into these 3 tools, analyze their features and prompt/RAG patterns, and incorporate their strongest elements into this team's internal copilot.

---


## PART 3 — THE ACTUAL PRODUCT SPEC

### 3.1 — Vision statement

Build an open-source, FRC-team-agnostic AI copilot for Team 6369 (and, eventually, any FRC team) that combines:
- Deep, structured, live-and-historical telemetry access (informed by wpilog-mcp and ClaudeScope, but built independently)
- A closed-loop sim/replay-verified code iteration workflow (informed by, but independently implemented from, Team 254's publicly-described approach)
- A genuinely new capability neither reference project has: a **live, in-competition mode** that outputs actionable PathPlanner waypoint/timing adjustments and flags match-critical hardware issues — matched to the reality that FRC teams do not change robot code during competition, only autonomous paths.

Explicitly NOT trying to be a forked IDE. Distribution should be as an MCP server + skill files usable from Claude Code (primary target), with reasonable effort toward also working from Cursor/Copilot/other MCP-capable clients.

### 3.2 — The two operating modes (core architectural seam, not just a usage convention)

**Mode A — Live/Competition Mode**
- Trigger: runs automatically as soon as a match log becomes available (ideally within seconds of a match ending — needs investigation into how quickly logs are flushed/available for reading; see Part 4 open questions).
- Cost/latency budget: must complete well within the gap between matches at a competition (realistically a handful of minutes at most, often less) — this is a hard design constraint, not a nice-to-have.
- Scope, explicitly limited to:
  1. PathPlanner waypoint/timing adjustment suggestions, output in a form directly usable in the PathPlanner GUI file format (research PathPlanner's actual file format — see Part 4).
  2. Critical safety/reliability flags only: brownout events, CAN bus faults/timeouts, comms/watchdog disconnects, battery health prediction. These do not result in code changes — they result in a plain-language flag to the pit crew ("check the battery," "check wiring on X") since the likely fix is electrical/mechanical, not software. This is a deliberate carve-out for "major issues worth surfacing immediately even though the action isn't a code change."
  3. Explicitly EXCLUDED from this mode: full swerve/PID oscillation audits, replay-drift analysis across many logs, any sim/replay sweep, any suggested code change of any kind. These are reserved for Mode B because they're too slow/expensive to run in a competition window and not actionable there anyway.

**Mode B — Post-Competition/Off-Season Mode**
- Trigger: run manually or on a schedule, after an event (or during off-season development), when there's no time pressure.
- Scope: everything else — deep swerve/PID audits, replay-drift analysis, season-long/event-long trend tracking (via TBA integration), and the full closed-loop sim/replay code-improvement cycle (see 3.4 below). This is where actual robot code changes are proposed, reviewed (Claude Code plan-mode style — see Source 4/6's emphasis on reviewing the plan more than the code), verified in sim/replay, and only then considered for deployment — never during a competition.

### 3.3 — Module breakdown (five core modules + the two-mode layer sits across all of them)

**Module 1 — Ingestion & Parsing Core**
- Use WPILib's official `DataLogReader` (Java) for `.wpilog` parsing — do not write a custom parser. This mirrors wpilog-mcp's correct decision; independently implement the surrounding server/tool logic, but don't reinvent the log-format parsing itself.
- Decode WPILib geometry/kinematics/vision types (Pose2d/3d, ChassisSpeeds, SwerveModuleState, TargetObservation, PoseObservation, etc.) — reference wpilog-mcp's documented type list (Source 5) as a checklist of what needs supporting, but implement independently.
- REV `.revlog` correlation: research whether reimplementing the timestamp-sync approach (coarse alignment via systemTime + filename, fine alignment via cross-correlation of duty-cycle-like signals, confidence scoring, clock-drift compensation for long recordings) is warranted for v1, or whether it should be deferred — this is a meaningfully complex sub-feature and may not be a v1 priority depending on whether 6369 uses REV or CTRE motor controllers (need to confirm which vendor 6369 actually uses before prioritizing this).
- **Persistent structured storage** (new — neither reference project does this): parsed log summaries should be written to a local structured store (e.g., SQLite) as logs are ingested, so that trend queries across a season don't require re-parsing every raw log file each time. Design this from the start rather than retrofitting.
- TBA (The Blue Alliance) API integration for match score/result/timing enrichment — same pattern as wpilog-mcp, independently implemented.

**Module 2 — Team/Robot Profile Layer (new — this is a genuine gap in both reference projects)**
- A config file (YAML or JSON) describing 6369's specific robot: subsystems, mechanisms, degrees of freedom, physical dimensions relevant to field-obstacle clearance (e.g., hopper height vs. trench clearance, matching 254's fuel-sim use case), CAN ID map, motor controller vendor(s) in use.
- This should be designed so that a DIFFERENT team could drop in their own profile and get team-specific analysis without code changes — this is the generalization piece that makes the tool usable beyond 6369, and it's explicitly something 254's internal tooling does NOT have (per the gap analysis in the conversation — their tooling is bespoke to their own robot) and wpilog-mcp also doesn't have (their game-info is generic/bundled, not robot-specific).
- Should also hold static game-info data (field geometry, scoring zones, match timing) for the current season — reuse the idea from wpilog-mcp's bundled game data, but keep it separable from the robot-specific profile.

**Module 3 — Analysis Layer**
- Adopt wpilog-mcp's design philosophy directly (this is a genuinely good pattern, independently re-implement it): small, composable, primitive tools rather than one opaque "diagnose" tool, so the agent's reasoning chain stays auditable.
- Adopt their epistemic-guardrails philosophy directly: every analysis tool's output should include a confidence/data-quality signal (sample count, data gaps, timing regularity) and use hedged language in tool descriptions, so the model doesn't overclaim from a single match's data.
- Build out analysis primitives covering (at minimum, matching wpilog-mcp's proven category list from Source 5, independently implemented): basic statistics/comparison/anomaly-detection/peak-finding/correlation; swerve/PID oscillation analysis; power/brownout/CAN-health analysis; cycle-time and autonomous-scoring analysis; vision-detection analysis.
- Include a discovery/guide tool (like `get_server_guide`) so the agent doesn't reinvent analysis that already exists as a tool, especially as the tool count grows.

**Module 4 — Write Layer (new — neither reference project needs this since both are read-only; this needs a first-class safety boundary designed in from the start, not retrofitted)**
- PathPlanner waypoint/timing output: research PathPlanner's actual path file format (see Part 4 open questions) and implement a writer that can propose specific, applyable waypoint/timing changes.
- Safe-tunable-only writes to live NetworkTables (if live NT is implemented at all in v1 — see Module 5): agent should be able to write ONLY explicitly-whitelisted tunable numeric parameters, NEVER actuator outputs, NEVER enable/disable state, NEVER anything that could move the robot. This boundary must be enforced in code (e.g., an explicit whitelist checked server-side), not left to model judgment or prompt instructions alone.
- Any code-change proposal that would eventually be deployed to the robot (Mode B only) requires explicit human confirmation before any deploy step — the agent should never autonomously push code to a robot.

**Module 5 — Live Telemetry Layer (new — this is ClaudeScope's one genuine advantage over wpilog-mcp, needs independent implementation)**
- Live NetworkTables querying, for use primarily in Mode A (live/competition mode) and for real-time monitoring generally.
- Needs a NetworkTables 4 (NT4) client implementation — research what libraries/approaches are available for this depending on the implementation language chosen (see Part 4 open questions on language/stack decision).
- Should integrate with Module 4's write-boundary rules if any live writes are supported at all.

**Module 6 — Sim/Replay Closed-Loop Layer (the genuinely novel, highest-effort piece — the equivalent of 254's undisclosed internal system, independently designed and built from the public description in Source 6)**
- Simulation-agent capability: drive WPILib's built-in headless simulator through a full teleop/auto run with zero required user intervention — agent supplies its own simulated gamepad/autonomous inputs, matching the skill described in Source 6's [21:40–22:20] excerpt.
- Should NOT attempt to build a physics simulator from scratch. Per Source 6 [28:40–29:20], 254 extended an existing off-the-shelf simulator (**MapleSim**) into 3D rather than writing one from scratch — research MapleSim (what it is, its license, whether it's usable/extensible for this project) as a first step before considering any custom simulation engineering. This research was NOT done in this pass and is an explicit open item for Part 4.
- Replay-testing capability: given one specific match log plus a proposed code change, replay just that segment and check what would have happened differently, without a full resimulation — matching AdvantageKit's built-in replay support (Source 6, [22:20–22:40]). Research exactly how AdvantageKit's replay mechanism works technically (what API surface it exposes, how a modified command/subsystem gets substituted into a replay run) — this was referenced but not deeply researched in this pass.
- Success-criteria/assertion framework: the agent needs a way to define and check pass/fail conditions against simulated or replayed results (e.g., "arm settles within 5% of setpoint in under a second," "auto scores non-zero and exceeds N points across multiple routines," matching Case Study 2 in Source 6). Design a lightweight assertion DSL, or have the agent translate natural-language success criteria into checks against logged/simulated signals — needs a concrete design decision during Part 4 research.
- **Persistent regression suite** (new — neither 254's described system nor either reference project mentions this): every verified fix/case study should accumulate into a standing library of "must still pass" scenarios that future changes get automatically checked against, not just the one scenario currently being worked on. This compounds in value across a season.
- Be honest in scoping: per the original brainstorm's own caution, this is a multi-week, genuinely hard engineering effort, not a weekend build. It should be sequenced LAST, after Modules 1-5 are working, per the build order in 3.5.

### 3.4 — Cost/efficiency requirements (explicit, cross-cutting concern across all modules)
- Live mode (3.2, Mode A) has a hard latency/cost budget — cache parsed logs, avoid re-parsing raw signals on every query, consider using a cheaper/faster model tier for purely mechanical steps (e.g., log parsing/formatting) and reserving the most capable model for actual diagnosis.
- Post-competition mode has more budget but should still avoid naive re-parsing of the same raw logs repeatedly — this is what Module 1's persistent structured storage is for.
- This whole project is unfunded/personal — do not design around the assumption of unlimited API spend. A team without heavy backing (unlike 254, who have implicit OpenAI support, or a fully-funded startup) needs to be able to run this affordably.

### 3.5 — Plan-review scaffolding (new — a genuine gap identified during the conversation)
- Because the intended reviewer of any agent-proposed plan (per Source 6's emphasis on plan-mode review) may be a less experienced student rather than a 254-caliber mentor, build lightweight, domain-specific plan-review checklists into the tool: does this touch CAN IDs? Does it check units? Does it handle the None/Noop IO case (per the pattern documented in Source 11's Team 360 CLAUDE.md)? This narrows the gap between an expert reviewer's judgment and a first-year student's.

### 3.6 — Explicit build order (given one solo developer, working over a summer, before build season starts)
1. Module 1 (ingestion/parsing core + persistent storage) — foundational, needed by everything else.
2. Module 2 (team/robot profile layer) — needed early since Module 3's analysis should be profile-aware from the start, not retrofitted.
3. Module 3 (analysis layer) — the log-analysis capability, roughly matching wpilog-mcp's proven scope but independently built and profile-aware.
4. Module 5 (live telemetry layer) — needed for Mode A (live/competition mode) to function at all.
5. Module 4 (write layer: PathPlanner output + safety-scoped tunable writes) — this is what turns Module 3+5's analysis into Mode A's actual deliverable (actionable waypoint suggestions).
6. **At this point, Mode A (live/competition mode) should be functional end-to-end and usable by 6369 immediately, even before Module 6 exists.**
7. Module 6 (sim/replay closed-loop layer) — the hardest, highest-effort piece, tackled last, once the developer has deep familiarity with the sim/replay internals from having built Modules 1-5.
8. Mode B (post-competition/off-season mode) becomes fully functional once Module 6 is done, since it depends on the sim/replay loop for its code-improvement cycle.

---

## PART 4 — EXPLICIT INSTRUCTIONS FOR CLAUDE CODE: WHAT TO DO BEFORE WRITING ANY CODE

**Read this section as a direct instruction, not background.** You (Claude Code, running as Opus or Fable) are more capable than the model that did the research compiled in Part 2 (a Sonnet-tier chat model without a coding sandbox). Your job right now is NOT to start implementing. Your job is to:

1. **Enter plan mode and stay there** through this entire research/planning phase. Do not write implementation code until an explicit go-ahead is given after the plan is reviewed.

2. **Re-verify every source in Part 2.** Treat the prior research pass as a first draft, not ground truth. Specifically:
   - Re-fetch and fully read wpilog-mcp's `doc/TOOLS.md`, `doc/DEVELOPMENT.md`, `doc/STANDALONE.md`, `doc/VACHE_POWER_ANALYSIS.md`, and `doc/VAALE_EVENT_ANALYSIS.md` — these were referenced by description only and never actually read in full.
   - Re-check whether ClaudeScope has since added a license file, updated its TODO list, or changed scope.
   - Confirm whether refinery-roborio-mcp and ros-mcp-server (mentioned as prior art but not deeply researched) have any additional relevant design ideas or licensing considerations worth knowing about.
   - Watch/extract the remaining ~13 minutes (35:00–48:32) of the 254 YouTube video (https://www.youtube.com/watch?v=oTcimMwxRoM) that this research pass did not cover.
   - Confirm current PathPlanner file format specifics (exact schema of the waypoint/path JSON or file format it reads/writes) — this was identified as needed but never actually researched.
   - Research MapleSim specifically (what it is, license, extensibility, current state) before assuming it's the right foundation for Module 6's simulation needs.
   - Research AdvantageKit's replay mechanism at an API/implementation level — what actually gets swapped out during a replay run, what the integration surface looks like for injecting a modified command/subsystem.
   - Confirm what motor-controller vendor(s) 6369 actually uses (CTRE/REV/both) before prioritizing REV-log-sync work in Module 1 — ask the project owner directly if this isn't discoverable from the team's existing codebase.
   - Confirm how quickly WPILib/AdvantageKit logs are actually flushed/available for reading after a match ends — this directly determines whether Mode A's "runs automatically right after each match" design is actually achievable within a real competition's between-match time window.

3. **Do deeper technical research on every module in Part 3** that wasn't fully specified, including but not limited to:
   - Concrete technology/language stack recommendation for the MCP server itself (Java, to directly reuse WPILib's `DataLogReader` per Module 1's own reasoning, vs. another language with a wrapper/FFI approach) — weigh trade-offs explicitly rather than assuming.
   - NT4 client library options for Module 5, per whatever language is chosen.
   - Concrete schema design for Module 1's persistent structured storage (SQLite schema proposal).
   - Concrete schema design for Module 2's team/robot profile config format.
   - A concrete design for Module 6's assertion/success-criteria DSL (or a decision to skip a DSL and rely on natural-language-to-check translation, with reasoning for the choice).
   - How MCP server distribution should actually work for a team like 6369 (matching wpilog-mcp's VS Code extension + standalone install pattern, or a different distribution approach) — reasoned decision, not just imitation.

4. **Surface open questions and design decisions explicitly, in writing, before proceeding to implementation.** Do not silently make judgment calls on anything that materially changes scope, safety behavior, or architecture — flag it for the project owner's review first, the same way Source 6 emphasizes reviewing the plan more than the code.

5. **Expand this document, do not just execute it.** Once the above research is done, produce an updated, more detailed version of Part 3 (the product spec) that incorporates what you learned — with concrete schemas, concrete API/library choices, and a more granular task breakdown per module — before writing any implementation code.

6. **Only after the expanded plan has been presented and reviewed should implementation begin**, following the build order in 3.6.
