package org.mercsmavs.frccopilot.smallmodel;

import java.util.List;

/**
 * A labeled feature matrix plus the per-feature scaling used to train on it.
 *
 * <p>Standardization is part of the dataset rather than an afterthought because the features here
 * come from robot signals with wildly different magnitudes — battery voltage sits near 12.5 while a
 * ball counter sits between 0 and 5. Batch gradient descent on raw columns like that is dominated by
 * the largest-magnitude feature and converges badly at any learning rate that is stable. The mean and
 * standard deviation are therefore kept with the model, so a prediction applies exactly the same
 * transform the training did; a model that standardized while training and then scored raw features
 * would silently produce nonsense.
 */
public record Dataset(
        List<String> featureNames, double[][] x, int[] y, double[] means, double[] stds) {

    public int size() {
        return x.length;
    }

    public int positives() {
        int n = 0;
        for (int label : y) {
            n += label;
        }
        return n;
    }

    /**
     * Build a dataset from raw rows, computing and applying per-column standardization. A
     * zero-variance column is left centered but unscaled — dividing by its zero deviation would
     * yield NaN, and a constant feature carries no information to scale anyway.
     */
    public static Dataset standardized(List<String> featureNames, double[][] raw, int[] labels) {
        if (raw.length == 0) {
            throw new IllegalArgumentException("no examples to train on");
        }
        if (raw.length != labels.length) {
            throw new IllegalArgumentException(
                    "have " + raw.length + " rows but " + labels.length + " labels");
        }
        int f = featureNames.size();
        for (double[] row : raw) {
            if (row.length != f) {
                throw new IllegalArgumentException(
                        "row has " + row.length + " values but " + f + " feature names were given");
            }
        }

        double[] means = new double[f];
        double[] stds = new double[f];
        for (int j = 0; j < f; j++) {
            double sum = 0;
            for (double[] row : raw) {
                sum += row[j];
            }
            means[j] = sum / raw.length;
            double sq = 0;
            for (double[] row : raw) {
                double d = row[j] - means[j];
                sq += d * d;
            }
            stds[j] = Math.sqrt(sq / raw.length);
            if (stds[j] < 1e-12) {
                stds[j] = 1.0;
            }
        }

        double[][] scaled = new double[raw.length][f];
        for (int i = 0; i < raw.length; i++) {
            scaled[i] = apply(raw[i], means, stds);
        }
        return new Dataset(List.copyOf(featureNames), scaled, labels.clone(), means, stds);
    }

    /** Apply a stored scaling to one raw feature vector. */
    public static double[] apply(double[] raw, double[] means, double[] stds) {
        double[] out = new double[raw.length];
        for (int j = 0; j < raw.length; j++) {
            out[j] = (raw[j] - means[j]) / stds[j];
        }
        return out;
    }
}
