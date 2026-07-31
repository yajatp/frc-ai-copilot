package org.mercsmavs.frccopilot.dashboard;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.mercsmavs.frccopilot.modes.ModeA;

/**
 * The dashboard's local web server: a JSON/SSE API over {@link TelemetryHub} plus the static UI.
 *
 * <p>Bound to loopback only. Robot telemetry should not be reachable from the field network, and
 * keeping it off the wire also means no CORS surface — in development the Vite dev server proxies
 * to this port rather than calling it cross-origin.
 *
 * <p>Two cadences drive the browser. Raw values tick at 10 Hz so gauges feel live; health verdicts
 * recompute at 2 Hz, because re-running the analysis primitives over the full rolling window ten
 * times a second would be pointless garbage for a number that changes on the scale of seconds.
 */
final class DashboardServer implements AutoCloseable {

    private static final int TICK_HZ = 10;
    private static final int HEALTH_HZ = 2;

    /** Points of history sent in the initial frame, per signal. */
    private static final int HISTORY_POINTS = 300;

    /** Standard FMS topics, published by the Driver Station whenever it is attached. */
    private static final String FMS_EVENT = "/FMSInfo/EventName";
    private static final String FMS_MATCH_NUMBER = "/FMSInfo/MatchNumber";
    private static final String FMS_MATCH_TYPE = "/FMSInfo/MatchType";
    private static final String FMS_IS_RED = "/FMSInfo/IsRedAlliance";
    private static final String FMS_STATION = "/FMSInfo/StationNumber";

    private final TelemetryHub hub;
    private final SseHub sse = new SseHub();
    private final HttpServer http;
    private final ExecutorService httpPool;
    private final ScheduledExecutorService ticker;
    private final int port;

    /** Latest health verdicts, recomputed on the slow cadence and reused by the fast one. */
    private volatile List<LiveHealth.Verdict> health = List.of();

    DashboardServer(TelemetryHub hub, int port, Path webRoot) throws IOException {
        this.hub = hub;
        this.port = port;
        this.http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        // SSE subscribers each hold a thread for the life of the tab, so the default
        // single-threaded executor would deadlock after the first browser connects.
        this.httpPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "dashboard-http");
            t.setDaemon(true);
            return t;
        });
        this.ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dashboard-tick");
            t.setDaemon(true);
            return t;
        });

        http.setExecutor(httpPool);
        http.createContext("/api/status", this::handleStatus);
        http.createContext("/api/topics", this::handleTopics);
        http.createContext("/api/health", this::handleHealth);
        http.createContext("/api/stream", this::handleStream);
        http.createContext("/", new StaticFiles(webRoot));
    }

    void start() {
        http.start();
        ticker.scheduleAtFixedRate(
                this::safeBroadcastTick, 0, 1000 / TICK_HZ, TimeUnit.MILLISECONDS);
        ticker.scheduleAtFixedRate(
                this::safeRecomputeHealth, 0, 1000 / HEALTH_HZ, TimeUnit.MILLISECONDS);
    }

    int port() {
        return port;
    }

    // --- scheduled work -------------------------------------------------------------------

    private void safeRecomputeHealth() {
        try {
            health = LiveHealth.assess(hub);
        } catch (RuntimeException e) {
            // A bad frame must never kill the scheduler — the next tick should still run.
            System.err.println("[dashboard] health assessment failed: " + e);
        }
    }

    private void safeBroadcastTick() {
        try {
            if (sse.subscriberCount() > 0) {
                sse.broadcast("tick", Json.write(tickFrame()));
            }
        } catch (RuntimeException e) {
            System.err.println("[dashboard] tick broadcast failed: " + e);
        }
    }

    /** The 10 Hz frame: current readings and connection state, no history. */
    private ObjectNode tickFrame() {
        ObjectNode frame = Json.obj();
        frame.put("t", System.currentTimeMillis());
        frame.put("connected", hub.isConnected());
        frame.put("topics", hub.topicCount());

        ObjectNode signals = frame.putObject("signals");
        hub.resolved().forEach((role, resolved) -> {
            RollingBuffer buf = hub.buffer(resolved.key());
            if (buf == null) {
                return;
            }
            ObjectNode s = signals.putObject(role);
            s.put("key", resolved.key());
            s.put("unit", resolved.unit());
            Json.putNumber(s, "value", buf.latest());
            s.put("tMs", buf.latestTimestampUs() / 1000L);
        });

        frame.set("health", healthArray());
        frame.set("fms", fmsNode());
        return frame;
    }

    private ArrayNode healthArray() {
        ArrayNode arr = Json.arr();
        for (LiveHealth.Verdict v : health) {
            ObjectNode n = arr.addObject();
            n.put("role", v.role());
            n.put("label", v.label());
            n.put("severity", v.severity().name());
            Json.putNumber(n, "value", v.value());
            n.put("unit", v.unit());
            n.put("assessment", v.assessment());
            n.put("confidence", v.confidence());
            if (v.signal() == null) {
                n.putNull("signal");
            } else {
                n.put("signal", v.signal());
            }
        }
        return arr;
    }

    /** Match context from the Driver Station, or {@code attached: false} when running off-field. */
    private ObjectNode fmsNode() {
        ObjectNode fms = Json.obj();
        Object event = hub.raw(FMS_EVENT);
        boolean attached = event != null;
        fms.put("attached", attached);
        fms.put("eventName", event instanceof String s ? s : "");
        fms.put("matchNumber", intOf(hub.raw(FMS_MATCH_NUMBER)));
        fms.put("matchType", intOf(hub.raw(FMS_MATCH_TYPE)));
        fms.put("station", intOf(hub.raw(FMS_STATION)));
        Object isRed = hub.raw(FMS_IS_RED);
        if (isRed instanceof Boolean b) {
            fms.put("isRedAlliance", b);
        } else {
            fms.putNull("isRedAlliance");
        }
        return fms;
    }

    private static int intOf(Object raw) {
        if (raw instanceof Long l) return l.intValue();
        if (raw instanceof Integer i) return i;
        if (raw instanceof Double d) return d.intValue();
        return 0;
    }

    // --- handlers -------------------------------------------------------------------------

    private void handleStatus(HttpExchange exchange) throws IOException {
        ObjectNode node = Json.obj();
        node.put("connected", hub.isConnected());
        node.put("host", hub.host());
        node.put("ntPort", hub.port());
        node.put("topics", hub.topicCount());
        node.put("subscribers", sse.subscriberCount());
        ObjectNode roles = node.putObject("resolved");
        hub.resolved().forEach((role, r) -> roles.put(role, r.key()));
        json(exchange, node);
    }

    private void handleTopics(HttpExchange exchange) throws IOException {
        String prefix = queryParam(exchange, "prefix");
        ArrayNode arr = Json.arr();
        for (TelemetryHub.TopicView t : hub.topics(prefix)) {
            ObjectNode n = arr.addObject();
            n.put("name", t.name());
            Json.putNumber(n, "value", t.value());
            n.put("samples", t.samples());
        }
        ObjectNode node = Json.obj();
        node.set("topics", arr);
        json(exchange, node);
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        ObjectNode node = Json.obj();
        node.set("health", healthArray());
        json(exchange, node);
    }

    /**
     * Opens an event stream. The first frame carries chart history so a newly-opened tab is not
     * blank for the first few seconds; everything after it is a delta tick.
     */
    private void handleStream(HttpExchange exchange) throws IOException {
        sse.subscribe(exchange);

        ObjectNode hello = tickFrame();
        ObjectNode history = hello.putObject("history");
        for (Map.Entry<String, TelemetryHub.Resolved> e : hub.resolved().entrySet()) {
            RollingBuffer buf = hub.buffer(e.getValue().key());
            if (buf == null) {
                continue;
            }
            ArrayNode points = history.putArray(e.getKey());
            for (double[] point : buf.decimated(HISTORY_POINTS)) {
                ArrayNode pair = points.addArray();
                pair.add(point[0]);
                pair.add(point[1]);
            }
        }
        try {
            sse.send(exchange, "hello", Json.write(hello));
        } catch (IOException closedImmediately) {
            exchange.close();
        }
        // Deliberately not closed: the exchange now belongs to SseHub for the life of the tab.
    }

    private static void json(HttpExchange exchange, ObjectNode node) throws IOException {
        StaticFiles.respond(exchange, 200, "application/json; charset=utf-8",
                Json.write(node).getBytes(StandardCharsets.UTF_8));
    }

    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** Severity ordering is shared with Mode A; exposed here so the UI can sort tiles by urgency. */
    static int rank(ModeA.Severity severity) {
        return severity.ordinal();
    }

    @Override
    public void close() {
        ticker.shutdownNow();
        sse.closeAll();
        http.stop(0);
        httpPool.shutdownNow();
    }
}
