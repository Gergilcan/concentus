package com.concentus.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Provider-neutral shapes for one chat turn.
 *
 * <p>Deliberately the intersection of what every provider supports — text, tool calls, tool
 * results, token usage — rather than the union. Anything a single vendor does uniquely stays
 * behind its own adapter; the orchestrator only ever sees these.
 */
public final class ChatTypes {

    private ChatTypes() {
    }

    /** A tool the model may call. {@code parameters} is a JSON Schema object. */
    public record ToolSpec(String name, String description, JsonNode parameters) {
    }

    /** A tool invocation the model asked for. {@code argumentsJson} is raw, unparsed JSON. */
    public record ToolCall(String id, String name, String argumentsJson) {
    }

    /**
     * One message in the conversation.
     *
     * @param role       user | assistant | tool
     * @param text       message text (may be null on a pure tool-call turn)
     * @param toolCalls  calls the assistant requested (assistant turns only)
     * @param toolCallId which call this message answers (tool turns only)
     */
    public record ChatMessage(String role, String text, List<ToolCall> toolCalls, String toolCallId) {

        public static ChatMessage user(String text) {
            return new ChatMessage("user", text, List.of(), null);
        }

        public static ChatMessage assistant(String text, List<ToolCall> calls) {
            return new ChatMessage("assistant", text, calls == null ? List.of() : calls, null);
        }

        public static ChatMessage toolResult(String toolCallId, String text) {
            return new ChatMessage("tool", text, List.of(), toolCallId);
        }
    }

    /**
     * Token usage for one call.
     *
     * <p>{@code cacheReadTokens} is reported by providers that do prompt caching and left at zero
     * by those that don't — it is priced separately because a cache read costs a fraction of fresh
     * input.
     */
    public record TokenUsage(long inputTokens, long outputTokens, long cacheReadTokens,
                             long cacheWriteTokens) {

        public static final TokenUsage NONE = new TokenUsage(0, 0, 0, 0);
    }

    /** What the model sent back. */
    public record ChatReply(String text, List<ToolCall> toolCalls, TokenUsage usage) {

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    /**
     * One request.
     *
     * @param system   system prompt, or null
     * @param messages conversation so far, oldest first
     * @param tools    tools the model may call; empty disables tool calling
     */
    public record ChatRequest(String model, String system, List<ChatMessage> messages,
                              List<ToolSpec> tools, long maxTokens) {
    }
}
