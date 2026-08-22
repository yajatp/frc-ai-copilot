package org.mercsmavs.frccopilot.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.mercsmavs.frccopilot.ingest.store.TrendStore;
import org.mercsmavs.frccopilot.profile.RobotProfile;

/**
 * Entry point for the local dashboard.
 */
public final class DashboardMain {

    private static final int DEFAULT_WEB_PORT = 5800;
    private static final int DEFAULT_NT_PORT = 5810;
    private static final int SIM_NT_PORT = 5811;
    private static final int DEFAULT_TEAM = 6369;

    public static void main(String[] args) throws Exception {
        String host = null;
        Integer team = null;
        int webPort = DEFAULT_WEB_PORT;
        int ntPort = DEFAULT_NT_PORT;
        boolean sim = false;
        Path pathsDir = null;
        String dbPath = null;
        Path profilePath = null;
        boolean open = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--team" -> team = Integer.parseInt(args[++i]);
                case "--port" -> webPort = Integer.parseInt(args[++i]);
                case "--nt-port" -> ntPort = Integer.parseInt(args[++i]);
                case "--sim" -> sim = true;
                case "--open" -> open = true;
                case "--paths" -> pathsDir = Path.of(args[++i]);
                case "--db" -> dbPath = args[++i];
                case "--profile" -> profilePath = Path.of(args[++i]);
                case "--help", "-h" -> {
                    printUsage();
                    return;
                }
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    printUsage();
                    System.exit(2);
                }
            }
        }

        SimRobot simRobot = null;
        if (sim) {
            simRobot = new SimRobot();
            simRobot.start(SIM_NT_PORT);
            host = "127.0.0.1";
            ntPort = SIM_NT_PORT;
            System.out.println("[dashboard] simulated robot publishing on NT port " + SIM_NT_PORT);
        } else if (host == null) {
            host = roborioAddress(team == null ? DEFAULT_TEAM : team);
        }

        TelemetryHub hub = new TelemetryHub(host, ntPort);
        hub.start();

        TrendStore store = null;
        if (dbPath != null) {
            try {
                store = new TrendStore(dbPath);
                System.out.println("[dashboard] trend store active: " + dbPath);
            } catch (Exception e) {
                System.err.println("[dashboard] failed to open trend store: " + e.getMessage());
            }
        }

        RobotProfile profile = null;
        if (profilePath != null && Files.isRegularFile(profilePath)) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                profile = mapper.readValue(profilePath.toFile(), RobotProfile.class);
                System.out.println("[dashboard] robot profile loaded: " + profilePath);
            } catch (Exception e) {
                System.err.println("[dashboard] failed to read profile: " + e.getMessage());
            }
        }

        Path webRoot = StaticFiles.resolveRoot();
        DashboardServer server = new DashboardServer(hub, webPort, webRoot, pathsDir, store, profile);
        server.start();

        System.out.println("[dashboard] http://localhost:" + server.port());
        System.out.println("[dashboard] NetworkTables target " + host + ":" + ntPort);
        if (webRoot == null) {
            System.out.println("[dashboard] UI not built yet — run: cd dashboard/web && npm install && npm run build");
        }

        if (open) {
            openInBrowser("http://localhost:" + server.port());
        }

        SimRobot toClose = simRobot;
        TrendStore storeToClose = store;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            hub.close();
            if (storeToClose != null) {
                try { storeToClose.close(); } catch (Exception ignored) {}
            }
            if (toClose != null) {
                toClose.close();
            }
        }));
        Thread.currentThread().join();
    }

    /**
     * Opens {@code url} in the machine's default browser.
     *
     * <p>Deliberately not {@code java.awt.Desktop}: that pulls in AWT, which on a headless pit
     * laptop (or over SSH) throws rather than degrading, and on macOS can bounce a Dock icon for a
     * process that is otherwise a console server. Shelling out to the platform opener is what every
     * other FRC tool does, and failing to open a browser must never take the server down with it —
     * the URL is already printed above, so a failure here is an inconvenience, not an error.
     */
    private static void openInBrowser(String url) {
        String os = System.getProperty("os.name").toLowerCase();
        List<String> command;
        if (os.contains("mac") || os.contains("darwin")) {
            command = List.of("open", url);
        } else if (os.contains("win")) {
            // "start" is a cmd builtin, not an executable; the empty string is the window title,
            // which start would otherwise take from a quoted URL and then open nothing.
            command = List.of("cmd", "/c", "start", "", url);
        } else {
            command = List.of("xdg-open", url);
        }
        try {
            new ProcessBuilder(command).inheritIO().start();
        } catch (Exception e) {
            System.out.println("[dashboard] could not open a browser (" + e.getMessage()
                    + ") — open " + url + " yourself.");
        }
    }

    /** The conventional roboRIO address for a team number: 10.TE.AM.2. */
    private static String roborioAddress(int team) {
        return "10." + (team / 100) + "." + (team % 100) + ".2";
    }

    private static void printUsage() {
        System.out.println("""
                FRC AI Copilot — local dashboard

                  --host <addr>    NetworkTables server to connect to (default: roboRIO for --team)
                  --team <number>  team number used to derive 10.TE.AM.2 (default: 6369)
                  --port <n>       web port (default: 5800)
                  --nt-port <n>    NetworkTables port (default: 5810)
                  --sim            run a built-in simulated robot instead of connecting to hardware
                  --open           open the dashboard in the default browser once it is serving
                  --paths <dir>    directory containing PathPlanner .path files
                  --db <path>      path to SQLite trend store database
                  --profile <file> path to robot profile JSON file
                """);
    }

    private DashboardMain() {}
}

