package org.mercsmavs.frccopilot.smallmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.util.datalog.DataLogWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mercsmavs.frccopilot.ingest.WpilogReader;

/**
 * Training from a real {@code .wpilog} plus hand-marked moments — the workflow the module exists for.
 *
 * <p>The fixture is the staging problem the technique was described against: balls accumulate in a
 * hopper and the "good moment to shoot" is when enough have been collected but not so long that the
 * window has passed. A real log rather than a synthetic matrix, because the sparse-stream sampling
 * (each signal written at its own cadence) is the part most likely to be wrong.
 */
class LogTrainingTest {

    private static final int CYCLES = 400;

    /** Writes a log where hopper fills 0..5 in a cycle, and the good moment recurs periodically. */
    private static Path stagingLog(Path file) throws IOException {
        try (DataLogWriter log = new DataLogWriter(file.toString())) {
            int hopper = log.start("/Hopper/Balls", "double");
            int since = log.start("/Hopper/SecondsSinceIntake", "double");
            int volts = log.start("/PowerDistribution/Voltage", "double");
            int ticks = log.start("/Drivetrain/EncoderTicks", "double");
            for (int i = 0; i < CYCLES; i++) {
                long ts = 1_000_000L + i * 20_000L;
                int phase = i % 50; // a 1-second collect/shoot cycle
                log.appendDouble(hopper, Math.min(5, phase / 8.0), ts);
                log.appendDouble(since, phase * 0.02, ts);
                log.appendDouble(volts, 12.5 - i * 0.001, ts);
                // Encoder counts, in the tens of thousands — the magnitude gap that makes scaling
                // load-bearing rather than cosmetic.
                log.appendDouble(ticks, 20_000 + phase * 400.0, ts);
            }
            log.flush();
        }
        return file;
    }

    /** The "good moment" marks: hopper full and prompt — phase 40 of each 50-cycle period. */
    private static List<Long> marks() {
        List<Long> ts = new java.util.ArrayList<>();
        for (int i = 40; i < CYCLES; i += 50) {
            ts.add(1_000_000L + i * 20_000L);
        }
        return ts;
    }

    private static final List<String> SIGNALS =
            List.of("/Hopper/Balls", "/Hopper/SecondsSinceIntake");

    @Test
    void learnsTheStagingMomentFromMarkedTimestampsInARealLog(@TempDir Path tmp) throws Exception {
        WpilogReader reader = new WpilogReader(stagingLog(tmp.resolve("practice.wpilog")).toString());
        Dataset dataset = LogFeatures.build(reader, SIGNALS, marks(), List.of(), 10);

        assertEquals(SIGNALS, dataset.featureNames());
        assertEquals(marks().size(), dataset.positives(), "every mark should be a positive example");
        assertTrue(dataset.size() > dataset.positives(),
                "negatives should have been drawn from the rest of the log");

        LogisticModel model = LogisticModel.train(dataset, 4000, 0.2);
        LogisticModel.Eval eval = model.evaluate(dataset.x(), dataset.y(), 0.5);
        assertTrue(eval.recall() > 0.8, () -> "recall=" + eval.recall());
        assertTrue(eval.accuracy() > 0.9, () -> "accuracy=" + eval.accuracy());
    }

    @Test
    void standardizationIsWhatMakesTrainingConvergeOnRealSignalMagnitudes(@TempDir Path tmp)
            throws Exception {
        // Encoder counts in the tens of thousands alongside a 0..5 hopper counter is the realistic
        // case. Trained on raw columns, gradient descent is dominated by the large-magnitude feature
        // and does not separate the classes at all; standardized, the same data and the same
        // hyperparameters do. This is why Dataset owns the scaling instead of leaving it to callers.
        WpilogReader reader = new WpilogReader(stagingLog(tmp.resolve("practice.wpilog")).toString());
        List<String> withVoltage = List.of(
                "/Hopper/Balls", "/Hopper/SecondsSinceIntake", "/Drivetrain/EncoderTicks");
        Dataset scaled = LogFeatures.build(reader, withVoltage, marks(), List.of(), 10);

        LogisticModel onScaled = LogisticModel.train(scaled, 4000, 0.2);
        double scaledF1 = onScaled.evaluate(scaled.x(), scaled.y(), 0.5).f1();

        // The same rows before scaling was applied: undo it to recover them.
        double[][] raw = new double[scaled.size()][];
        for (int i = 0; i < scaled.size(); i++) {
            raw[i] = new double[withVoltage.size()];
            for (int j = 0; j < withVoltage.size(); j++) {
                raw[i][j] = scaled.x()[i][j] * scaled.stds()[j] + scaled.means()[j];
            }
        }
        LogisticModel onRaw = LogisticModel.train(raw, scaled.y(), 4000, 0.2);
        double rawF1 = onRaw.evaluate(raw, scaled.y(), 0.5).f1();

        assertTrue(scaledF1 > rawF1,
                () -> "standardized f1=" + scaledF1 + " should beat raw f1=" + rawF1);
    }

    @Test
    void aSavedModelScoresIdenticallyToTheOneThatWasTrained(@TempDir Path tmp) throws Exception {
        // The round trip that matters: train and predict are separate tool calls, so a model that
        // scored differently after saving would be quietly useless.
        WpilogReader reader = new WpilogReader(stagingLog(tmp.resolve("practice.wpilog")).toString());
        Dataset dataset = LogFeatures.build(reader, SIGNALS, marks(), List.of(), 10);
        LogisticModel model = LogisticModel.train(dataset, 4000, 0.2);
        LogisticModel.Eval eval = model.evaluate(dataset.x(), dataset.y(), 0.5);

        Path file = tmp.resolve("model.json");
        SavedModel.of("staging", "d", dataset, model, 0.5, eval).save(file);
        SavedModel loaded = SavedModel.load(file);

        assertEquals(SIGNALS, loaded.features);
        assertEquals(marks().size(), loaded.trainingPositives);
        // Raw features in, standardization applied from the stored means/stds.
        double[] good = {5.0, 0.80};
        double[] bad = {0.2, 0.02};
        assertEquals(
                model.probability(Dataset.apply(good, dataset.means(), dataset.stds())),
                loaded.probability(good), 1e-12);
        assertTrue(loaded.probability(good) > loaded.probability(bad),
                "a full hopper should score above an empty one");
    }

    @Test
    void refusesAFeatureSignalThatIsNotInTheLog(@TempDir Path tmp) throws Exception {
        WpilogReader reader = new WpilogReader(stagingLog(tmp.resolve("practice.wpilog")).toString());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LogFeatures.build(reader, List.of("/Nope/Missing"), marks(), List.of(), 10));
        assertTrue(e.getMessage().contains("/Nope/Missing"), e.getMessage());
    }

    @Test
    void refusesToTrainWithNoMarks(@TempDir Path tmp) throws Exception {
        WpilogReader reader = new WpilogReader(stagingLog(tmp.resolve("practice.wpilog")).toString());
        assertThrows(IllegalArgumentException.class,
                () -> LogFeatures.build(reader, SIGNALS, List.of(), List.of(), 10));
    }

    @Test
    void rejectsASavedModelWhoseWeightsDoNotMatchItsFeatures(@TempDir Path tmp) throws Exception {
        // A hand-edited or truncated model file must fail loudly, not score confidently on the wrong
        // number of inputs.
        Path file = tmp.resolve("bad.json");
        java.nio.file.Files.writeString(file, """
                {"name":"bad","features":["a","b"],"weights":[1.0],
                 "means":[0.0,0.0],"stds":[1.0,1.0]}
                """);
        IOException e = assertThrows(IOException.class, () -> SavedModel.load(file));
        assertTrue(e.getMessage().contains("weights"), e.getMessage());
    }

    @Test
    void scoringWithTheWrongFeatureCountIsAnError(@TempDir Path tmp) throws Exception {
        WpilogReader reader = new WpilogReader(stagingLog(tmp.resolve("practice.wpilog")).toString());
        Dataset dataset = LogFeatures.build(reader, SIGNALS, marks(), List.of(), 10);
        LogisticModel model = LogisticModel.train(dataset, 100, 0.2);
        SavedModel saved = SavedModel.of("m", "d", dataset, model,
                0.5, model.evaluate(dataset.x(), dataset.y(), 0.5));

        assertThrows(IllegalArgumentException.class, () -> saved.probability(new double[] {1.0}));
    }

    @Test
    void aConstantFeatureDoesNotProduceNaN(@TempDir Path tmp) throws Exception {
        // Zero variance means no standard deviation to divide by; the column must be left alone
        // rather than turning every row into NaN and every prediction into garbage.
        Dataset dataset = Dataset.standardized(
                List.of("constant", "varying"),
                new double[][] {{7.0, 1.0}, {7.0, 2.0}, {7.0, 3.0}, {7.0, 4.0}},
                new int[] {0, 0, 1, 1});
        for (double[] row : dataset.x()) {
            for (double v : row) {
                assertTrue(Double.isFinite(v), "standardized values must stay finite");
            }
        }
        assertEquals(1.0, dataset.stds()[0], 1e-12, "a constant column keeps a unit scale");
    }
}
