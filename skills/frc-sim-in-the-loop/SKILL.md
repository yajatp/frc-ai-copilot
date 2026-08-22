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

## 2. Drive auto with zero user intervention

This is the part that bites. Four things all have to be true, and getting any of them wrong
looks identical from outside: the sim runs, writes a log, and the robot sits there disabled.

**a. The flag has to reach the robot JVM.** `./gradlew simulateJava -Dcopilot.headlessAuto=true`
does *not* work: GradleRIO forks the robot into its own JVM, and `-D` on the Gradle command line
sets the property on the *Gradle* JVM only. Use an environment variable, which is inherited by
the forked process:

```java
private static boolean headlessAuto() {
    return System.getenv("COPILOT_HEADLESS_AUTO") != null;
}
```

**b. The sim GUI has to be off.** Every GradleRIO project has
`wpi.sim.addGui().defaultEnabled = true` in `build.gradle`, so `simulateJava` loads
`libhalsim_gui`, and the GUI's Driver Station panel owns the enable state — it will hold the
robot disabled no matter what your code asks for. Setting `HALSIM_EXTENSIONS` in the
environment does not override it, because GradleRIO sets that on the task. Make it conditional
in `build.gradle`:

```groovy
wpi.sim.addGui().defaultEnabled = System.getenv("COPILOT_HEADLESS") == null
if (System.getenv("COPILOT_HEADLESS") == null) wpi.sim.addDriverstation()
```

**c. The enable has to be re-asserted every loop, not set once.** A single
`DriverStationSim.setEnabled(true)` in the `Robot` constructor does not stick. Do it in
`robotPeriodic()`, before `CommandScheduler.getInstance().run()`.

**d. Nothing ends the match.** A headless sim runs forever; the harness would sit there until
`timeoutSeconds`. End it yourself — and give the routine long enough to actually **finish**. A
window that cuts off mid-path leaves the robot still moving when the log ends, and a check on where
the auto finished cannot be generated from a run that never finished. If a generated scenario says
it skipped your odometry pose, this is why: raise the window past the length of the routine.

Together, in `Robot.java`:

```java
private double headlessStart = -1;

@Override
public void robotPeriodic() {
    if (headlessAuto()) {
        if (headlessStart < 0) headlessStart = Timer.getFPGATimestamp();
        if (Timer.getFPGATimestamp() - headlessStart < 15.0) {
            DriverStationSim.setAutonomous(true);
            DriverStationSim.setEnabled(true);
            DriverStationSim.setDsAttached(true);
            DriverStationSim.notifyNewData();
        } else {
            DriverStationSim.setEnabled(false);
            DriverStationSim.notifyNewData();
            Logger.end();          // flush the WPILOGWriter
            System.exit(0);
        }
    }
    CommandScheduler.getInstance().run();
    // ... the rest of your robotPeriodic
}
```

**Verify it worked before going further.** The whole point is a log of a robot that *ran*:

```bash
core-ingest dump <log>.wpilog /DriverStation/Enabled
```

One sample reading `false` means the robot never enabled — go back through (a) to (c). This
check takes ten seconds and saves an hour of reading an empty log as a robot bug.

## 3. Pick the auto (the chooser has no dashboard to read)

`AutoBuilder.buildAutoChooser()` defaults to "None" with no Driver Station attached, so the
robot enables and then does nothing. Name the routine explicitly in `autonomousInit()`:

```java
String name = System.getenv("COPILOT_HEADLESS_AUTO_NAME");
if (headlessAuto() && name != null && !name.isBlank()) {
    autonomousCommand = new PathPlannerAuto(name);
}
```

## 4. Declare the loop (a `loop.yaml` in the robot repo)

```yaml
name: echo-auto
workDir: .
build: ["./gradlew", "build", "-x", "test"]
run:   ["./gradlew", "simulateJava"]
logDir: logs/sim               # where the WPILOGWriter above writes
scenarioDir: scenarios
baseline: .loop/baseline.wpilog
sources: ["src/main/java"]
env:
  GRADLE_USER_HOME: "{workDir}/.gradle-home"
  COPILOT_HEADLESS: "1"
  COPILOT_HEADLESS_AUTO: "1"
  COPILOT_HEADLESS_AUTO_NAME: "LeftJamesAuto"
timeoutSeconds: 300
```

## 5. Run the loop

A project that has never produced a log has nothing to derive a scenario from, so start with
`bootstrap` — it builds and runs once, verifying nothing:

```bash
simreplay bootstrap <echo-repo-dir>/loop.yaml
simreplay generate <the log it printed> <echo-repo-dir>/scenarios/auto.yaml \
    echo_auto /DriverStation/Autonomous true
simreplay iterate   <echo-repo-dir>/loop.yaml
simreplay baseline  <echo-repo-dir>/loop.yaml   # once it passes
```

Adopt the baseline as soon as a turn passes. Without it a failure can only be reported as a
shortfall; with it, an inverted output is recognised as `POLARITY_REVERSED` and every turn
reports what moved relative to the known-good run — including turns that pass.

### First-run notes

- **Vendordeps need the network once.** PathPlanner, AdvantageKit and Phoenix 6 are not in
  WPILib's offline Maven repo, so the first build of a robot project has to reach the internet.
  After that it is cached in `GRADLE_USER_HOME` and the loop runs offline. Plan for this before
  an event, not in the pit.
- **`gradlew` may not be executable.** Some robot repos commit it as mode `100644`, and a fresh
  clone then cannot run its own build. `chmod +x gradlew && git update-index --chmod=+x gradlew`.

Each turn rebuilds, runs headless, verifies every scenario, and diagnoses what failed. The agent
edits code and re-runs until it passes — the full edit → build → run → verify → iterate loop, with
plan-review at the front and `DeployGate` before anything reaches a real robot.

Read the failure *kind* rather than just FAIL: `SIGNAL_CONSTANT` means the mechanism never ran,
`POLARITY_REVERSED` means it ran the wrong way round (a sign error, not a tuning problem),
`SHORTFALL` means it ran and fell short or overshot, and `SIGNAL_ABSENT` usually means a renamed
log key — or a struct named where one of its fields was meant.

The older `simreplay run <workDir> <logDir> <scenario> -- <cmd...>` still works for a one-off
check without a `loop.yaml`, but it has no diagnosis, no baseline diff, and no journal.

> Note on this repo: Echo's local reference clone can be patched as above to demo this
> locally, but that patch must **not** be pushed to the team's robot repo.

## What sim will not catch

Worth saying plainly, because the loop passing is easy to read as "the robot is fine":

- **Motor and encoder inversions.** `ModuleIOSim` does not model the inversion flags in
  `TunerConstants`, so flipping `kInvertRightSide` produces a bit-identical run. Inversion bugs
  are invisible here by construction — they are found on blocks, not in this loop.
- **Anything downstream of real hardware timing** — CAN latency, brownout under real current
  draw, a sensor that drops out when a wire is chafed.

Sim answers questions about *logic and control*. It does not answer questions about *wiring*.

## maple-sim (physics/collision fidelity)

WPILib's built-in `ModuleIOSim` is kinematic. For bump/trench traversal or game-piece
collisions, add **maple-sim** (MIT) as a vendordep and back the drive/IO sim with its `SwerveDriveSimulation`. It's an
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

When a decision is better learned than coded (e.g. "when is the right moment to stage game
pieces"), use the
`smallmodel` module: hand-label ~30 examples (timestamp + feature values + a 0/1 label)
from synced video/logs, `LogisticModel.train(...)`, check `evaluate(...)` precision/recall
in replay, then deploy the tiny model. The larger agent orchestrates the labeling and
training; the shipped artifact is a handful of weights.
