package org.mercsmavs.frccopilot.mcp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Entry point: an MCP server over stdio (newline-delimited JSON-RPC), the transport Claude Code
 * uses. Reads one request per line from stdin, writes one response per line to stdout. All logging
 * goes to stderr so stdout stays pure protocol.
 */
public final class McpServer {

    public static void main(String[] args) throws Exception {
        McpDispatcher dispatcher = new McpDispatcher();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        System.err.println("[frc-ai-copilot] MCP server ready (stdio).");

        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            Optional<String> response = dispatcher.handle(line);
            response.ifPresent(out::println);
        }
    }

    private McpServer() {}
}
