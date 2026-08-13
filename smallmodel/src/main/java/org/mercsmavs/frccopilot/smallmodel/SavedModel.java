package org.mercsmavs.frccopilot.smallmodel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A trained model on disk: weights, the feature signals they apply to, the scaling used at training
 * time, and the decision threshold chosen for it.
 *
 * <p>Persistence is what makes this usable from a request/response tool at all — training and
 * predicting are separate calls, so a model that only existed in memory would be gone before anything
 * could score with it. It is deliberately plain JSON: a few dozen numbers a person can read, diff, and
 * check into the robot repo, which is the whole appeal of a small model over a large one.
 *
 * <p>The feature names are stored with the weights so scoring cannot silently use a different signal
 * order than training did — a reordering would produce confident, wrong answers rather than an error.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class SavedModel {

    private static final ObjectMapper JSON = new ObjectMapper();

    public String name;
    public String description;
    /** Feature signals, in the order the weights expect them. */
    public List<String> features;
    /** Trained weights; the last element is the bias. */
    public double[] weights;
    /** Per-feature standardization captured at training time. */
    public double[] means;
    public double[] stds;
    /** Decision threshold this model was evaluated at. */
    public double threshold = 0.5;
    /** Training-set metrics, recorded so a later reader knows what it is trusting. */
    public double precision;
    public double recall;
    public double accuracy;
    public double f1;
    public int trainingExamples;
    public int trainingPositives;

    public static SavedModel of(
            String name,
            String description,
            Dataset dataset,
            LogisticModel model,
            double threshold,
            LogisticModel.Eval eval) {
        SavedModel saved = new SavedModel();
        saved.name = name;
        saved.description = description;
        saved.features = dataset.featureNames();
        saved.weights = model.weights();
        saved.means = dataset.means();
        saved.stds = dataset.stds();
        saved.threshold = threshold;
        saved.precision = eval.precision();
        saved.recall = eval.recall();
        saved.accuracy = eval.accuracy();
        saved.f1 = eval.f1();
        saved.trainingExamples = dataset.size();
        saved.trainingPositives = dataset.positives();
        return saved;
    }

    public static SavedModel load(Path file) throws IOException {
        SavedModel model = JSON.readValue(Files.readString(file), SavedModel.class);
        if (model.features == null || model.weights == null || model.means == null || model.stds == null) {
            throw new IOException(file + ": not a complete saved model (needs features, weights,"
                    + " means and stds)");
        }
        if (model.weights.length != model.features.size() + 1) {
            throw new IOException(file + ": has " + model.features.size() + " features but "
                    + model.weights.length + " weights (expected features+1 for the bias)");
        }
        return model;
    }

    public void save(Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(this) + "\n");
    }

    /** Score one raw (unscaled) feature vector, applying the stored standardization first. */
    public double probability(double[] rawFeatures) {
        if (rawFeatures.length != features.size()) {
            throw new IllegalArgumentException(
                    "model expects " + features.size() + " features " + features + " but got "
                            + rawFeatures.length);
        }
        return LogisticModel.of(weights, features.size())
                .probability(Dataset.apply(rawFeatures, means, stds));
    }

    public boolean predict(double[] rawFeatures) {
        return probability(rawFeatures) >= threshold;
    }

    /** Weight per feature, largest magnitude first — what the model actually keyed on. */
    public String renderWeights() {
        StringBuilder sb = new StringBuilder();
        List<Integer> order = new java.util.ArrayList<>();
        for (int i = 0; i < features.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> Double.compare(Math.abs(weights[b]), Math.abs(weights[a])));
        for (int i : order) {
            // Weights are on standardized features, so they are comparable across signals: each is
            // the log-odds shift per standard deviation of that signal.
            sb.append(String.format("  %+.4f  %s%n", weights[i], features.get(i)));
        }
        sb.append(String.format("  %+.4f  (bias)%n", weights[weights.length - 1]));
        return sb.toString();
    }

    public String renderSummary() {
        return String.format(
                "model: %s%n%s"
                        + "trained on %d examples (%d positive) | threshold %.2f%n"
                        + "precision %.3f  recall %.3f  accuracy %.3f  f1 %.3f%n"
                        + "weights (log-odds per standard deviation):%n%s",
                name == null ? "(unnamed)" : name,
                description == null || description.isBlank() ? "" : description + "\n",
                trainingExamples, trainingPositives, threshold,
                precision, recall, accuracy, f1, renderWeights());
    }
}
