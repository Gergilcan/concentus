package com.concentus.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds the JSON Schema an MCP tool advertises for its arguments.
 *
 * <p>Hand-written {@code putObject("properties").putObject(name).put("type", ...)} chains are how
 * the existing endpoints do it, and at two tools that reads fine. At two dozen it stops being
 * legible, and the part that goes wrong is invisible: a {@code required} array that names a
 * property no longer present, or a property added to the wrong nesting level, is still valid JSON
 * and still serves — it just quietly stops constraining anything.
 *
 * <p>Every description here is read by a model rather than a person, which is why the methods
 * demand one. A property whose meaning has to be guessed is guessed wrong.
 */
public final class Schema {

    private final ObjectNode root;
    private final ObjectNode properties;
    private ArrayNode required;

    private Schema(ObjectMapper mapper) {
        this.root = mapper.createObjectNode();
        this.root.put("type", "object");
        this.properties = this.root.putObject("properties");
    }

    /** An object schema with no properties yet — and, for a tool that takes none, the final one. */
    public static Schema of(ObjectMapper mapper) {
        return new Schema(mapper);
    }

    public Schema str(String name, String description) {
        return property(name, "string", description, false);
    }

    public Schema requiredStr(String name, String description) {
        return property(name, "string", description, true);
    }

    public Schema bool(String name, String description) {
        return property(name, "boolean", description, false);
    }

    public Schema number(String name, String description) {
        return property(name, "integer", description, false);
    }

    /**
     * A free-form object — a whole flow graph, or one node's {@code data}. Deliberately unconstrained:
     * the flow schema changes with the product, and a JSON Schema copy of it here would be a second
     * definition to keep in step with {@code FlowGraph}. What actually guards the shape is
     * {@code FlowCompiler.compile}, which every write goes through and which rejects with a message
     * naming the problem. {@code flow_schema} is where the model learns the shape.
     */
    public Schema object(String name, String description, boolean isRequired) {
        return property(name, "object", description, isRequired);
    }

    /** A required string restricted to a fixed set — the model sees the options in tools/list. */
    public Schema requiredEnumeration(String name, String description, String... values) {
        ArrayNode allowed = add(name, "string", description, true).putArray("enum");
        for (String value : values) allowed.add(value);
        return this;
    }

    private Schema property(String name, String type, String description, boolean isRequired) {
        add(name, type, description, isRequired);
        return this;
    }

    private ObjectNode add(String name, String type, String description, boolean isRequired) {
        ObjectNode node = properties.putObject(name);
        node.put("type", type);
        node.put("description", description);
        if (isRequired) {
            if (required == null) required = root.putArray("required");
            required.add(name);
        }
        return node;
    }

    public ObjectNode build() {
        return root;
    }
}
