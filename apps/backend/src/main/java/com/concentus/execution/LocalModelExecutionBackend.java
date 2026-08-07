package com.concentus.execution;

import com.concentus.llm.LocalModelCatalog;
import com.concentus.llm.LocalModelClient;
import com.concentus.service.AgentRun;
import com.concentus.service.CompiledFlow;
import com.concentus.service.LocalModelAgentExecutor;
import org.springframework.stereotype.Component;

/**
 * A model running on your own hardware, through an OpenAI-compatible server.
 *
 * <p>Ollama, llama.cpp, vLLM, LM Studio. Stays in app-backend rather than becoming its own
 * service: these are stateless HTTP calls to something already running elsewhere, so a separate
 * container would add a hop and a deployment unit without isolating anything. The heavy part —
 * the weights and the GPU — is the server's problem, not this process's.
 *
 * <p><b>Availability means the server answered.</b> Not that a URL is configured: a backend that
 * claims to be up while nothing is listening sends the run somewhere it cannot execute, and the
 * failure then arrives mid-turn rather than at launch.
 */
@Component
public class LocalModelExecutionBackend implements ExecutionBackend {

    private final LocalModelClient client;
    private final LocalModelCatalog catalog;
    private final LocalModelAgentExecutor executor;

    public LocalModelExecutionBackend(LocalModelClient client, LocalModelCatalog catalog,
                                      LocalModelAgentExecutor executor) {
        this.client = client;
        this.catalog = catalog;
        this.executor = executor;
    }

    @Override
    public String id() {
        return LocalModelClient.ID;
    }

    @Override
    public String displayName() {
        return client.isConfigured()
                ? "Self-hosted model (" + client.baseUrl() + ")"
                : "Self-hosted model (Ollama, llama.cpp, vLLM)";
    }

    @Override
    public boolean isAvailable() {
        return catalog.isAvailable();
    }

    /**
     * Claims exactly the models the server says it has.
     *
     * <p>Not a prefix rule, which is how the other backends do it. Local ids are whatever the
     * runner calls them — {@code qwen3:14b} on Ollama, {@code Qwen/Qwen3-14B} on vLLM — and no
     * prefix covers that without also swallowing model names this server has never heard of.
     */
    @Override
    public boolean supportsModel(String model) {
        return catalog.serves(model);
    }

    @Override
    public void runTurn(AgentRun run, CompiledFlow flow, String userText) {
        executor.runTurn(run, flow, userText);
    }

    /**
     * There is no child process to kill — the work is happening in the model server.
     *
     * <p>Marking the run terminated is what stops the agent loop: it checks the status between
     * turns, so an in-flight generation finishes and nothing further is dispatched.
     */
    @Override
    public void stop(AgentRun run) {
        run.status = "TERMINATED";
    }
}
