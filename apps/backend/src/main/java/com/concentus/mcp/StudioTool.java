package com.concentus.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.function.Function;

/**
 * One tool the Studio MCP server offers, complete: how it is advertised and what it does.
 *
 * <p>Name, description, schema and behaviour travel together rather than living in a
 * {@code tools/list} switch and a {@code tools/call} switch that have to agree. Four MCP endpoints
 * in this codebase already pair those two switches by hand, and it is fine there because each
 * serves one or two fixed tools. This server serves two dozen across three subject areas, and the
 * failure mode of the split — a tool advertised with a schema nothing reads, or callable and
 * invisible — is one an agent experiences as the server lying about itself.
 *
 * @param name        the tool name the model calls, {@code snake_case} like the rest of MCP
 * @param description what it does AND when to reach for it. Written for a model with no other
 *                    documentation: this text is the entire interface.
 * @param inputSchema JSON Schema for {@code arguments}
 * @param handler     receives the {@code arguments} object (never null — an absent one arrives as
 *                    an empty object) and answers with what the caller should see
 */
public record StudioTool(String name, String description, ObjectNode inputSchema,
                         Function<JsonNode, Result> handler) {

    /**
     * A tool's answer.
     *
     * <p>{@code isError} is not a transport failure — the JSON-RPC call succeeded either way. It
     * means "this did not work", and the model is expected to read {@link #text} and correct
     * itself. So every failing result here says what to do next, not merely what went wrong: an
     * agent that is told "flow not found" guesses again, while one told "flow not found — call
     * list_flows for the ids that exist" fixes itself in one turn.
     */
    public record Result(boolean isError, String text) {

        public static Result ok(String text) {
            return new Result(false, text);
        }

        public static Result failed(String text) {
            return new Result(true, text);
        }
    }
}
