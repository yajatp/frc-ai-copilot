package org.mercsmavs.frccopilot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.Optional;

/**
 * Handles one MCP JSON-RPC message and produces the response line (or empty for notifications).
 *
 * <p>Implements the slice of the Model Context Protocol that Claude Code actually uses over stdio:
 * {@code initialize}, {@code notifications/initialized}, {@code tools/list}, {@code tools/call},
 * {@code ping}. Kept transport-free so it is directly unit-testable.
 */
public final class McpDispatcher {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_PROTOCOL = "2024-11-05";
    private static final String SERVER_NAME = "frc-ai-copilot";
    private static final String SERVER_VERSION = "0.1.0";

    private final Map<String, Tool> tools;

    public McpDispatcher() {
        this(ToolRegistry.build());
    }

    McpDispatcher(Map<String, Tool> tools) {
        this.tools = tools;
    }

    /** @return the response JSON to write back, or empty for notifications (no id). */
    public Optional<String> handle(String line) {
        JsonNode req;
        try {
            req = MAPPER.readTree(line);
        } catch (Exception e) {
            return Optional.of(errorString(null, -32700, "Parse error"));
        }
        JsonNode id = req.get("id");
        String method = req.path("method").asText("");
        try {
            return switch (method) {
                case "initialize" -> Optional.of(resultString(id, initialize(req.path("params"))));
                case "notifications/initialized", "notifications/cancelled" -> Optional.empty();
                case "ping" -> Optional.of(resultString(id, MAPPER.createObjectNode()));
                case "tools/list" -> Optional.of(resultString(id, toolsList()));
                case "tools/call" -> Optional.of(resultString(id, toolsCall(req.path("params"))));
                default -> id == null
                        ? Optional.empty()
                        : Optional.of(errorString(id, -32601, "Method not found: " + method));
            };
        } catch (Exception e) {
            return id == null
                    ? Optional.empty()
                    : Optional.of(errorString(id, -32603, String.valueOf(e.getMessage())));
        }
    }

    private ObjectNode initialize(JsonNode params) {
        String protocol = params.path("protocolVersion").asText(DEFAULT_PROTOCOL);
        ObjectNode result = MAPPER.createObjectNode();
        result.put("protocolVersion", protocol);
        result.putObject("capabilities").putObject("tools");
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", SERVER_NAME);
        info.put("version", SERVER_VERSION);
        result.put("instructions", "Call get_guide first, then profile_show for the team's robot.");
        return result;
    }

    private ObjectNode toolsList() {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode arr = result.putArray("tools");
        for (Tool t : tools.values()) {
            ObjectNode td = arr.addObject();
            td.put("name", t.name());
            td.put("description", t.description());
            td.set("inputSchema", t.inputSchema());
        }
        return result;
    }

    private ObjectNode toolsCall(JsonNode params) {
        String name = params.path("name").asText();
        Tool tool = tools.get(name);
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode content = result.putArray("content");
        if (tool == null) {
            content.addObject().put("type", "text").put("text", "Unknown tool: " + name);
            result.put("isError", true);
            return result;
        }
        try {
            String text = tool.call(params.get("arguments"));
            content.addObject().put("type", "text").put("text", text);
        } catch (Exception e) {
            // MCP convention: tool failures come back as a result with isError, not a protocol error.
            content.addObject().put("type", "text").put("text", "Tool error: " + e.getMessage());
            result.put("isError", true);
        }
        return result;
    }

    private String resultString(JsonNode id, ObjectNode result) {
        ObjectNode env = MAPPER.createObjectNode();
        env.put("jsonrpc", "2.0");
        env.set("id", id == null ? null : id);
        env.set("result", result);
        return env.toString();
    }

    private String errorString(JsonNode id, int code, String message) {
        ObjectNode env = MAPPER.createObjectNode();
        env.put("jsonrpc", "2.0");
        env.set("id", id == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : id);
        ObjectNode err = env.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return env.toString();
    }
}
