package org.mercsmavs.frccopilot.dashboard;

import java.nio.file.Path;

/**
 * Entry point for the local dashboard.
 *
 * <pre>
 *   dashboard                          # connect to a roboRIO at 10.63.69.2
 *   dashboard --host localhost         # connect to a desktop simulation
 *   dashboard --sim                    # run a built-in fake robot, no hardware needed
 *   dashboard --port 5800 --team 6369  # pick the web port / derive the roboRIO address
 * </pre>
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

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--team" -> team = Integer.parseInt(args[++i]);
                case "--port" -> webPort = Integer.parseInt(args[++i]);
                case "--nt-port" -> ntPort = Integer.parseInt(args[++i]);
                case "--sim" -> sim = true;
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

        Path webRoot = StaticFiles.resolveRoot();
        DashboardServer server = new DashboardServer(hub, webPort, webRoot);
        server.start();

        System.out.println("[dashboard] http://localhost:" + server.port());
        System.out.println("[dashboard] NetworkTables target " + host + ":" + ntPort);
        if (webRoot == null) {
            System.out.println("[dashboard] UI not built yet — run: cd dashboard/web && npm install && npm run build");
        }

        SimRobot toClose = simRobot;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            hub.close();
            if (toClose != null) {
                toClose.close();
            }
        }));
        Thread.currentThread().join();
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
                """);
    }

    private DashboardMain() {}
}
