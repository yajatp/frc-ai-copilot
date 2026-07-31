package org.mercsmavs.frccopilot.dashboard;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Serves the built Vite bundle. Node is a build-time tool only — by the time this runs the UI is
 * just a folder of static files, so the pit laptop needs nothing but the JVM.
 */
final class StaticFiles implements HttpHandler {

    private static final Map<String, String> MIME = Map.of(
            ".html", "text/html; charset=utf-8",
            ".js", "text/javascript; charset=utf-8",
            ".css", "text/css; charset=utf-8",
            ".json", "application/json; charset=utf-8",
            ".svg", "image/svg+xml",
            ".woff", "font/woff",
            ".woff2", "font/woff2",
            ".png", "image/png",
            ".ico", "image/x-icon");

    private final Path root;

    StaticFiles(Path root) {
        this.root = root == null ? null : root.toAbsolutePath().normalize();
    }

    /**
     * Locates the built UI: an explicit {@code -Dfrc.dashboard.web} (set by the packaged launcher),
     * then the in-repo Vite output for a development run.
     */
    static Path resolveRoot() {
        String override = System.getProperty("frc.dashboard.web");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        Path cwd = Path.of(System.getProperty("user.dir"));
        for (Path candidate : new Path[] {cwd.resolve("dashboard/web/dist"), cwd.resolve("web/dist")}) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (root == null || !Files.isDirectory(root)) {
            respond(exchange, 503, "text/plain; charset=utf-8",
                    ("The dashboard UI has not been built yet.\n\n"
                            + "  cd dashboard/web && npm install && npm run build\n\n"
                            + "or run ./gradlew :dashboard:installDist, which builds it for you.\n")
                            .getBytes(StandardCharsets.UTF_8));
            return;
        }

        String rawPath = exchange.getRequestURI().getPath();
        Path file = resolveFile(rawPath);
        if (file == null) {
            // Single-page app: any unknown route is handled client-side, so serve the shell.
            file = root.resolve("index.html");
        }
        if (!Files.isRegularFile(file)) {
            respond(exchange, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        // Hashed asset filenames may be cached hard; index.html must not be, or a rebuilt UI
        // would never reach an already-open tab.
        boolean hashedAsset = file.startsWith(root.resolve("assets"));
        exchange.getResponseHeaders().add("Cache-Control",
                hashedAsset ? "public, max-age=31536000, immutable" : "no-cache");
        respond(exchange, 200, mimeOf(file.getFileName().toString()), Files.readAllBytes(file));
    }

    /** Maps a URL path to a file under the root, or null if it escapes the root or is absent. */
    private Path resolveFile(String rawPath) {
        String relative = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
        if (relative.isEmpty()) {
            return root.resolve("index.html");
        }
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) {
            return null; // path traversal attempt
        }
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    private static String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "application/octet-stream";
        }
        return MIME.getOrDefault(name.substring(dot).toLowerCase(), "application/octet-stream");
    }

    static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
