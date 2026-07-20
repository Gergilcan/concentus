package com.concentus.execution;

import com.concentus.llm.ProviderRegistry;
import com.concentus.service.AgentRun;
import com.concentus.service.ApiAgentExecutor;
import com.concentus.service.CompiledFlow;
import org.springframework.stereotype.Component;

/**
 * Chat-completions providers (OpenAI, Gemini, Vertex, DeepSeek, Azure, local servers).
 *
 * <p>Stays in app-backend rather than becoming its own service: these are stateless HTTPS calls
 * with no local runtime and no process to supervise, so a separate container would add a hop and a
 * deployment unit without isolating anything.
 */
@Component
public class ApiExecutionBackend implements ExecutionBackend {

    private final ProviderRegistry providers;
    private final ApiAgentExecutor executor;

    public ApiExecutionBackend(ProviderRegistry providers, ApiAgentExecutor executor) {
        this.providers = providers;
        this.executor = executor;
    }

    @Override
    public String id() {
        return "api";
    }

    @Override
    public String displayName() {
        return "Model APIs (OpenAI, Gemini, DeepSeek, …)";
    }

    @Override
    public boolean isAvailable() {
        return !providers.available().isEmpty();
    }

    @Override
    public boolean supportsModel(String model) {
        return providers.forModel(model).isPresent();
    }

    @Override
    public void runTurn(AgentRun run, CompiledFlow flow, String userText) {
        executor.runTurn(run, flow, userText);
    }
}
