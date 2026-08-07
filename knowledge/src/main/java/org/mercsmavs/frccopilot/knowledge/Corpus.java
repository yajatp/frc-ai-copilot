package org.mercsmavs.frccopilot.knowledge;

import java.util.List;

/**
 * The documentation corpora the copilot knows how to fetch and index.
 *
 * <p>Only repositories that are actually public and actually contain prose are listed. REV's
 * documentation is deliberately absent: it is published at docs.revrobotics.com with no public
 * source repository, so it cannot be cloned. Point {@code index} at a local copy instead.
 *
 * @param name corpus label used by {@code --source} filters and stored on every chunk
 * @param repo git URL to clone
 * @param subdir path within the repo that holds the docs, or "" for the repo root
 * @param urlBase canonical docs site, used to turn a file path back into a citable link
 */
public record Corpus(String name, String repo, String subdir, String urlBase, String description) {

    public static final List<Corpus> KNOWN = List.of(
            new Corpus(
                    "wpilib",
                    "https://github.com/wpilibsuite/frc-docs.git",
                    "source",
                    "https://docs.wpilib.org/en/stable",
                    "WPILib official documentation (frc-docs)"),
            new Corpus(
                    "ctre",
                    "https://github.com/CrossTheRoadElec/Phoenix6-Documentation.git",
                    "docs/source",
                    "https://v6.docs.ctr-electronics.com/en/stable",
                    "CTRE Phoenix 6 documentation"),
            new Corpus(
                    "photonvision",
                    "https://github.com/PhotonVision/photonvision.git",
                    "docs/source",
                    "https://docs.photonvision.org/en/latest",
                    "PhotonVision documentation"),
            new Corpus(
                    "pathplanner",
                    "https://github.com/mjansen4857/pathplanner.git",
                    "",
                    "https://pathplanner.dev",
                    "PathPlanner documentation and README material"));

    public static Corpus byName(String name) {
        return KNOWN.stream()
                .filter(c -> c.name().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown corpus '" + name + "' (known: " + names() + ")"));
    }

    public static String names() {
        return String.join(", ", KNOWN.stream().map(Corpus::name).toList());
    }
}
