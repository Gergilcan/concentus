package com.concentus.runner;

import com.anthropic.client.AnthropicClient;
import com.concentus.config.AgentSpec;

/** Selects the runner implementation from {@code spec.mode}. */
public final class AgentRunnerFactory {

    private AgentRunnerFactory() {}

    public static AgentRunner create(AgentSpec spec, AnthropicClient client) {
        switch (spec.runMode()) {
            case MANAGED:
                return new ManagedAgentRunner(spec, client);
            case LOCAL:
                return new SelfHostedAgentRunner(spec, client);
            default:
                throw new IllegalStateException("Unhandled mode: " + spec.mode);
        }
    }
}
