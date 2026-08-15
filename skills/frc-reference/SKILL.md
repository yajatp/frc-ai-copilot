---
name: frc-reference
description: FRC robot-code conventions used by Team 6369 (Echo) and Team 6773 (AlphaBot) — the AdvantageKit IO-layer pattern, CTRE Phoenix 6, PathPlanner, AutoLog, LoggedTunableNumber, the REAL/SIM/REPLAY switch, and a plan-review checklist. Load this before writing, editing, or reviewing any robot Java code (subsystems, commands, RobotContainer, autos) so changes match the team's actual structure instead of generic WPILib advice.
---

# FRC reference conventions (AdvantageKit / CTRE / PathPlanner)

Both 6369's "Echo" and 6773's "AlphaBot" are built on the upstream **AdvantageKit** swerve
template (which carries a GPLv3 header on the template files — keep it). Stack: **AdvantageKit** (`org.littletonrobotics.akit`,
`@AutoLog`/`Logger`), **CTRE Phoenix 6** (TalonFX, Pigeon2 — 6369 is pure CTRE; 6773 mixes in
**REVLib**/SparkMax), **PathPlanner** (autonomous paths/autos + `LocalADStarAK` on-the-fly
pathfinding), Limelight vision, JUnit 5. Package root is `frc.robot`; mechanisms live under
`frc.robot.subsystems.<name>`.

Before touching code, get robot-specific ground truth: call the MCP tool `profile_show` on the
relevant team's profile (`profiles/6369-echo.yaml` / `profiles/6773-alphabot.yaml`) — real CAN
IDs, current limits, drivetrain numbers, subsystem list. Don't guess at these; the profile is
bootstrapped straight from the team's own `*Constants.java` / `pathplanner/settings.json`.

## The IO-layer pattern

The core convention: **all hardware access is isolated behind an interface**, so the exact same
subsystem logic runs unmodified on real hardware, in physics sim, and during AdvantageKit log
replay. This is *why* replay is deterministic and why Module 6's closed loop can drive the team's
own robot code headlessly — it's not a testing add-on, it's how every subsystem is already built.

A subsystem named `Intake` is exactly four (or five) files, using the real Echo code as the
concrete example:

| File | Role |
|---|---|
| `Intake.java` | The `SubsystemBase`. Holds an `IntakeIO` reference + one `IntakeIOInputsAutoLogged`. `periodic()` calls `io.updateInputs(inputs)`, `Logger.processInputs("Intake/Inputs", inputs)`, then decides goals/setpoints purely in terms of the IO's abstract API. **Never imports `com.ctre.phoenix6.*` or `com.revrobotics.*` directly.** |
| `IntakeIO.java` | Interface. A static inner `IntakeIOInputs` class with plain default-initialized fields (`public double appliedVolts = 0.0;` etc.), annotated `@AutoLog`. Plus the hardware-agnostic method surface: `updateInputs`, `setPosition`, `stop`, `setGains`, `setBrakeMode`, ... |
| `IntakeIOTalonFX.java` | Real-hardware impl. Builds a `TalonFXConfiguration` from `Constants` records (gains, current limits, neutral mode), holds `StatusSignal<T>` handles for position/velocity/voltage/current/temp, refreshes them each cycle via `BaseStatusSignal.refreshAll(...).isOK()` → feeds `inputs.isMotorConnected`. |
| `IntakeIOSim.java` | Physics-sim impl (pattern: see `ModuleIOSim` below) — wraps a `DCMotorSim`/arm sim, closes the loop in software, zero CAN. |
| *(no file)* | **Noop/REPLAY impl.** Echo's actual idiom is **not** a dedicated `XxxIONoop.java` — every method on the IO interface can be a `default {}` no-op, so a bare anonymous instance (`new IntakeIO() {}`) is a legal, zero-effort Noop. See "REAL/SIM/REPLAY switch" below. |
| `XxxConstants.java` | `public static final` fields/records: `IntakeHardware` (CAN IDs), `IntakeGains` (P/I/D/V/S/G/A + motion-profile limits), `IntakeMotorConfiguration` (invert, stator/supply current limits + enables, neutral mode), optionally `SimulationConfiguration` (`DCMotor` type, inertia/mass, `simulateGravity`). |

**Do not hand-write `XxxIOInputsAutoLogged.java`.** It's generated at compile time by the
`akit-autolog` annotation processor (wired in `build.gradle` as
`annotationProcessor "org.littletonrobotics.akit:akit-autolog:$akitJson.version"`) from the
`@AutoLog`-annotated inputs class — it appears under
`build/generated/sources/annotationProcessor/...` after building. If you find one checked into
`src/`, treat it as stale/legacy, not something to maintain by hand.

`@AutoLogOutput(key = "Intake/Feedback/PositionAtGoal")` on a subsystem method/getter logs a
derived value (error, at-goal boolean, etc.) without needing its own inputs field — used
throughout `Intake.java` for computed diagnostics.

### Two real gotchas from Echo's own code (why the checklist below exists)

1. **Not every mechanism actually gets a Sim/Noop swap.** In `RobotContainer`, the `SIM` and
   default/`REPLAY` branches faithfully swap `Drive`'s `ModuleIOTalonFX`→`ModuleIOSim`→`ModuleIO(){}`
   and `Vision` stays real (Limelight has no sim path), but **Shooter, Hood, Intake, Transfer, and
   Index all instantiate the real `XxxIOTalonFX` in every branch**, including `SIM` and `REPLAY`.
   That's a live gap: those mechanisms try to talk to real CAN devices even in physics sim /
   replay. When reviewing or writing `RobotContainer` wiring, check every subsystem's IO choice
   in *all three* branches — don't assume "it compiles" means "it's mode-correct."
2. **"The subsystem exists" ≠ "the subsystem runs."** `Climber`, `ClimberIO`, `ClimberIOTalonFX`
   all exist under `subsystems/climber/`, fully written — but in `RobotContainer` the
   instantiation and field are commented out (`// public final Climber climber;` /
   `// climber = new Climber(...)`). If you're asked to modify climber behavior, check
   `RobotContainer` first; editing `Climber.java` alone does nothing if it isn't wired in.

## AutoLog

- `@AutoLog` goes on the static inner `XxxIOInputs` class inside the `XxxIO` interface.
- The subsystem owns one `XxxIOInputsAutoLogged` instance, refreshed + logged every cycle:
  ```java
  private final IntakeIOInputsAutoLogged inputs = new IntakeIOInputsAutoLogged();
  // in periodic():
  io.updateInputs(inputs);
  Logger.processInputs("Intake/Inputs", inputs);
  ```
- `Logger.recordOutput("Intake/PositionGoal", currentGoal)` logs arbitrary extra state (enums,
  computed doubles) that isn't part of the IO's raw inputs.

## LoggedTunableNumber

`frc.robot.util.debugging.LoggedTunableNumber` (`implements DoubleSupplier`) wraps a
`LoggedNetworkNumber`, gated by `Constants.kTuningMode`:

```java
private static final LoggedTunableNumber kP = new LoggedTunableNumber("Intake/kP", 140.0);
// ... each cycle, or reactively:
LoggedTunableNumber.ifChanged(hashCode(), () -> io.setGains(kP.get(), ...), kP, kI, kD);
```

- Outside tuning mode, `.get()` just returns the hardcoded default — no NT dependency, no
  behavior change on a competition robot with `kTuningMode = false`.
- In tuning mode, the value comes from a NetworkTables entry under `TunableNumbers/...` that a
  dashboard can edit live, without redeploying code.
- This is exactly the write surface Module 5's NT write-boundary whitelists — **only** these
  tunable keys are ever safe to write to over the network; never an actuator output or an enable
  flag.

## The REAL/SIM/REPLAY switch

`Constants.java`:
```java
public static final Mode simMode = Mode.SIM;  // flip by hand for local dev: SIM or REPLAY
public static final Mode currentMode = RobotBase.isReal() ? Mode.REAL : simMode;
public enum Mode { REAL, SIM, REPLAY }
```

Two places read `currentMode`:

1. **`Robot()` constructor** picks the AdvantageKit data receiver/replay source:
   - `REAL` → `WPILOGWriter()` (to a USB stick, `/U/logs`) + `NT4Publisher()`.
   - `SIM` → `NT4Publisher()` only.
   - `REPLAY` → `setUseTiming(false)` (run as fast as possible), `Logger.setReplaySource(new WPILOGReader(logPath))`, and re-log to a `_sim`-suffixed `WPILOGWriter` via `LogFileUtil.addPathSuffix`.
2. **`RobotContainer()` constructor** picks each subsystem's IO implementation per the same
   switch — `XxxIOTalonFX` for `REAL`, `XxxIOSim` for `SIM`, an empty anonymous IO (or a real
   `XxxIOSim` reused, if cheap) for `REPLAY`/default. This is the switch that Module 6's
   sim/replay loop leans on: it never edits subsystem code, it just runs
   `./gradlew simulateJava` with `Constants.simMode` set and a log on the replay path — the same
   Java classes run, only the IO wiring changes.

## PathPlanner deploy layout

Under `src/main/deploy/pathplanner/`:
- `settings.json` — the robot-wide PathPlanner config: `robotMass`, `robotMOI`, `robotTrackwidth`,
  `driveWheelRadius`, `driveGearing`, `maxDriveSpeed`, `driveMotorType`, `driveCurrentLimit`,
  `wheelCOF`, module offsets (`flModuleX/Y`, ...), bumper/robot dimensions. **This is exactly what
  `profile init` parses** to populate a robot profile's `drivetrain:` block — don't hand-duplicate
  these numbers elsewhere, read the profile.
- `paths/*.path` — one file per path. Real schema (from `JAMES1.path`):
  `version` ("2025.0"), `waypoints[{anchor{x,y}, prevControl, nextControl, isLocked, linkedName}]`,
  `rotationTargets[{waypointRelativePos, rotationDegrees}]`,
  `constraintZones[{name, minWaypointRelativePos, maxWaypointRelativePos, constraints{maxVelocity,
  maxAcceleration, maxAngularVelocity, maxAngularAcceleration, nominalVoltage, unlimited}}]`,
  `pointTowardsZones`, `eventMarkers[{name, waypointRelativePos, endWaypointRelativePos, command}]`
  (fires named commands at a point along the path), `globalConstraints`, `goalEndState{velocity,
  rotation}`, `idealStartingState{velocity, rotation}`, `reversed`, `folder`, `useDefaultConstraints`.
  Units are meters / m·s⁻¹ / degrees.
- `autos/*.auto` — compose named paths + commands into a full autonomous routine (what the driver
  station auto chooser actually selects).
- `navgrid.json` — the obstacle grid `LocalADStarAK` pathfinds over.
- `frc.robot.util.LocalADStarAK` — the AdvantageKit-aware wrapper around PathPlanner's
  `LocalADStar` on-the-fly pathfinder, so dynamic pathfinding still logs deterministically for
  replay.

The MCP `write-layer` tools (`pathplanner_show`/`pathplanner_fudge`/`pathplanner_set_speed`)
operate on exactly this `.path` JSON — see `skills/frc-copilot-usage/SKILL.md` for how to use
them.

## Plan-review checklist

Run this over **every** proposed diff to robot code — a subsystem change, a `RobotContainer`
rewire, a new command — before it's applied, whether you're doing a small edit or reviewing a
scaffolded/agent-generated subsystem:

1. **Touches CAN IDs?** Cross-check the new/changed ID against `profile_show`'s device map for
   collisions and against entries flagged `accurate: false` (unverified CAN IDs the profile
   generator couldn't confirm — review those first, don't build on top of them).
2. **Units checked?** WPILib math defaults to radians/meters/seconds. CTRE Phoenix 6 native units
   are **rotations** (`Units.rotationsToRadians`/`radiansToRotations`, plus a
   `SensorToMechanismRatio`/gearing constant to go from motor rotations to mechanism units).
   PathPlanner units are meters, m/s, and degrees. A missed conversion turns a "small tweak" into
   a 57× (radian↔degree) or gearing-ratio-sized error.
3. **Noop/absent-IO handled?** Does *every* mode branch in `RobotContainer` — `REAL`, `SIM`,
   `REPLAY` — get an IO implementation appropriate to that mode (hardware / physics-sim /
   no-op), and not silently fall back to the real hardware IO in `SIM`/`REPLAY` (the exact gap
   Echo currently has for Shooter/Hood/Intake/Transfer/Index — see above)?
4. **Within current limits?** Any new or changed motor config sets explicit stator **and** supply
   current limits (the 2026 energy-management meta — missing or too-generous limits are how
   breakers pop and brownouts happen). Cross-check against `profile_show`'s
   `driveCurrentLimitA`/subsystem limits rather than picking a number from thin air.
5. **Actually wired in?** Is the subsystem instantiated in `RobotContainer` and its commands
   bound, or does it exist in source but sit unused (like the commented-out `Climber`)? A diff
   that only touches `Foo.java` with no `RobotContainer` change is a signal to check whether `Foo`
   is even reachable.
