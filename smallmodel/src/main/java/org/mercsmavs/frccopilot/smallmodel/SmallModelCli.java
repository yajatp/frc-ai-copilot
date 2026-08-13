package org.mercsmavs.frccopilot.smallmodel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.mercsmavs.frccopilot.ingest.WpilogReader;

/**
 * CLI for the small-model trainer.
 *
 * <pre>
 *   train &lt;log.wpilog&gt; &lt;model.json&gt; --signals a,b,c --positives t1,t2,...
 *                                    [--negatives t1,...] [--stride N] [--threshold T]
 *                                    [--epochs N] [--lr R] [--name N]
 *   show  &lt;model.json&gt;
 *   score &lt;model.json&gt; &lt;log.wpilog&gt; [--top N]
 * </pre>
 *
 * <p>Timestamps are in seconds (of log time), which is what a person reads off AdvantageScope;
 * they are converted to the log's microseconds internally.
 */
public final class SmallModelCli {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            usage();
            System.exit(2);
            return;
        }
        try {
            switch (args[0]) {
                case "train" -> train(args);
                case "show" -> System.out.print(SavedModel.load(Path.of(args[1])).renderSummary());
                case "score" -> score(args);
                default -> {
                    usage();
                    System.exit(2);
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Refused: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void train(String[] args) throws Exception {
        String logPath = args[1];
        Path out = Path.of(args[2]);
        List<String> signals = csv(required(args, "--signals"));
        List<Long> positives = micros(csv(required(args, "--positives")));
        List<Long> negatives = micros(csv(flag(args, "--negatives", "")));
        int stride = Integer.parseInt(flag(args, "--stride", "10"));
        double threshold = Double.parseDouble(flag(args, "--threshold", "0.5"));
        int epochs = Integer.parseInt(flag(args, "--epochs", "3000"));
        double lr = Double.parseDouble(flag(args, "--lr", "0.1"));
        String name = flag(args, "--name", stripExtension(out.getFileName().toString()));

        WpilogReader reader = new WpilogReader(logPath);
        Dataset dataset = LogFeatures.build(reader, signals, positives, negatives, stride);
        LogisticModel model = LogisticModel.train(dataset, epochs, lr);
        LogisticModel.Eval eval = model.evaluate(dataset.x(), dataset.y(), threshold);
        SavedModel saved = SavedModel.of(
                name, "Trained from " + logPath, dataset, model, threshold, eval);
        saved.save(out);

        System.out.print(saved.renderSummary());
        System.out.println("Wrote " + out);
        // These metrics are on the training set, so say so rather than letting them read as held-out
        // performance. With a few dozen hand marks there is not enough data to hold any out.
        System.out.println("Note: metrics above are on the TRAINING set — they show the model fit the"
                + " marks, not that it generalizes. Validate it against a different log"
                + " (`score`) or in replay before trusting it on the robot.");
    }

    private static void score(String[] args) throws Exception {
        SavedModel model = SavedModel.load(Path.of(args[1]));
        WpilogReader reader = new WpilogReader(args[2]);
        int top = Integer.parseInt(flag(args, "--top", "10"));

        List<TreeMap<Long, Double>> timelines = LogFeatures.timelines(reader, model.features);
        record Scored(long ts, double p) {}
        List<Scored> all = new ArrayList<>();
        int fired = 0;
        for (long ts : timelines.get(0).navigableKeySet()) {
            double p = model.probability(LogFeatures.featuresAt(timelines, ts));
            all.add(new Scored(ts, p));
            if (p >= model.threshold) {
                fired++;
            }
        }
        all.sort((a, b) -> Double.compare(b.p(), a.p()));

        System.out.printf("model %s over %s%n", model.name, args[2]);
        System.out.printf("fired at %d of %d sampled timestamps (threshold %.2f)%n",
                fired, all.size(), model.threshold);
        System.out.println("highest-scoring moments:");
        for (int i = 0; i < Math.min(top, all.size()); i++) {
            System.out.printf("  %8.3f s  p=%.4f%n", all.get(i).ts() / 1_000_000.0, all.get(i).p());
        }
    }

    private static List<String> csv(String s) {
        List<String> out = new ArrayList<>();
        for (String part : s.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private static List<Long> micros(List<String> seconds) {
        List<Long> out = new ArrayList<>(seconds.size());
        for (String s : seconds) {
            out.add((long) (Double.parseDouble(s) * 1_000_000));
        }
        return out;
    }

    private static String required(String[] args, String flag) {
        String v = flag(args, flag, null);
        if (v == null) {
            throw new IllegalArgumentException(flag + " is required");
        }
        return v;
    }

    private static String flag(String[] args, String flag, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(flag)) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static void usage() {
        System.err.println(
                """
                frc-ai-copilot small-model trainer
                Learns a tiny logistic classifier from a few human-marked moments in a log — the
                "big AI trains a small AI" technique. Timestamps are in SECONDS of log time.

                usage:
                  train <log.wpilog> <model.json> --signals a,b,c --positives 4.2,7.8,...
                        [--negatives ...] [--stride N] [--threshold T] [--epochs N] [--lr R] [--name N]
                  show  <model.json>
                  score <model.json> <log.wpilog> [--top N]

                  --signals    feature signal names from the log (see `core-ingest entries`)
                  --positives  moments you marked as the positive class
                  --negatives  explicit negatives; omitted, they are drawn from the rest of the log
                  --stride     when drawing negatives, take every Nth sample (default 10)
                  --threshold  decision threshold (default 0.5; raise it to cut false positives)
                """);
    }

    private SmallModelCli() {}
}
