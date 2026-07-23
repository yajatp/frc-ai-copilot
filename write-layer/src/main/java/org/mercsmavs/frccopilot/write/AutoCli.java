package org.mercsmavs.frccopilot.write;

import java.nio.file.Path;
import java.util.List;

/**
 * CLI for the PathPlanner {@code .auto} write layer. Edits are DRY-RUN by default (print the diff); a
 * change is only written when an explicit output file is given — never overwriting the original in
 * place. Mirrors {@link PathCli}'s behavior for {@code .path} files.
 *
 * <pre>
 *   show       &lt;file.auto&gt;
 *   list-paths &lt;file.auto&gt;
 *   swap-path  &lt;file.auto&gt; &lt;oldName&gt; &lt;newName&gt; [out.auto]
 * </pre>
 */
public final class AutoCli {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            usage();
            System.exit(2);
            return;
        }
        String cmd = args[0];
        AutoFile auto = AutoFile.load(Path.of(args[1]));

        switch (cmd) {
            case "show" -> System.out.print(auto.show());
            case "list-paths" -> {
                List<String> paths = auto.listPathReferences();
                System.out.println("paths (" + paths.size() + "):");
                for (String p : paths) {
                    System.out.println("  - " + p);
                }
            }
            case "swap-path" -> {
                if (args.length < 4) {
                    usage();
                    System.exit(2);
                    return;
                }
                String oldName = args[2];
                String newName = args[3];
                AutoFile edited = auto.copy();
                int n = edited.replacePathReference(oldName, newName);
                applyOrDryRun(
                        auto,
                        edited,
                        args.length > 4 ? args[4] : null,
                        String.format(
                                "Swap path reference \"%s\" -> \"%s\" (%d occurrence%s)",
                                oldName, newName, n, n == 1 ? "" : "s"));
            }
            default -> {
                usage();
                System.exit(2);
            }
        }
    }

    private static void applyOrDryRun(AutoFile before, AutoFile after, String out, String title)
            throws Exception {
        List<PathDiff.Change> changes = PathDiff.diff(before.root(), after.root());
        System.out.println("Proposed: " + title);
        if (changes.isEmpty()) {
            System.out.println("  (no change)");
            return;
        }
        for (PathDiff.Change c : changes) {
            System.out.println("  " + c);
        }
        if (out == null) {
            System.out.println("\nDry run (no output file given). Re-run with an out path to write a copy.");
        } else {
            after.save(Path.of(out));
            System.out.println("\nWrote edited auto to: " + out);
        }
    }

    private static void usage() {
        System.err.println(
                """
                frc-ai-copilot pathplanner auto writer
                usage:
                  show       <file.auto>
                  list-paths <file.auto>
                  swap-path  <file.auto> <oldName> <newName> [out.auto]
                """);
    }

    private AutoCli() {}
}
