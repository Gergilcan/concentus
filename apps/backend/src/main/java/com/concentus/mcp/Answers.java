package com.concentus.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;

/**
 * How a tool's answer is written for the model reading it.
 *
 * <p>Two shapes, chosen by what the caller does next. Anything it might send back — a flow graph, a
 * saved resource — is JSON, because a model that has to reconstruct JSON from a prose summary
 * reconstructs it wrong. Anything it only reads to decide — a list of flows, a run's events — is
 * lines, because a JSON array of twenty flows costs several times the tokens of twenty lines and
 * says nothing extra.
 */
public final class Answers {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private Answers() {
    }

    /** Pretty-printed JSON — indented on purpose, so a model editing it can find its way around. */
    public static String json(ObjectMapper mapper, Object value) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            // Serialising our own records cannot realistically fail, but a tool result that says
            // so beats one that throws through the JSON-RPC layer as a transport error.
            return "This could not be rendered as JSON: " + e.getMessage();
        }
    }

    /**
     * A heading and one line per item, or a stated emptiness.
     *
     * <p>"Nothing" is spelled out rather than left as a blank answer: an empty tool result reads to
     * a model as a failure it should retry, and it retries.
     */
    public static <T> String lines(String heading, String emptiness, List<T> items,
                                   Function<T, String> render) {
        if (items.isEmpty()) return emptiness;
        StringBuilder out = new StringBuilder(items.size() + " " + heading + ":\n");
        for (T item : items) out.append('\n').append(render.apply(item));
        return out.toString();
    }

    /** A millisecond epoch as a local timestamp; blank when there is none (0 = never happened). */
    public static String when(long epochMillis) {
        return epochMillis <= 0 ? "" : STAMP.format(Instant.ofEpochMilli(epochMillis));
    }

    /**
     * Text cut to a budget, with the cut declared.
     *
     * <p>An agent's output can be tens of thousands of characters, and several of them arrive in
     * one {@code get_run_nodes}. Truncating silently would let a model quote a conclusion that was
     * actually cut off mid-sentence, so the marker is part of the contract.
     */
    public static String clip(String text, int max) {
        if (text == null) return "";
        String trimmed = text.strip();
        if (trimmed.length() <= max) return trimmed;
        return trimmed.substring(0, max) + "\n… [cut after " + max + " characters — "
                + (trimmed.length() - max) + " more]";
    }
}
