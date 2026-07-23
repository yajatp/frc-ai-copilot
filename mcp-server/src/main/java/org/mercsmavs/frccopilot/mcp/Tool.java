package org.mercsmavs.frccopilot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** One MCP tool: a name, a description, a JSON-Schema for its arguments, and a handler. */
public interface Tool {

    String name();

    String description();

    /** JSON Schema (object) describing the tool's arguments. */
    ObjectNode inputSchema();

    /** Execute the tool; return human-/agent-readable text. May throw to signal a tool error. */
    String call(JsonNode arguments) throws Exception;
}
