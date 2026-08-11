---
name: frc-sim-in-the-loop
description: Close the agentic loop against a real robot's WPILib/AdvantageKit sim — the robot-side hook to make sim produce a log, scripted auto/teleop inputs, and how the harness verifies it. Also covers maple-sim and the big-AI-small-AI technique.
---

# Sim-in-the-loop (closing the agentic loop on real robot code)

The `simreplay` module runs the **whole** loop — build, run, verify, diagnose, iterate —
behind one command (`loop iterate` / the `loop_iterate` MCP tool). See
[docs/CLOSED-LOOP.md](../../docs/CLOSED-LOOP.md) for the harness side.

This skill covers the *robot* side: what a real team's project (e.g. 6369 Echo) needs before
the loop can drive it. Two small changes, because AdvantageKit `SIM` mode publishes to
NetworkTables but does **not** write a `.wpilog`, and the sim has no inputs.

## 1. Make SIM write a log (one line in Robot.java)

In the team's `Robot.java`, the `SIM` case currently only adds an `NT4Publisher`. Add a
`WPILOGWriter` so a headless sim run produces a log the harness can read:

```java
case SIM:
    // physics sim
    Logger.addDataReceiver(new NT4Publisher());
    Logger.addDataReceiver(new WPILOGWriter("logs/sim")); // <-- add: sim now produces a .wpilog
    break;
```

## 2. Drive teleop/auto with zero user intervention (a sim input script)

Use `DriverStationSim` to enable and select autonomous, so `./gradlew simulateJava`
runs a full auto with no human. Put this behind a flag (e.g. a system property) so it only
runs in the agent's headless loop, never on the real robot:

```java
if (Boolean.getBoolean("copilot.headlessAuto")) {
    DriverStationSim.setAutonomous(true);
    DriverStationSim.setEnabled(true);
    DriverStationSim.notifyNewData();
    // optionally set the auto chooser to a specific routine here
}
```

Run headless (no GUI extension) so it exits on its own after the auto window.

## 3. Declare the loop (a `loop.yaml` in the robot repo)

```yaml
name: echo-auto
workDir: .
build: ["./gradlew", "build"]
run:   ["./gradlew", "simulateJava", "-Dcopilot.headlessAuto=true"]
logDir: logs/sim               # where the WPILOGWriter above writes
scenarioDir: scenarios
baseline: .loop/baseline.wpilog
sources: ["src/main/java"]
```

## 4. Run the loop

```bash
simreplay iterate <echo-repo-dir>/loop.yaml
```

One turn: rebuild, run headless, verify every scenario, and diagnose what failed. The agent
edits code and re-runs until it passes — the full edit → build → run → verify → iterate loop,
with plan-review at the front and `DeployGate` before anything reaches a real robot.

Getting the first scenario is easiest from a run that already works:

```bash
simreplay generate logs/sim/good.wpilog scenarios/auto.yaml auto_scores /Robot/State AUTO
```

Read the failure *kind* rather than just FAIL: `SIGNAL_CONSTANT` means the mechanism never
ran, `SHORTFALL` means it ran and fell short, `SIGNAL_ABSENT` usually means a renamed log key.
With a baseline adopted (`simreplay baseline`), failures also come with a ranked list of which
signals diverged from the known-good run.

The older `simreplay run <workDir> <logDir> <scenario> -- <cmd...>` still works for a one-off
check without a `loop.yaml`, but it has no diagnosis, no baseline diff, and no journal.

> Note on this repo: Echo's local reference clone can be patched as above to demo this
> locally, but that patch must **not** be pushed to the team's robot repo.

## maple-sim (physics/collision fidelity)

WPILib's built-in `ModuleIOSim` is kinematic. For bump/trench traversal or game-piece
collisions (254's use case), add [maple-sim](https://github.com/Shenzhen-Robotics-Alliance/maple-sim)
(MIT) as a vendordep and back the drive/IO sim with its `SwerveDriveSimulation`. It's an
opt-in upgrade per robot, gated on need — not required for the loop to function.

`example-robot` demonstrates this concretely: `DriveSubsystem` wraps a real
`SwerveDriveSimulation` (dyn4j rigid-body physics — mass, momentum, field-boundary
collisions), and `DriveAuto` drives it toward a fixed scoring pose with `broken` negating
the translation command (a "drive axis inverted" wiring bug). `HeadlessSim` logs
`/Drivetrain/Pose/{X,Y}` and `/Drivetrain/DistanceToTarget`, and
`example-robot/scenarios/auto_drives_to_target.yaml` asserts the auto ends within 1 m of
the target — FAILing on the broken build (physics carries the robot ~4 m off course) and
PASSing on the fixed one. Since the drivetrain is real dyn4j physics rather than a
kinematic stub, the failure distance is whatever the sim's inertia/damping actually
produces, not a scripted number. Gradle wiring: root `build.gradle` adds maple-sim's
vendordep-published Maven repo; `example-robot/build.gradle` depends on
`org.ironmaple:maplesim-java`, `org.dyn4j:dyn4j`, and `org.ejml:ejml-simple` (the last two
are maple-sim's own runtime deps — its published `.pom` omits them, so they must be added
explicitly or you'll hit `NoClassDefFoundError` at first use).

## big-AI-small-AI

When a decision is better learned than coded (254's "when to stage balls"), use the
`smallmodel` module: hand-label ~30 examples (timestamp + feature values + a 0/1 label)
from synced video/logs, `LogisticModel.train(...)`, check `evaluate(...)` precision/recall
in replay, then deploy the tiny model. The larger agent orchestrates the labeling and
training; the shipped artifact is a handful of weights.
