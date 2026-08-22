# The agentic closed loop

The loop lets an AI agent debug robot code the way a good student does at a competition: change
something, run it, look at what the robot actually did, and decide whether that helped — without a
robot, a field, or a human in the chair.

`simreplay` could already answer *"does this log satisfy these criteria"*. What it could not do was
run a turn. This is the rest of the cycle:

```
   edit robot code  ->  build  ->  run headless  ->  verify  ->  diagnose  ->  edit again
                          ^                                                       |
                          +-------------------------------------------------------+
```

Each turn is one command: `loop iterate`, or the `loop_iterate` MCP tool.

## Setup: one file

Drop a `loop.yaml` in the robot project. It says how to build the code, how to run it headless, and
what "working" means. Without it, every invocation needs four arguments an agent must keep straight
across iterations; with it, a turn takes none.

```yaml
name: my-robot-auto
workDir: .                                    # where commands run (relative to this file)
build: ["./gradlew", "build"]                 # optional; a failing build short-circuits the turn
run:   ["./gradlew", "simulateJava"]          # anything that produces a .wpilog
logDir: logs                                  # where to find the log the run wrote
scenarioDir: scenarios                        # the checks that must pass
baseline: .loop/baseline.wpilog               # optional known-good run to diff failures against
sources: ["src/main/java"]                    # fingerprinted, to attribute behaviour to edits
timeoutSeconds: 900
```

If the run command can be told where to write its log, use the `{log}` placeholder instead of
`logDir` — each iteration then gets its own unambiguous output file rather than one guessed at by
modification time:

```yaml
run: ["build/install/robot/bin/robot", "{log}"]
```

Environment variables for the build and run steps go under `env:`, where `{workDir}` and
`{baseDir}` expand to real paths — how a committed config points at something inside the checkout
without hardcoding a machine:

```yaml
env:
  GRADLE_USER_HOME: "{workDir}/.gradle-home"
```

## Getting the first scenario

A loop with nothing to verify against is just a build script, and a scenario is derived from a
known-good log — so a project being onboarded has a circle to break: no scenario without a log, and
`iterate` will not run without a scenario. `bootstrap` is the way in. It builds and runs once,
verifying nothing, purely to produce that first log:

```bash
simreplay bootstrap                 # or the loop_bootstrap MCP tool
```

Then derive the checks from it:

```bash
simreplay generate good.wpilog scenarios/auto.yaml auto_scores /Robot/State AUTO
```

That reads the log and proposes checks for the signals with a shape worth defending — battery
voltage, loop period, errors that converge, counters, and **where the robot finished**. It
deliberately does not put an envelope around every signal: a suite that fails on ordinary
run-to-run variation gets deleted within a week.

Three things it will not propose, because on a real AdvantageKit log they produced nothing but
noise: wall clocks and uptime counters (always increasing, always satisfied); signals with fewer
than 20 samples in the window (logging is on-change, so a settled signal has three samples that
happen to rise and are indistinguishable from a counter); and one-sided bounds on a pose.

Pose fields get a box on **both** sides — `LAST >= x-0.20` and `LAST <= x+0.20` — because an auto
that ends half a metre short still satisfies any "did it move" threshold taken from a good run.
This is the check most worth reading and adjusting: 0.20 m and 6° are starting points chosen to
absorb run-to-run variation, not a spec for your robot.

A pose only gets that box if it had **come to rest** by the end of the window. A value still
travelling when the window closes records where the window ended, not where the robot got to, and
boxing it fails on timing jitter alone. Anything skipped for that reason is named in the generated
scenario's description rather than dropped quietly — if your own odometry pose shows up there, the
run was cut off before the routine finished, and the fix is to give the headless run a longer
window and regenerate.

Thresholds carry a 10% tolerance and are **a starting point, not a spec** — they encode one run.
Read them before banking them. A generated scenario is always satisfied by the log it came from.

### Asserting on a pose

Geometry types are logged as structs, and a struct is not a number. Every struct entry is therefore
also readable as its scalar fields, which is what a check names:

```yaml
- signal: "/RealOutputs/Odometry/Robot/X"     # not /RealOutputs/Odometry/Robot
  aggregation: LAST
  op: LE
  threshold: 3.46
```

`Pose2d` and `Pose3d` give `X`, `Y`, `Z`; rotations give `Roll`/`Pitch`/`Yaw` or `Rotation`, each in
radians plus a `Deg` companion; `ChassisSpeeds` gives `Vx`/`Vy`/`Omega`/`Speed`; swerve states give
`Speed`/`Angle`. Struct *arrays* are not projected — element 3 of an odometry batch means nothing
stable across runs.

## A turn

```bash
simreplay iterate                 # finds the nearest loop.yaml, like git finds its root
```

Outcomes are kept distinct because they call for different next actions:

| Outcome | What it means | What to do |
|---|---|---|
| `BUILD_FAILED` | The edit does not compile — or the build command could not be started at all, which is reported separately | Read the compiler output in the report; nothing about the robot can be concluded yet |
| `NO_SCENARIOS` | Nothing to verify against | `simreplay bootstrap`, then `generate` — see above |
| `BOOTSTRAPPED` | Built and ran to produce a first log, verifying nothing | Derive a scenario from the log it names |
| `RUN_FAILED` / `NO_LOG` | The code crashed, or logged nothing | Check the output tail; verify data logging is started |
| `CHECKS_FAILED` | The robot ran and misbehaved | Read the diagnosis below |
| `NO_INPUT_LOG` | A replay config's recorded log is missing | Point `inputLog` at a real log, or pass `--input-log`; nothing was built |
| `PASSED` | Bank a scenario and move on | `simreplay baseline` to adopt this run as the reference |

## Replay: what would my change have done on *that* match

The turn above simulates a fresh match. The other half of Module 6 is the opposite question — given
a match log that already exists plus a proposed change, what would have happened differently, without
resimulating.

A config becomes a replay config by putting `{inputLog}` in its run command. `{log}` stays the
output the pass produces, so a replay config normally has both — read one, write the other:

```yaml
run: ["build/install/robot/bin/robot-replay", "{inputLog}", "{log}"]
inputLog: .loop/recorded-match.wpilog
```

```bash
simreplay iterate replay.yaml                                  # replay the configured log
simreplay iterate replay.yaml --input-log logs/qm42.wpilog     # replay a specific match
```

`--input-log` (and `loop_iterate`'s `inputLog`) is what points one config at each of a season's match
logs in turn, rather than only the one it names.

**Replay is only valid for code downstream of the signals in the log.** This is a property of replay
in general, not of this harness, and it decides which questions belong on which path. Logic that is a
function of recorded inputs — a scoring sequence driven by the recorded tick timeline — is faithfully
re-executed. Physically coupled outputs are not: the chassis pose depends on actuation feeding back
through the physics, so no replay pass can move it, and a drivetrain change has to be evaluated in
sim. A replay config should therefore assert only what replay can answer; `example-robot/replay.yaml`
uses its own `replay-scenarios/` directory for exactly this reason, and the scenario there says why
the drive check is deliberately absent.

Practical consequence: a replay turn does not run a physics engine, so it costs a pass over a file.
`--from`/`--to` on the example robot's replay entry point narrows it further to just the segment in
question.

A project may declare both a sim config and a replay config over the same tree. Their iteration
journals are kept separate (`.loop/session-<name>.json`), because the "how did every checked value
move since the last iteration" deltas are only meaningful between comparable runs — interleaving a
sim turn and a replay turn would diff two different things and present it as progress.

## Reading a failure

A bare `FAIL` says a check failed and nothing else. The loop separates failures by shape, because
they point at completely different files:

- **`SIGNAL_CONSTANT`** — the value never moved. The code path that changes it almost certainly
  never executed: an unscheduled command, an unmet trigger, or an action routed somewhere else.
  This is the 254 "shoot mapped to a pass" defect.
- **`POLARITY_REVERSED`** — it moved about as hard as the baseline did, the other way. A sign
  error: an inverted motor or encoder, a negated axis, a swapped setpoint. **Requires an adopted
  baseline** — against a single run this is indistinguishable from a shortfall, and gets reported
  as one, with tuning advice that will waste an afternoon on a controller that is working.
- **`SHORTFALL`** — it moved but fell short. Usually gains, timing, or a window that ends too
  early.
- **`SIGNAL_ABSENT`** — no samples at all. Usually the log key was renamed, not that the robot
  misbehaved; the report names the closest keys that *are* present. If the key exists but holds a
  struct, the message says so and points at the field form (`.../Robot/X`).
- **`NO_SAMPLES_IN_PHASE`** — the signal exists but the phase never occurred.

When a baseline is configured, the report also ranks every signal by how far it diverged from that
known-good run, normalized by each signal's own range so signals in different units stay comparable.
The largest divergence usually sits closest to the cause. A signal present in only one of the two
logs is called out separately — that is a rename, or a subsystem that never initialized. Wall
clocks are excluded: they differ between any two runs by a wide margin and would otherwise take the
top of the ranking every time. Accumulating signals — a wheel's total travel, a score tally — are
compared on *how much they accumulated* rather than on where they happened to be, for the same
reason: a running integral mostly reports how much of the window elapsed.

**Divergence is reported on passing turns too.** A change can move the robot materially while
staying inside every threshold — detuned path-following gains that land the auto 40 cm off is the
canonical case — and a clean `PASSED` with no other information is how that ships. On a pass the
section is labelled to say what it means: anything large there moved without a check noticing, and
either the change or the thresholds need attention.

This is most of why `simreplay baseline` is worth running the moment a turn passes. Without it the
loop can still tell you a check failed; with it, it can tell you *which way* things went wrong and
what else moved.

## Across turns

The journal in `.loop/session.json` records each turn: which source files changed, and how every
checked value moved.

```
$ simreplay history
loop history for example-robot-auto:
  #1 PASS  3 scenarios
  #2 FAIL  3 scenarios
      changed: src/main/java/.../ScoringSubsystem.java
      auto_scores_balls / auto scores non-zero: 5.0000 -> 0.0000 (now FAILS)
  #3 PASS  3 scenarios
      changed: src/main/java/.../ScoringSubsystem.java
      auto_scores_balls / auto scores non-zero: 0.0000 -> 5.0000 (now PASSES)
```

This is on disk rather than in conversation context on purpose: "did my last edit help" is the
question every iteration turns on, and remembering the previous run's numbers is exactly what
degrades over a long debugging session. It also means a later session can pick up where one left off.

Fingerprints are content hashes, not timestamps, so a rebuild that touches files without changing
them is not misreported as an edit.

## Banking a fix

Every verified fix should become a standing check, or the same regression comes back in three weeks.
When a turn passes:

```bash
simreplay baseline                                   # adopt this run as the reference
simreplay generate <that log> scenarios/new.yaml ...  # bank what it proved
```

The whole suite runs on every subsequent turn, so the value compounds across a season.

## MCP tools

| Tool | Use |
|---|---|
| `loop_iterate` | Run one full turn after editing robot code (pass `inputLog` to replay a recorded match) |
| `loop_history` | What has already been tried (read this when resuming) |
| `loop_generate` | Derive a scenario from a known-good run |
| `loop_diff` | Rank signal divergence between two logs directly |
| `loop_check` / `loop_suite` | Verify an existing log without running anything |

## Worked example

`example-robot/` is set up as a working loop against a maple-sim physics simulation — a real WPILib
command-based robot running headless through the actual command scheduler. From the repo root:

```bash
simreplay iterate example-robot/loop.yaml
```

Introduce the classic defect in `ScoringSubsystem.shoot()` (route the shot to a pass) and iterate
again: the loop reports `SIGNAL_CONSTANT` on `/Autonomous/BallsScored`, isolates that one signal out
of six as the divergence from baseline, and names the file you edited. Revert it, and the journal
records the recovery.

### The replay example

`example-robot/replay.yaml` runs the same robot on the replay path. `ReplaySim` reads a recorded log's
phase timeline and pose, re-executes the scoring commands against it through the real
`CommandScheduler`, and writes a fresh log — no physics engine.

```bash
# Record a match with the defect in place: it scores 0.
example-robot/build/install/example-robot/bin/example-robot .loop/recorded-match.wpilog broken

# Ask what the current (fixed) code would have done on that same match.
simreplay iterate example-robot/replay.yaml
```

The recorded match scored 0; replaying it through the fixed code reports 5 and passes. Replaying it
through the broken code reproduces the recorded 0 and fails — which is the check that matters, since
a replay that always passed would be measuring nothing.

> Scope note: `ReplaySim` is a replay source written for this example robot. It demonstrates the
> mechanism and proves the harness's replay path end to end. The harness itself is command-agnostic,
> so an AdvantageKit `REPLAY` task fits the same `{inputLog}` contract — but this repository contains
> no AdvantageKit project, so that specific integration is untested here rather than verified.
