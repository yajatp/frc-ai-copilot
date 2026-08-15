# Public release post — draft

Working draft of the public announcement. **Not yet posted.** See "Before posting" at the bottom —
there is one decision that is not the author's to make and a short pre-flight list.

Suggested title:

> **FRC AI Copilot — open-source log analysis, PathPlanner editing, and a closed-loop code
> iteration harness (MIT)**

---

## Post body

We're Team 6369 (Mercenary Robotics) / 6773, and we've spent this season building a tool for
ourselves that we think is worth sharing. It's MIT licensed and on GitHub: **[repo link]**.

### What it is

An AI copilot for FRC robot code. It reads your match logs, tells you what went wrong, proposes the
autonomous tweaks you'd actually make between matches, and — the part we're most interested in —
closes the loop on robot code: it edits, builds, runs the code in simulation or against a recorded
log, checks the result against success criteria you wrote down, and iterates until they pass.

It ships as an MCP server plus skills, so it runs inside whatever AI coding tool you already use
(VS Code, Cursor, Codex, Antigravity). It isn't a coding agent — it's FRC expertise for the one you
have.

### The two things it's built around

**Teams don't recompile robot code at competition — they tweak autonomous paths.** So there are two
modes with genuinely different budgets. Mode A is the between-match pass: brownout, battery, CAN and
loop-timing flags, plus PathPlanner waypoint *and timing* suggestions, fast enough to run in the gap
between matches. It can run automatically — point a watcher at your USB drive or Driver Station log
folder and it ingests and analyzes each match as it lands. Mode B is the after-the-event work: full
audits, season trends, and the closed-loop iteration.

**A tool that sounds confident about a single match is worse than no tool.** Every analysis result
carries its sample count, a timing-regularity check, and a confidence level, and the wording is
hedged to match. If a check's input signal isn't in your log, it says "cannot see this" rather than
"fine". We'd rather it said "approximate" than invent a number.

### What's in it

- **Log analysis** — `.wpilog` parsing with struct decoding, and 15 composable primitives:
  power/brownout, battery, CAN, loop timing, swerve/PID, vision, cycle times, anomalies, peaks,
  correlation, comparison.
- **Season trends** — metrics persisted to local SQLite, so a season-wide query is a database read
  rather than re-parsing every log.
- **Robot profile** — generated from your repo (CAN IDs, drivetrain geometry, PathPlanner settings,
  vendordeps), so the analysis is about *your* robot.
- **PathPlanner editing** — waypoint fudging, global speed, event-marker timing, and per-zone
  constraints, all as reviewable diffs. Dry-run by default; it never overwrites a file in place.
- **Live telemetry** — NT4 read access, with a hard, code-enforced safety boundary on writes.
- **Closed loop** — edit → build → run → verify → diagnose → iterate, against headless simulation or
  a replay of a match that already happened.
- **Offline docs** — local full-text search over WPILib, CTRE Phoenix 6, PhotonVision and PathPlanner
  documentation plus the game manual, page-cited. No network, which is the point in a pit.
- **Dashboard** — a local web UI over all of it.
- **Small models** — train a tiny, inspectable classifier from a few moments you mark in a log, for
  the judgement calls no sensor reports.

### Getting started

You need the WPILib 2026 install (it provides the JDK and an offline Maven repo). Then:

```bash
git clone <repo>
cd frc-ai-copilot
export JAVA_HOME=~/wpilib/2026/jdk
export GRADLE_USER_HOME="$PWD/.gradle-home"
./gradlew build
```

Windows, macOS and Linux, x64 or ARM. Then point it at your robot repo to generate your profile, and
run the installer for your editor. `CONTRIBUTING.md` walks through it, including the few things that
look broken on a fresh checkout and aren't.

### What it does *not* do, honestly

We'd rather you find these out here than after cloning:

- **It has been used by two teams.** Everything is tested (166 automated tests, CI on all three
  platforms), but "tested" and "battle-proven across many teams' setups" are different things. The
  bundled 2026 field data beyond standard dimensions and match timing is explicitly marked
  approximate.
- **The replay path is demonstrated with our own example robot.** The harness is command-agnostic, so
  an AdvantageKit REPLAY task fits the same contract, but we don't have an AdvantageKit project in
  the repo to prove that end to end — so that specific integration is untested rather than verified.
- **Replay only validly evaluates code downstream of what's in your log.** Scoring logic driven by a
  recorded timeline replays faithfully; anything physically coupled (chassis pose depends on
  actuation feeding back through physics) has to be evaluated in sim. This is a property of replay in
  general, not a limitation we could engineer away.
- **The small-model metrics are training-set metrics.** With a few dozen hand-marked examples there
  isn't enough data to hold any out. Validate against a different log before trusting one on a robot.
- **Game-specific coordinates are approximate placeholders.** Verify against official field drawings
  before using them for clearance decisions.

### Safety

The write boundaries were designed in, not retrofitted. Every file edit is a reviewable diff and
dry-run by default. The dashboard binds to loopback only and is read-only. The single live-NT write
path is a default-deny whitelist, doubles only, enforced in code rather than by prompt instructions —
an agent cannot command actuators or change enable state through this tool.

### Contributing

MIT. Use it, fork it, ship it on your robot — attribution appreciated, not required. Issues and PRs
welcome; CI builds and tests on Ubuntu, Windows and macOS.

Happy to answer questions in this thread.

---

## Before posting

### 1. Acknowledgments — needs a decision, not mine to make

**This section is deliberately blank and must be filled in before this is posted.**

The current README has no prior-art or inspiration credits: they were removed on purpose, with the
stated intent to make the project "more transparent about influences before any external release."
This *is* that external release, so the deferred decision now comes due — and which influences to
name, and how, is a judgement call for the project owner rather than something to generate.

Two categories worth separating:

- **Software the project builds on.** WPILib, PathPlanner, maple-sim (physics simulation), dyn4j,
  Jackson, SQLite/xerial, PDFBox, EJML, JUnit. These are dependencies, and naming them is normal
  courtesy that costs nothing. The MIT license does not compel it, but their licenses may have their
  own notice requirements worth a check.
- **Design influences and prior art.** Deliberately omitted pending your call. The FRC community
  takes attribution seriously, and a release post that reads as though nothing preceded it tends to
  attract exactly that criticism — so this is worth getting right rather than skipping.

### 2. Pre-flight

- [ ] Fill in the Acknowledgments decision above.
- [ ] Replace **[repo link]** and confirm the repository is actually public.
- [ ] Re-run the reference sweep before publishing. Every user-facing surface was cleared of other
      teams, tools and affiliations on 2026-08-15; confirm nothing has crept back in since.
- [ ] Re-check the test count and tool count against the current build rather than trusting the
      numbers above.
- [ ] Confirm the season: this post assumes the 2026 season data is current. `GameData` is the one
      place that changes.
- [ ] Have someone who has never set the project up follow `CONTRIBUTING.md` on a **Windows** machine
      start to finish. CI proves it builds there; it does not prove the instructions are followable.
- [ ] Decide whether to link the dashboard screenshots — the post is text-only as drafted, and a
      screenshot of the Live page would carry a lot of it.
