package org.mercsmavs.frccopilot.simreplay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.mercsmavs.frccopilot.ingest.WpilogReader;

/**
 * Turns a failed verification into evidence an agent can act on.
 *
 * <p>{@code FAIL: MAX /Autonomous/BallsScored > 0} tells you a check failed and nothing else. The
 * next question is always the same one a human asks: <em>what did that signal actually do?</em> A
 * signal pinned at zero for the entire window means the mechanism never fired; one that climbed and
 * fell short means a tuning problem; one with no samples at all usually means the log key was
 * renamed, not that the robot misbehaved. Those three failures point at completely different files,
 * so the loop separates them rather than reporting one undifferentiated FAIL.
 */
public record Diagnosis(List<Finding> findings) {

    /** What kind of failure this is — the distinction that decides where to look next. */
    public enum Kind {
        /** The signal has no samples in the log at all. Usually a name/logging problem. */
        SIGNAL_ABSENT,
        /** The signal exists but has no samples inside the required phase window. */
        NO_SAMPLES_IN_PHASE,
        /** The signal never moved. The mechanism it reflects likely never ran. */
        SIGNAL_CONSTANT,
        /** The signal moved but did not reach the threshold. Usually tuning, not wiring. */
        SHORTFALL,
        /**
         * The signal moved about as much as the baseline did, but the other way. The shape of a
         * sign error — an inverted motor, a negated joystick axis, a swapped controller output —
         * which reads as a shortfall unless the direction is called out.
         */
        POLARITY_REVERSED
    }

    public record Finding(Kind kind, String signal, String detail, List<String> suggestions) {
        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append("  [").append(kind).append("] ").append(signal).append('\n');
            sb.append("      ").append(detail).append('\n');
            for (String s : suggestions) {
                sb.append("      -> ").append(s).append('\n');
            }
            return sb.toString();
        }
    }

    /** No findings — the outcome for a turn that never got as far as checking robot behaviour. */
    public static Diagnosis empty() {
        return new Diagnosis(List.of());
    }

    public boolean isEmpty() {
        return findings.isEmpty();
    }

    public String render() {
        if (findings.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("diagnosis:\n");
        for (Finding f : findings) {
            sb.append(f.render());
        }
        return sb.toString();
    }

    /**
     * Explain every failed assertion in a result.
     *
     * <p>Several assertions on one signal fail together as a rule — "scores non-zero" and "meets the
     * target" are the same defect seen twice — so findings are deduplicated per signal and kind. The
     * agent gets one statement of each distinct problem rather than one per failed check.
     *
     * @param scenario the scenario that was checked (assertions align with {@code result}'s checks)
     * @param result the verification outcome
     * @param run the log that was produced
     */
    public static Diagnosis of(Scenario scenario, Verifier.LoopResult result, WpilogReader run) {
        return of(scenario, result, run, null);
    }

    /**
     * As {@link #of(Scenario, Verifier.LoopResult, WpilogReader)}, with the adopted baseline's
     * summaries for comparison. Some defects are only legible against a known-good run: a signal
     * that swung just as hard the other way is a sign error, but on its own it is indistinguishable
     * from a mechanism that underperformed.
     *
     * @param baselineSummaries baseline signal summaries, or {@code null} when none is adopted
     */
    public static Diagnosis of(
            Scenario scenario,
            Verifier.LoopResult result,
            WpilogReader run,
            Map<String, WpilogReader.NumericSummary> baselineSummaries) {
        List<Finding> findings = new ArrayList<>();
        Map<String, WpilogReader.NumericSummary> summaries = run.numericSummaries();
        Map<String, String> typesByName = signalTypes(run);

        List<Assertion> assertions = scenario.assertions();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (int i = 0; i < assertions.size() && i < result.results().size(); i++) {
            if (result.results().get(i).passed()) {
                continue;
            }
            Finding finding = explain(
                    assertions.get(i), result.results().get(i), summaries, typesByName, baselineSummaries);
            if (seen.add(finding.kind() + "|" + finding.signal())) {
                findings.add(finding);
            }
        }
        if (!findings.isEmpty() && baselineSummaries != null) {
            // Deliberately not deduplicated against the per-assertion findings above. The two say
            // different things: that one names a signal a check asked about, this one names the
            // chain it belongs to. Keying this on its top signal would let a per-assertion finding
            // for the same signal swallow the whole upstream list, which is the useful half.
            Finding reversed = scanForReversedSignals(summaries, baselineSummaries);
            if (reversed != null) {
                findings.add(reversed);
            }
        }
        return new Diagnosis(findings);
    }

    /**
     * Look across the whole run for signals that ran opposite to the baseline.
     *
     * <p>Per-assertion polarity detection only ever inspects the signal a check named, and that is
     * usually an outcome rather than a cause: a negated rotation command shows up as a heading that
     * ended somewhere wrong, and the heading itself wraps, so its mean says nothing. The sign error
     * is visible one step upstream, in the commanded value — which no assertion mentions. So when
     * anything failed and there is a baseline to compare against, sweep for it directly.
     */
    private static Finding scanForReversedSignals(
            Map<String, WpilogReader.NumericSummary> run,
            Map<String, WpilogReader.NumericSummary> baseline) {
        record Flip(String signal, double baseMean, double runMean, double magnitude) {}
        List<Flip> flips = new ArrayList<>();
        for (Map.Entry<String, WpilogReader.NumericSummary> e : run.entrySet()) {
            String name = e.getKey();
            if (isWrappingAngle(name)) {
                continue; // the mean of a wrapping heading is not a direction
            }
            WpilogReader.NumericSummary b = baseline.get(name);
            WpilogReader.NumericSummary r = e.getValue();
            if (b == null || b.count() == 0 || r.count() == 0) {
                continue;
            }
            double bm = b.mean();
            double rm = r.mean();
            if (!Double.isFinite(bm) || !Double.isFinite(rm)
                    || Math.signum(bm) * Math.signum(rm) >= 0) {
                continue;
            }
            double scale = Math.max(Math.abs(bm), Math.abs(rm));
            if (scale <= 1e-6 || Math.min(Math.abs(bm), Math.abs(rm)) < MIRROR_RATIO * scale) {
                continue; // see MIRROR_RATIO
            }
            flips.add(new Flip(name, bm, rm, scale));
        }
        if (flips.size() < MIN_CORROBORATING_SIGNALS) {
            // One signal averaging the other way is what a different trajectory looks like. An
            // inverted output shows up across the chain it drives, so require corroboration.
            return null;
        }
        flips.sort(Comparator.comparingDouble(Flip::magnitude).reversed());
        List<Flip> top = flips.subList(0, Math.min(5, flips.size()));
        StringBuilder detail = new StringBuilder(
                flips.size() + " signals ran roughly equal and opposite to the baseline,"
                        + " largest first:");
        for (Flip f : top) {
            detail.append(String.format("%n        %s  mean %.4f -> %.4f", f.signal(), f.baseMean(),
                    f.runMean()));
        }
        return new Finding(
                Kind.POLARITY_REVERSED,
                top.get(0).signal(),
                detail.toString(),
                List.of(
                        "that pattern usually means a sign error — an inverted motor or encoder, a"
                                + " negated axis, a swapped setpoint — on whichever of these sits"
                                + " furthest upstream. Check that before touching gains. A robot"
                                + " that simply drove a different path can look like this too, so"
                                + " confirm against the code rather than acting on it alone"));
    }

    /**
     * How closely the two means must mirror each other before a sign difference is read as an
     * inversion: the smaller magnitude must be at least this fraction of the larger.
     *
     * <p>A genuinely inverted output produces roughly equal and opposite behaviour. A robot that
     * merely drove somewhere else — detuned gains, a different path — also averages the other way
     * on some signals, but with lopsided magnitudes. Measured against a real inverted-rotation
     * build and a real detuned-gains build, the two separated at about half; this is a heuristic
     * calibrated on a couple of cases, not a law, which is why the finding is worded as evidence
     * to check rather than as a conclusion.
     */
    private static final double MIRROR_RATIO = 0.5;

    /** A lone reversed signal is noise; an inverted output moves everything downstream of it. */
    private static final int MIN_CORROBORATING_SIGNALS = 2;

    /** Headings and azimuths wrap, so averaging them says nothing about direction. */
    private static boolean isWrappingAngle(String name) {
        int split = name.lastIndexOf('/');
        String leaf = split < 0 ? name : name.substring(split + 1);
        return switch (leaf) {
            case "Rotation", "RotationDeg", "Yaw", "YawDeg", "Angle", "AngleDeg" -> true;
            default -> false;
        };
    }

    private static Finding explain(
            Assertion assertion,
            Assertion.Result result,
            Map<String, WpilogReader.NumericSummary> summaries,
            Map<String, String> typesByName,
            Map<String, WpilogReader.NumericSummary> baselineSummaries) {
        String signal = assertion.signal();
        WpilogReader.NumericSummary s = summaries.get(signal);

        if (s == null || s.count() == 0) {
            String loggedType = typesByName.get(signal);
            if (loggedType != null) {
                // The key is in the log, but carries nothing an assertion can compare numerically.
                return new Finding(
                        Kind.SIGNAL_ABSENT,
                        signal,
                        "logged as type '" + loggedType + "', which yields no numeric value",
                        List.of(
                                "assert against a numeric signal, or log this value as a"
                                        + " double/int64/boolean"));
            }
            List<String> similar = similarNames(signal, typesByName.keySet());
            List<String> suggestions = new ArrayList<>();
            if (similar.isEmpty()) {
                suggestions.add(
                        "no similarly-named signal in the log — check that this value is being logged at all");
            } else {
                suggestions.add("did the log key change? closest names present: " + String.join(", ", similar));
            }
            return new Finding(
                    Kind.SIGNAL_ABSENT, signal, "no samples anywhere in the log", suggestions);
        }

        if (result.sampleCount() == 0) {
            // The signal exists, so the assertion's phase window is what excluded everything.
            return new Finding(
                    Kind.NO_SAMPLES_IN_PHASE,
                    signal,
                    String.format(
                            "%d samples in the log, but none while %s == %s",
                            s.count(), assertion.phaseSignal(), assertion.phaseEquals()),
                    List.of(
                            "the phase never occurred, or is spelled differently — verify "
                                    + assertion.phaseSignal() + " actually takes the value '"
                                    + assertion.phaseEquals() + "'"));
        }

        if (s.constant()) {
            return new Finding(
                    Kind.SIGNAL_CONSTANT,
                    signal,
                    String.format(
                            "held constant at %.4f across all %d samples in the log; check wanted %s %s %.4f",
                            s.last(), s.count(), assertion.aggregation(), assertion.op().symbol,
                            assertion.threshold()),
                    List.of(
                            "the value never moved — the code path that changes it likely never"
                                    + " executed (unscheduled command, unmet trigger, or an action"
                                    + " routed somewhere else)"));
        }

        Finding reversed = explainPolarity(assertion, signal, s, baselineSummaries);
        if (reversed != null) {
            return reversed;
        }

        double gap = Math.abs(assertion.threshold() - result.actual());
        // "Short of" is wrong for half of a two-sided endpoint check: a robot that finished a metre
        // past where it should have did not fall short of anything, and saying so sends the reader
        // looking for the opposite defect.
        boolean overshot = switch (assertion.op()) {
            case LT, LE -> true;
            default -> false;
        };
        String advice = "the mechanism ran but "
                + (overshot ? "went past the limit" : "fell short")
                + " — most often gains, timing, or a window that ends too early";
        if (baselineSummaries == null) {
            advice += ". With no baseline adopted a direction error looks the same from here, so"
                    + " check the sign of the output too — or adopt a baseline (`loop baseline`)"
                    + " and re-run, which would tell the two apart";
        }
        return new Finding(
                Kind.SHORTFALL,
                signal,
                String.format(
                        "reached %s=%.4f, %s %.4f by %.4f (ranged %.4f..%.4f over %d samples in scope)",
                        assertion.aggregation(), result.actual(),
                        overshot ? "over" : "short of",
                        assertion.threshold(), gap,
                        s.min(), s.max(), result.sampleCount()),
                List.of(advice));
    }

    /**
     * Recognise a sign error: the signal moved about as far as the baseline did, in the opposite
     * direction. Requires a baseline — against the run alone this is just a number that fell short,
     * which is why an inverted output otherwise gets tuning advice for a wiring problem.
     */
    private static Finding explainPolarity(
            Assertion assertion,
            String signal,
            WpilogReader.NumericSummary run,
            Map<String, WpilogReader.NumericSummary> baselineSummaries) {
        if (baselineSummaries == null || isWrappingAngle(signal)) {
            // Same reason the cross-signal scan skips these: a heading that crosses ±180 has no
            // meaningful mean, so its sign is not evidence of direction. The scan below still
            // catches the real reversal on the rates and positions that drove it.
            return null;
        }
        WpilogReader.NumericSummary base = baselineSummaries.get(signal);
        if (base == null || base.count() == 0 || run.count() == 0) {
            return null;
        }
        double baseMean = base.mean();
        double runMean = run.mean();
        if (!Double.isFinite(baseMean) || !Double.isFinite(runMean)) {
            return null;
        }
        // Opposite signs, and both far enough from zero that the sign is meaningful rather than
        // noise about a signal that simply sits near zero in both runs.
        boolean flipped = Math.signum(baseMean) * Math.signum(runMean) < 0;
        double scale = Math.max(Math.abs(baseMean), Math.abs(runMean));
        boolean comparable = scale > 1e-6
                && Math.min(Math.abs(baseMean), Math.abs(runMean)) > 0.25 * scale;
        if (!flipped || !comparable) {
            return null;
        }
        return new Finding(
                Kind.POLARITY_REVERSED,
                signal,
                String.format(
                        "mean ran %.4f against the baseline's %.4f — same order of magnitude, opposite"
                                + " sign; check wanted %s %s %.4f",
                        runMean, baseMean, assertion.aggregation(), assertion.op().symbol,
                        assertion.threshold()),
                List.of(
                        "the mechanism ran about as hard as the known-good run but the other way —"
                                + " look for a sign error (an inverted motor or encoder, a negated"
                                + " axis, a swapped setpoint) rather than tuning the gains"));
    }

    /** Every signal name in the log with its declared type (a missing key may be a string signal). */
    private static Map<String, String> signalTypes(WpilogReader run) {
        Map<String, String> types = new java.util.LinkedHashMap<>();
        run.index().values().forEach(e -> types.putIfAbsent(e.name, e.type));
        return types;
    }

    /**
     * Candidate names for a signal that is absent, ranked by shared trailing path segment then by
     * shared prefix — the shape of a rename ({@code /Autonomous/BallsScored} to
     * {@code /Auto/BallsScored}) keeps the leaf and changes the prefix.
     */
    static List<String> similarNames(String missing, java.util.Collection<String> present) {
        String leaf = leafOf(missing).toLowerCase();
        return present.stream()
                .filter(name -> !name.equals(missing))
                .map(name -> Map.entry(name, similarity(missing, leaf, name)))
                .filter(e -> e.getValue() > 0)
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed())
                .limit(3)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static int similarity(String missing, String missingLeaf, String candidate) {
        String candidateLeaf = leafOf(candidate).toLowerCase();
        if (candidateLeaf.equals(missingLeaf)) {
            return 100;
        }
        if (candidateLeaf.contains(missingLeaf) || missingLeaf.contains(candidateLeaf)) {
            return 50;
        }
        // Fall back to shared prefix length, which catches a renamed leaf under the same subsystem.
        int shared = 0;
        int limit = Math.min(missing.length(), candidate.length());
        while (shared < limit && missing.charAt(shared) == candidate.charAt(shared)) {
            shared++;
        }
        return shared > 2 ? shared : 0;
    }

    private static String leafOf(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }
}
