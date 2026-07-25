package org.mercsmavs.frccopilot.smallmodel;

/**
 * A tiny logistic-regression classifier — the "small AI" a larger agent trains from a handful of
 * human-labeled log examples (254's technique: e.g. learn the optimal moment to stage balls from
 * ~30 hand-marked timestamps, then run it on the robot). Deliberately minimal and dependency-free so
 * it can be trained on a laptop in milliseconds and the weights inspected.
 */
public final class LogisticModel {

    private final double[] weights; // last element is the bias
    private final int nFeatures;

    private LogisticModel(double[] weights, int nFeatures) {
        this.weights = weights;
        this.nFeatures = nFeatures;
    }

    /** Train via batch gradient descent on standardized-ish features. */
    public static LogisticModel train(double[][] x, int[] y, int epochs, double learningRate) {
        int n = x.length;
        int f = x[0].length;
        double[] w = new double[f + 1];
        for (int epoch = 0; epoch < epochs; epoch++) {
            double[] grad = new double[f + 1];
            for (int i = 0; i < n; i++) {
                double p = sigmoid(dot(w, x[i]));
                double err = p - y[i];
                for (int j = 0; j < f; j++) {
                    grad[j] += err * x[i][j];
                }
                grad[f] += err;
            }
            for (int j = 0; j <= f; j++) {
                w[j] -= learningRate * grad[j] / n;
            }
        }
        return new LogisticModel(w, f);
    }

    public double probability(double[] features) {
        return sigmoid(dot(weights, features));
    }

    public boolean predict(double[] features, double threshold) {
        return probability(features) >= threshold;
    }

    public double[] weights() {
        return weights.clone();
    }

    /** Precision/recall/accuracy of this model on a labeled set at a given decision threshold. */
    public Eval evaluate(double[][] x, int[] y, double threshold) {
        int tp = 0;
        int fp = 0;
        int tn = 0;
        int fn = 0;
        for (int i = 0; i < x.length; i++) {
            boolean pred = predict(x[i], threshold);
            boolean actual = y[i] == 1;
            if (pred && actual) tp++;
            else if (pred) fp++;
            else if (actual) fn++;
            else tn++;
        }
        double precision = tp + fp == 0 ? 1 : (double) tp / (tp + fp);
        double recall = tp + fn == 0 ? 1 : (double) tp / (tp + fn);
        double accuracy = (double) (tp + tn) / x.length;
        return new Eval(precision, recall, accuracy, tp, fp, tn, fn);
    }

    public record Eval(double precision, double recall, double accuracy, int tp, int fp, int tn, int fn) {
        public double f1() {
            return precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
        }
    }

    private static double dot(double[] w, double[] x) {
        double s = w[w.length - 1]; // bias is the last weight
        for (int j = 0; j < x.length; j++) {
            s += w[j] * x[j];
        }
        return s;
    }

    private static double sigmoid(double z) {
        return 1.0 / (1.0 + Math.exp(-z));
    }
}
