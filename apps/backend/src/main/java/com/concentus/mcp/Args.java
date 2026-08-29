package com.concentus.mcp;

import com.concentus.model.FlowGraph;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads a tool call's {@code arguments}.
 *
 * <p>Missing and blank are treated as the same thing throughout. A model that means "no folder"
 * sends {@code ""} about as often as it omits the key, and an endpoint that distinguishes them
 * turns a shrug into a filter matching nothing.
 *
 * <p>{@link #require} throws {@link IllegalArgumentException}; the controller turns that into a
 * failed tool result carrying the message. That is the point of throwing rather than returning an
 * error — the argument checks read as preconditions at the top of a handler instead of as a
 * ladder of early returns wrapping the actual work.
 */
public final class Args {

    private Args() {
    }

    public static String str(JsonNode args, String name, String fallback) {
        String value = args.path(name).asText("");
        return value.isBlank() ? fallback : value.trim();
    }

    /** The argument, or a failure naming it. Blank counts as absent. */
    public static String require(JsonNode args, String name) {
        String value = str(args, name, null);
        if (value == null) {
            throw new IllegalArgumentException("'" + name + "' is required and was not given.");
        }
        return value;
    }

    public static boolean bool(JsonNode args, String name, boolean fallback) {
        JsonNode node = args.path(name);
        return node.isMissingNode() || node.isNull() ? fallback : node.asBoolean(fallback);
    }

    /**
     * A positive count, clamped into {@code [1, max]}.
     *
     * <p>Clamped rather than rejected: a limit is a courtesy to the caller's context window, and
     * failing a call over one is worse than quietly giving a sane amount. The ceiling is what
     * matters — an agent asking for every event of a long run would spend its whole window on it.
     */
    public static int count(JsonNode args, String name, int fallback, int max) {
        int value = args.path(name).asInt(fallback);
        if (value <= 0) return fallback;
        return Math.min(value, max);
    }

    /** A required object argument (a flow graph, a node's data, a resource). */
    public static JsonNode object(JsonNode args, String name) {
        JsonNode node = args.path(name);
        if (!node.isObject()) {
            throw new IllegalArgumentException("'" + name + "' is required and must be a JSON object.");
        }
        return node;
    }

    /** A flow graph out of an object argument, with a parse failure phrased as something to fix. */
    public static FlowGraph flow(ObjectMapper mapper, JsonNode node) {
        try {
            return mapper.treeToValue(node, FlowGraph.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("That is not a valid flow graph: " + e.getMessage()
                    + " Call flow_schema for the format.");
        }
    }
}
