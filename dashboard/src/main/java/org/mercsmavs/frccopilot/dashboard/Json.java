package org.mercsmavs.frccopilot.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Small shared JSON helpers, so handlers stay about routing rather than serialization. */
final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }

    static ArrayNode arr() {
        return MAPPER.createArrayNode();
    }

    /**
     * Serializes a node to a single line — required for Server-Sent Events, where a newline inside
     * a {@code data:} field would terminate the frame.
     */
    static String write(Object node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize JSON", e);
        }
    }

    /**
     * JSON has no NaN/Infinity literal, and a raw NaN would make {@code JSON.parse} throw in the
     * browser. Non-finite values become null, which the UI renders as "no reading".
     */
    static void putNumber(ObjectNode node, String field, double value) {
        if (Double.isFinite(value)) {
            node.put(field, value);
        } else {
            node.putNull(field);
        }
    }

    private Json() {}
}
