package com.concentus.llm;

/**
 * One model vendor, reduced to a single blocking chat call.
 *
 * <p>Implementations own their wire format and auth; nothing above this interface knows whether
 * it is talking to OpenAI, Gemini, DeepSeek or a local server. Adding a vendor means adding an
 * implementation, not touching the orchestrator.
 */
public interface LlmProvider {

    /** Stable id used in config and shown in the UI, e.g. "openai", "gemini", "deepseek". */
    String id();

    /** Whether this provider is configured well enough to be used (i.e. has a credential). */
    boolean isConfigured();

    /**
     * Runs one turn. Blocking — callers run it on a worker thread.
     *
     * @throws LlmException on transport failure or a non-2xx response
     */
    ChatTypes.ChatReply chat(ChatTypes.ChatRequest request);
}
