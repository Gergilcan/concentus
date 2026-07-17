package com.concentus.runner;

/** Drives an agent turn from a single user prompt, writing the agent's output to stdout. */
public interface AgentRunner {

    /**
     * Runs the agent against {@code userPrompt} until it finishes (or the session goes idle).
     *
     * @throws Exception if the API call or stream fails
     */
    void run(String userPrompt) throws Exception;
}
