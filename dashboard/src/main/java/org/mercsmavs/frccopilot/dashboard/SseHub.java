package org.mercsmavs.frccopilot.dashboard;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fan-out of live telemetry frames to every open browser tab over Server-Sent Events.
 *
 * <p>SSE rather than WebSockets on purpose: the data flows one way (robot → browser), it is plain
 * HTTP so it needs no extra dependency on top of the JDK's {@code HttpServer}, and browsers
 * reconnect on their own if the pit laptop's link hiccups.
 *
 * <p>Each subscriber holds an HTTP response open, so the server must run on a pooled executor —
 * the default single-threaded one would be consumed by the first tab.
 */
final class SseHub {

    private final CopyOnWriteArrayList<HttpExchange> subscribers = new CopyOnWriteArrayList<>();

    /**
     * Takes ownership of an exchange and keeps it open as an event stream. The caller must not
     * close the exchange afterwards.
     */
    void subscribe(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache, no-transform");
        exchange.getResponseHeaders().add("Connection", "keep-alive");
        // Length 0 selects chunked encoding, which is what keeps the response open indefinitely.
        exchange.sendResponseHeaders(200, 0);
        // Nudge the browser's reconnect backoff down; a pit-side drop should recover quickly.
        write(exchange, "retry: 2000\n\n");
        subscribers.add(exchange);
    }

    /** Sends one named event to every subscriber, dropping any whose connection has gone away. */
    void broadcast(String event, String jsonData) {
        if (subscribers.isEmpty()) {
            return;
        }
        String frame = "event: " + event + "\ndata: " + jsonData + "\n\n";
        for (HttpExchange exchange : subscribers) {
            try {
                write(exchange, frame);
            } catch (IOException e) {
                // Tab closed or navigated away — reap it.
                subscribers.remove(exchange);
                exchange.close();
            }
        }
    }

    /** Sends one named event to a single subscriber (the initial history frame). */
    void send(HttpExchange exchange, String event, String jsonData) throws IOException {
        write(exchange, "event: " + event + "\ndata: " + jsonData + "\n\n");
    }

    int subscriberCount() {
        return subscribers.size();
    }

    void closeAll() {
        List<HttpExchange> open = List.copyOf(subscribers);
        subscribers.clear();
        open.forEach(HttpExchange::close);
    }

    private static void write(HttpExchange exchange, String text) throws IOException {
        OutputStream body = exchange.getResponseBody();
        body.write(text.getBytes(StandardCharsets.UTF_8));
        body.flush();
    }
}
