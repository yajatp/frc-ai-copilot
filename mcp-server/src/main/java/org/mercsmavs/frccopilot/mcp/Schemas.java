package org.mercsmavs.frccopilot.mcp;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Tiny helpers for building JSON-Schema objects for tool inputs. */
final class Schemas {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    /**
     * @param itemType element type when {@code type} is {@code "array"} — clients that validate
     *     strictly reject an array schema with no {@code items}. Null for scalar props.
     */
    record Prop(String name, String type, String description, boolean required, String itemType) {
        Prop(String name, String type, String description, boolean required) {
            this(name, type, description, required, null);
        }
    }

    static ObjectNode object(Prop... props) {
        ObjectNode schema = F.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = F.arrayNode();
        for (Prop p : props) {
            ObjectNode prop = properties.putObject(p.name());
            prop.put("type", p.type());
            prop.put("description", p.description());
            if (p.itemType() != null) {
                prop.putObject("items").put("type", p.itemType());
            }
            if (p.required()) {
                required.add(p.name());
            }
        }
        if (!required.isEmpty()) {
            schema.set("required", required);
        }
        return schema;
    }

    static ObjectNode empty() {
        ObjectNode schema = F.objectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        return schema;
    }

    static List<Prop> nothing() {
        return List.of();
    }

    private Schemas() {}
}
