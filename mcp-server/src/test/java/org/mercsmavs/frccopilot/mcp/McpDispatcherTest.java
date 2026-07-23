package org.mercsmavs.frccopilot.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpDispatcherTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final McpDispatcher dispatcher = new McpDispatcher();

    private JsonNode handle(String req) throws Exception {
        Optional<String> resp = dispatcher.handle(req);
        assertTrue(resp.isPresent(), "expected a response for: " + req);
        return mapper.readTree(resp.get());
    }

    @Test
    void initializeEchoesProtocolAndAdvertisesServer() throws Exception {
        JsonNode r = handle(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                        + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}}");
        assertEquals("2.0", r.get("jsonrpc").asText());
        assertEquals(1, r.get("id").asInt());
        assertEquals("2025-06-18", r.at("/result/protocolVersion").asText());
        assertEquals("frc-ai-copilot", r.at("/result/serverInfo/name").asText());
        assertTrue(r.at("/result/capabilities/tools").isObject());
    }

    @Test
    void notificationsProduceNoResponse() {
        assertTrue(dispatcher.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").isEmpty());
    }

    @Test
    void toolsListIncludesTheKeyTools() throws Exception {
        JsonNode r = handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        Set<String> names = new HashSet<>();
        r.at("/result/tools").forEach(t -> names.add(t.get("name").asText()));
        assertTrue(names.containsAll(Set.of(
                "get_guide", "log_info", "power_analysis", "can_health",
                "profile_show", "pathplanner_fudge")), () -> "tools were: " + names);
        // Every tool advertises an object input schema.
        r.at("/result/tools").forEach(t -> assertEquals("object", t.at("/inputSchema/type").asText()));
    }

    @Test
    void callsGetGuide() throws Exception {
        JsonNode r = handle(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"get_guide\",\"arguments\":{}}}");
        String text = r.at("/result/content/0/text").asText();
        assertTrue(text.toLowerCase().contains("guardrail"), "guide should mention guardrails");
        assertFalse(r.at("/result").has("isError") && r.at("/result/isError").asBoolean());
    }

    @Test
    void unknownToolReturnsToolError() throws Exception {
        JsonNode r = handle(
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"does_not_exist\",\"arguments\":{}}}");
        assertTrue(r.at("/result/isError").asBoolean(), "unknown tool must be a tool error");
    }

    @Test
    void unknownMethodReturnsJsonRpcError() throws Exception {
        JsonNode r = handle("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"no/such/method\"}");
        assertEquals(-32601, r.at("/error/code").asInt());
    }
}
