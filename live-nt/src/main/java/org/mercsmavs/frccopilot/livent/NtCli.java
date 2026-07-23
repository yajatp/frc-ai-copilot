package org.mercsmavs.frccopilot.livent;

import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.networktables.NetworkTableValue;
import java.util.Set;

/**
 * Command-line entry point for Module 5 (live NetworkTables read access).
 *
 * <p>This CLI is read-only: it connects as an NT4 client and observes values. It has no write
 * command, by design &mdash; writing is only ever done through {@link NtWriteGuard}.
 *
 * <pre>
 *   monitor &lt;host&gt; [seconds]   connect and print value changes for `seconds` (default 10)
 *   get     &lt;host&gt; &lt;key&gt;       connect and print the current value of a single key
 * </pre>
 */
public final class NtCli {

    private static final String IDENTITY = "frc-ai-copilot";
    private static final double CONNECT_TIMEOUT_SECONDS = 5.0;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            usage();
            System.exit(2);
            return;
        }

        switch (args[0]) {
            case "monitor" -> monitor(args);
            case "get" -> get(args);
            default -> {
                usage();
                System.exit(2);
            }
        }
    }

    private static void monitor(String[] args) throws InterruptedException {
        if (args.length < 2) {
            usage();
            System.exit(2);
            return;
        }
        String host = args[1];
        double seconds = args.length > 2 ? Double.parseDouble(args[2]) : 10.0;

        try (NtClient client = new NtClient()) {
            client.connect(IDENTITY, host, NetworkTableInstance.kDefaultPort4);
            System.out.println("Connecting to " + host + " ...");
            if (!client.waitForConnection(CONNECT_TIMEOUT_SECONDS)) {
                System.out.println(
                        "(not yet connected after " + CONNECT_TIMEOUT_SECONDS + "s - still watching for changes)");
            } else {
                System.out.println("Connected. Watching for value changes for " + seconds + "s ...");
            }

            try (AutoCloseable monitorHandle =
                    client.monitor(
                            Set.of(),
                            (key, value) -> System.out.println(formatChange(key, value)))) {
                Thread.sleep((long) (seconds * 1000));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void get(String[] args) {
        if (args.length < 3) {
            usage();
            System.exit(2);
            return;
        }
        String host = args[1];
        String key = args[2];

        try (NtClient client = new NtClient()) {
            client.connect(IDENTITY, host, NetworkTableInstance.kDefaultPort4);
            if (!client.waitForConnection(CONNECT_TIMEOUT_SECONDS)) {
                System.out.println("(warning: not connected to " + host + " after " + CONNECT_TIMEOUT_SECONDS + "s)");
            }
            NetworkTableValue value = client.getValue(key);
            if (!value.isValid()) {
                System.out.println(key + " = <no value>");
                return;
            }
            System.out.println(formatChange(key, value));
        }
    }

    private static String formatChange(String key, NetworkTableValue value) {
        String rendered =
                switch (value.getType()) {
                    case kBoolean -> String.valueOf(value.getBoolean());
                    case kDouble -> String.valueOf(value.getDouble());
                    case kFloat -> String.valueOf(value.getFloat());
                    case kInteger -> String.valueOf(value.getInteger());
                    case kString -> value.getString();
                    case kBooleanArray -> java.util.Arrays.toString(value.getBooleanArray());
                    case kDoubleArray -> java.util.Arrays.toString(value.getDoubleArray());
                    case kFloatArray -> java.util.Arrays.toString(value.getFloatArray());
                    case kIntegerArray -> java.util.Arrays.toString(value.getIntegerArray());
                    case kStringArray -> java.util.Arrays.toString(value.getStringArray());
                    case kRaw -> "<" + value.getRaw().length + " raw bytes>";
                    case kUnassigned -> "<no value>";
                };
        return key + " = " + rendered + "  (" + value.getType().getValueStr() + ")";
    }

    private static void usage() {
        System.err.println(
                """
                frc-ai-copilot live-nt CLI
                usage:
                  monitor <host> [seconds]   connect and print value changes (default 10s)
                  get     <host> <key>       connect and print the current value of one key
                """);
    }

    private NtCli() {}
}
