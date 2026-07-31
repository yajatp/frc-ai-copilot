package org.mercsmavs.frccopilot.dashboard;

import edu.wpi.first.networktables.NetworkTableValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.mercsmavs.frccopilot.analysis.SignalResolver;
import org.mercsmavs.frccopilot.livent.NtClient;

/**
 * Owns the dashboard's live view of the robot: one NetworkTables client, a rolling window per
 * numeric topic, and the intent-resolved set of signals the health tiles care about.
 *
 * <p>The dashboard's NT client is entirely independent of the one an AI agent may hold — NT4
 * supports many concurrent clients — and it is read-only: nothing here can write to the robot.
 * ({@code NtWriteGuard} is the only write path in this codebase and the dashboard never touches it.)
 *
 * <p>Sampling and broadcasting are deliberately decoupled. The NT listener thread only appends to
 * ring buffers, which is cheap and lets us keep every sample the robot publishes; the browser is fed
 * separately at a fixed tick rate, so a chatty robot can't flood the UI.
 */
final class TelemetryHub implements AutoCloseable {

    /**
     * Samples retained per topic — roughly 40 s at a 50 Hz publish rate. Long enough for the rolling
     * analysis window and live charts, small enough that a robot publishing hundreds of topics stays
     * in single-digit megabytes.
     */
    private static final int BUFFER_CAPACITY = 2048;

    /**
     * Requested NT delivery period. Paired with a send-all subscription this asks for every sample a
     * 50 Hz robot publishes, rather than NT4's default 100 ms periodic snapshot — which would
     * decimate a 20 ms loop roughly 5:1 and make overrun counts meaningless.
     */
    private static final double SUBSCRIBE_PERIOD_SECONDS = 0.02;

    /** Loop-time signal names, mirroring the candidates the MCP tools use. */
    private static final List<String> LOOP_CANDIDATES =
            List.of("loopTimeMs", "LoopTime", "codeRuntime", "PeriodicMs", "loopPeriod");

    /** A signal the health tiles want, resolved by intent against whatever the robot actually publishes. */
    record Resolved(String role, String key, String unit) {}

    /** One numeric topic's current state, for the topic browser. */
    record TopicView(String name, double value, int samples) {}

    private final NtClient client = new NtClient();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "dashboard-resolver");
                t.setDaemon(true);
                return t;
            });

    /** Rolling windows, one per numeric topic. Sorted so the topic browser reads alphabetically. */
    private final ConcurrentSkipListMap<String, RollingBuffer> buffers = new ConcurrentSkipListMap<>();

    /** Latest raw value per topic, including non-numeric ones (FMS strings, booleans). */
    private final ConcurrentHashMap<String, Object> latestRaw = new ConcurrentHashMap<>();

    private final String host;
    private final int port;
    private volatile AutoCloseable monitorHandle;
    private volatile Map<String, Resolved> resolved = Map.of();

    TelemetryHub(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /** Connects the NT client and starts ingesting every topic the server announces. */
    void start() {
        client.connect("frc-copilot-dashboard", host, port);
        monitorHandle = client.monitorAll(Set.of(), SUBSCRIBE_PERIOD_SECONDS, this::onValue);
        // Topics appear over time (and after a reconnect), so intent-resolution is re-run
        // periodically rather than once at startup.
        scheduler.scheduleAtFixedRate(this::resolveSignals, 0, 2, TimeUnit.SECONDS);
    }

    /** NT listener thread: keep this fast — it runs for every value change on every topic. */
    private void onValue(String key, NetworkTableValue value) {
        Object raw = value.getValue();
        latestRaw.put(key, raw);
        Double numeric = asNumeric(raw);
        if (numeric != null) {
            buffers.computeIfAbsent(key, k -> new RollingBuffer(BUFFER_CAPACITY))
                    .add(value.getTime(), numeric);
        }
    }

    /** Re-resolves the health signals against the topics currently published. */
    private void resolveSignals() {
        Set<String> numericKeys = buffers.keySet();
        Map<String, Resolved> found = new LinkedHashMap<>();
        put(found, numericKeys, "battery_voltage", SignalResolver.VOLTAGE, "V");
        put(found, numericKeys, "total_current", SignalResolver.TOTAL_CURRENT, "A");
        put(found, numericKeys, "can_errors", SignalResolver.CAN_ERRORS, "count");
        put(found, numericKeys, "loop_ms", LOOP_CANDIDATES, "ms");
        resolved = Map.copyOf(found);
    }

    private static void put(
            Map<String, Resolved> out, Set<String> keys, String role, List<String> candidates, String unit) {
        Optional<String> key = SignalResolver.resolve(keys, candidates);
        key.ifPresent(k -> out.put(role, new Resolved(role, k, unit)));
    }

    /**
     * Which health signals were found, and which were not. Roles missing from this map are exactly
     * what the Signal Coverage view reports as unpublished by the robot code.
     */
    Map<String, Resolved> resolved() {
        return resolved;
    }

    /** The rolling window for a topic, or null if that topic has never published a numeric value. */
    RollingBuffer buffer(String key) {
        return buffers.get(key);
    }

    boolean isConnected() {
        return client.isConnected();
    }

    String host() {
        return host;
    }

    int port() {
        return port;
    }

    int topicCount() {
        return latestRaw.size();
    }

    /** Latest value of every numeric topic, alphabetically — the topic browser's payload. */
    List<TopicView> topics(String prefix) {
        List<TopicView> out = new ArrayList<>();
        buffers.forEach((name, buf) -> {
            if (prefix == null || prefix.isEmpty() || name.startsWith(prefix)) {
                out.add(new TopicView(name, buf.latest(), buf.size()));
            }
        });
        return out;
    }

    /** Latest raw value of any topic, numeric or not (used for FMS/match state). */
    Object raw(String key) {
        return latestRaw.get(key);
    }

    /** Converts an NT value to a double, or null if it isn't a scalar number/boolean. */
    private static Double asNumeric(Object raw) {
        if (raw instanceof Double d) return d;
        if (raw instanceof Float f) return (double) f;
        if (raw instanceof Long l) return (double) l;
        if (raw instanceof Integer i) return (double) i;
        if (raw instanceof Boolean b) return b ? 1.0 : 0.0;
        return null; // arrays, strings, raw bytes — kept in latestRaw, not charted
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        if (monitorHandle != null) {
            try {
                monitorHandle.close();
            } catch (Exception ignored) {
                // Closing a listener that's already gone is not worth failing shutdown over.
            }
        }
        client.close();
    }
}
