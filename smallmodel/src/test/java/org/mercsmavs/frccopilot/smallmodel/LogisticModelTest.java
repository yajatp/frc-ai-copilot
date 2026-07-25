package org.mercsmavs.frccopilot.smallmodel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class LogisticModelTest {

    @Test
    void learnsAStagingMomentFromLabeledExamples() {
        // Toy version of 254's "when to stage balls" problem: feature[0] = balls collected,
        // feature[1] = seconds since intake; label = 1 when it's a good moment (enough balls, not
        // too late). Generate a separable-ish labeled set (the human's ~30 marks).
        Random rng = new Random(7);
        int n = 200;
        double[][] x = new double[n][2];
        int[] y = new int[n];
        for (int i = 0; i < n; i++) {
            double balls = rng.nextDouble() * 5;
            double secs = rng.nextDouble() * 4;
            x[i][0] = balls;
            x[i][1] = secs;
            // "good moment" ~ enough balls (>3) and reasonably prompt (<2.5s)
            y[i] = (balls > 3.0 && secs < 2.5) ? 1 : 0;
        }
        LogisticModel model = LogisticModel.train(x, y, 3000, 0.1);
        LogisticModel.Eval eval = model.evaluate(x, y, 0.5);

        assertTrue(eval.accuracy() > 0.85, () -> "accuracy=" + eval.accuracy());
        assertTrue(eval.f1() > 0.6, () -> "f1=" + eval.f1());

        // A clearly-good moment scores high, a clearly-bad one scores low.
        assertTrue(model.probability(new double[] {4.5, 1.0}) > 0.6);
        assertTrue(model.probability(new double[] {0.5, 3.5}) < 0.4);
    }
}
