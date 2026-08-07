---
name: frc-photonvision
description: Working with PhotonVision (and Limelight-style AprilTag pipelines) in FRC — camera calibration, pose estimation, multi-tag ambiguity, latency compensation, and fusing vision with wheel odometry. Load this when writing or reviewing vision code, when pose estimates jump or drift, or when diagnosing detection dropouts from a match log.
---

# PhotonVision / AprilTag pose estimation

**Verify API specifics with `search_docs source=photonvision`.** The pose-estimation API and the
result/target class names have changed across seasons.

## The mental model that matters

1. **A vision measurement is a timestamped observation, not a current position.** By the time the
   roboRIO sees a frame, the robot has moved. Every serious vision integration is really a
   latency-compensation problem.
2. **Vision corrects odometry; it does not replace it.** Wheel odometry is smooth but drifts.
   Vision is drift-free but noisy and intermittent. A pose estimator fuses them. Code that
   snaps the pose directly to the latest vision reading will produce a robot that visibly jerks.
3. **A single tag gives an ambiguous pose.** One AprilTag can be consistent with two different
   camera poses. Multi-tag observations resolve this; single-tag observations must be treated
   with suspicion, especially at distance and at shallow angles.

## Pitfalls that actually cost matches

**Trusting a high-ambiguity single-tag reading.** This is the classic failure: the robot's pose
estimate flips to a mirrored position, autonomous drives into a wall. Reject readings above an
ambiguity threshold, and prefer multi-tag results whenever available.

**Not rejecting implausible jumps.** A vision pose that disagrees with wheel odometry by more than
the robot could physically have moved since the last update is wrong — discard it rather than
fusing it. This single guard prevents most catastrophic vision failures.

**Feeding vision a wrong timestamp.** If the measurement timestamp is the time you processed the
result rather than the time the frame was captured, the estimator fuses stale data as though it
were current, and the pose lags or oscillates.

**Bad or stale camera calibration.** Pose accuracy is bounded by calibration quality. A camera
that was re-mounted, re-focused, or replaced needs re-calibration; the symptom is a pose that is
consistently offset rather than noisy.

**Wrong camera-to-robot transform.** Every pose estimate is relative to the camera. An incorrect
mounting transform produces a confidently wrong robot pose. Measure it, do not estimate it.

**Uniform trust regardless of conditions.** Standard deviations should scale with distance and tag
count — a two-tag reading at 1.5 m deserves far more weight than a one-tag reading at 5 m.

## Diagnosing vision from a match log

```
vision_analysis  file=<match.wpilog>                       # detection rate + dropout count
log_entries      file=<match.wpilog> filter=vision         # what the pipeline actually published
signal_stats     file=<match.wpilog> entry=<latency signal>
data_quality     file=<match.wpilog> entry=<pose x signal>  # is this even sampled well enough?
find_peaks       file=<match.wpilog> entry=<ambiguity signal> minProminence=0.2
rate_of_change   file=<match.wpilog> entry=<pose x signal>  # sudden jumps = bad fusion
correlate        file=<match.wpilog> entry=<angular velocity> entryB=<hasTarget>
```

Reading the results:

- **Detection rate below ~50%** — check exposure and pipeline settings before touching code.
  Dropouts that correlate with high angular velocity are motion blur; slow the rotation or shorten
  exposure.
- **`rate_of_change` on pose showing large instantaneous slopes** — the estimator is accepting
  jumps it should reject. Add a plausibility gate.
- **Ambiguity peaks aligned with pose jumps** — single-tag ambiguity is flipping the pose. Raise
  the rejection threshold and prefer multi-tag.
- **High, variable latency** — the coprocessor is overloaded; lower resolution or frame rate.
  This also shows up as loop overruns in `loop_timing` if processing happens on the roboRIO.

## What to log so this is diagnosable at all

The copilot can only analyze signals your robot recorded. At minimum, log per frame: `hasTarget`,
tag count, pose ambiguity, capture-to-processed latency, the raw vision pose, and the fused pose.
Without ambiguity and latency, most vision failures are unfalsifiable after the fact.

## Related

- Roadmap Horizon 3 adds dedicated vision primitives (multi-tag ambiguity spikes, latency jitter,
  pose-jump severity vs. wheel odometry). Until then the general primitives above cover it.
- `skills/frc-copilot-usage/SKILL.md` — tool sequencing
