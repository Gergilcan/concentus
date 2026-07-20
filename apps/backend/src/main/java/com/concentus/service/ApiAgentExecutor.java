package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.llm.ChatTypes;
import com.concentus.llm.LlmException;
import com.concentus.llm.LlmProvider;
import com.concentus.llm.ProviderRegistry;
import com.concentus.model.NodeExec;
import com.concentus.model.RunEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs a compiled flow against any chat-completions provider.
 *
 * <p>The other two backends lean on an Anthropic product for orchestration — Claude Code's
 * subagents, or Managed Agents' hosted loop. Neither exists elsewhere, so this one owns the loop:
 * delegation is expressed as an ordinary tool call, which every provider supports.
 *
 * <p>Each agent gets a {@code delegate_to_<name>} tool for the agents wired behind it, so the
 * delegation chains drawn on the canvas work here exactly as they do locally — a reviewer behind
 * an engineer reviews that engineer's work, not the flow's work in general.
 *
 * <p><b>Not supported yet:</b> file editing, bash and MCP — none of which is impossible here, since
 * every provider supports function calling. MCP is an open protocol and reads as Claude-only only
 * because the existing implementation registers servers through {@code claude mcp add}; file tools
 * would reuse the containment {@link ContextFolderResolver} already provides. Bash is the one held
 * back on purpose: flows can be triggered by public webhooks, so model-generated shell commands on
 * the host is a remote-code-execution path, and that wants an explicit design decision rather than
 * a default.
 */
@Component
public class ApiAgentExecutor {

    /** Stops a coordinator that keeps delegating instead of answering. */
    private static final int MAX_TURNS_PER_AGENT = 12;
    /** Delegation is one level on the canvas but chains can nest; bound the recursion. */
    private static final int MAX_DELEGATION_DEPTH = 4;

    private final ProviderRegistry providers;
    private final RagContextInjector ragInjector;
    private final ObjectMapper mapper;
    private final int maxTurns;

    public ApiAgentExecutor(ProviderRegistry providers, RagContextInjector ragInjector,
                            ObjectMapper mapper,
                            @Value("${llm.max-turns-per-agent:12}") int maxTurns) {
        this.providers = providers;
        this.ragInjector = ragInjector;
        this.mapper = mapper;
        this.maxTurns = maxTurns > 0 ? maxTurns : MAX_TURNS_PER_AGENT;
    }

    /** Runs one turn of the flow. Blocking — call on a worker thread. */
    public void runTurn(AgentRun run, CompiledFlow flow, String userText) {
        AgentSpec coordinator = flow.coordinator();
        LlmProvider provider = providers.forModel(coordinator.model.id).orElse(null);
        if (provider == null) {
            fail(run, unconfiguredMessage(coordinator.model.id));
            return;
        }

        if (!run.apiContextPrepared) {
            // Injected once, not per turn — re-running the query every turn would re-read the
            // database and re-append the same rows to the prompt.
            ragInjector.inject(coordinator, run, m -> run.emit(RunEvent.of("system", m)));
            for (AgentSpec sub : flow.subAgents()) {
                ragInjector.inject(sub, run, m -> run.emit(RunEvent.of("system", m)));
            }
            run.apiContextPrepared = true;
            run.emit(RunEvent.of("system", "Running on " + provider.id()
                    + " (" + coordinator.model.id + "). File editing, bash and MCP are Claude-only —"
                    + " this backend does reasoning, delegation and SQL context."));
        }

        NodeExec exec = run.nodeExec(coordinator.nodeId, "agent", coordinator.name);
        if (exec != null) {
            exec.appendInput(userText);
            exec.status = "running";
        }
        run.status = "RUNNING";
        run.emit(RunEvent.of("system", "› " + userText));

        try {
            String answer = runAgent(run, flow, coordinator, userText, 0);
            if (exec != null && !"failed".equals(exec.status)) {
                exec.status = "passed";
                exec.endedAt = System.currentTimeMillis();
            }
            if (answer != null && !answer.isBlank()) {
                run.emit(RunEvent.of("status", "idle"));
            }
            if (!"TERMINATED".equals(run.status)) run.status = "IDLE";
        } catch (LlmException e) {
            markFailed(exec, e.getMessage());
            fail(run, e.getMessage());
        } catch (RuntimeException e) {
            markFailed(exec, e.getMessage());
            fail(run, "Run failed: " + e.getMessage());
        }
    }

    /**
     * Drives one agent to an answer, executing any delegations it asks for along the way.
     *
     * @return the agent's final text, which becomes the tool result for whoever delegated to it
     */
    private String runAgent(AgentRun run, CompiledFlow flow, AgentSpec spec, String task, int depth) {
        LlmProvider provider = providers.forModel(spec.model.id)
                .orElseThrow(() -> new LlmException("none", unconfiguredMessage(spec.model.id)));

        List<ChatTypes.ToolSpec> tools = depth >= MAX_DELEGATION_DEPTH
                ? List.of()
                : delegationTools(flow, spec);

        List<ChatTypes.ChatMessage> messages = new ArrayList<>();
        messages.add(ChatTypes.ChatMessage.user(task));

        String lastText = null;
        for (int turn = 0; turn < maxTurns; turn++) {
            ChatTypes.ChatReply reply = provider.chat(new ChatTypes.ChatRequest(
                    spec.model.id, spec.systemPrompt, messages, tools, spec.model.maxTokens));

            record(run, spec, reply);

            if (reply.text() != null && !reply.text().isBlank()) {
                lastText = reply.text();
                run.emit(RunEvent.of("agent_message", reply.text(), spec.name, spec.nodeId));
            }
            if (!reply.hasToolCalls()) {
                return lastText;
            }

            messages.add(ChatTypes.ChatMessage.assistant(reply.text(), reply.toolCalls()));
            for (ChatTypes.ToolCall call : reply.toolCalls()) {
                String result = executeDelegation(run, flow, spec, call, depth);
                messages.add(ChatTypes.ChatMessage.toolResult(call.id(), result));
            }
        }
        // Hitting the cap is a real outcome, not a silent stop — say so rather than returning
        // whatever half-answer happened to be last.
        run.emit(RunEvent.of("system",
                spec.name + " stopped after " + maxTurns + " turns without finishing.",
                spec.name, spec.nodeId));
        return lastText;
    }

    /** Runs the delegate named by a tool call and returns its answer as the tool result. */
    private String executeDelegation(AgentRun run, CompiledFlow flow, AgentSpec delegator,
                                     ChatTypes.ToolCall call, int depth) {
        AgentSpec target = findByToolName(flow, delegator, call.name());
        if (target == null) {
            // Reported back to the model rather than thrown: it can pick a valid agent instead of
            // the whole run dying on one bad name.
            return "No agent named '" + call.name() + "' is available to you.";
        }

        String task = extractTask(call.argumentsJson());
        NodeExec targetExec = run.nodeExec(target.nodeId, "agent", target.name);
        if (targetExec != null) {
            targetExec.status = "running";
            targetExec.appendInput(task);
        }
        run.emit(RunEvent.of("tool_use", "Delegate → " + target.name,
                delegator.name, delegator.nodeId));

        try {
            String answer = runAgent(run, flow, target, task, depth + 1);
            if (targetExec != null) {
                if (answer != null) targetExec.appendOutput(answer);
                if (!"failed".equals(targetExec.status)) {
                    targetExec.status = "passed";
                    targetExec.endedAt = System.currentTimeMillis();
                }
            }
            return answer == null ? "(no answer)" : answer;
        } catch (LlmException e) {
            markFailed(targetExec, e.getMessage());
            run.emit(RunEvent.of("error", target.name + " failed: " + e.getMessage(),
                    target.name, target.nodeId));
            // Handed back as a tool result so the delegator can react rather than the run dying.
            return "That agent failed: " + e.getMessage();
        }
    }

    /** One tool per agent wired behind this one, so its roster is exactly what it may call. */
    private List<ChatTypes.ToolSpec> delegationTools(CompiledFlow flow, AgentSpec spec) {
        List<ChatTypes.ToolSpec> tools = new ArrayList<>();
        for (String cliName : spec.delegatesTo) {
            AgentSpec target = byCliName(flow, cliName);
            if (target == null) continue;
            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");
            ObjectNode props = schema.putObject("properties");
            props.putObject("task").put("type", "string")
                    .put("description", "The work for this agent, with everything it needs to know.");
            schema.putArray("required").add("task");
            schema.put("additionalProperties", false);

            String description = target.description == null || target.description.isBlank()
                    ? "Delegate work to " + target.name + "."
                    : target.description;
            tools.add(new ChatTypes.ToolSpec(toolNameFor(cliName), description, schema));
        }
        return tools;
    }

    /** Tool names are constrained to a safe charset by most providers. */
    static String toolNameFor(String cliName) {
        return "delegate_to_" + cliName.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private AgentSpec findByToolName(CompiledFlow flow, AgentSpec delegator, String toolName) {
        for (String cliName : delegator.delegatesTo) {
            if (toolNameFor(cliName).equals(toolName)) return byCliName(flow, cliName);
        }
        return null;
    }

    private static AgentSpec byCliName(CompiledFlow flow, String cliName) {
        if (cliName == null) return null;
        if (cliName.equals(flow.coordinator().cliName)) return flow.coordinator();
        for (AgentSpec s : flow.subAgents()) {
            if (cliName.equals(s.cliName)) return s;
        }
        return null;
    }

    /** Pulls the `task` argument, falling back to the raw JSON so nothing is silently dropped. */
    private String extractTask(String argumentsJson) {
        try {
            JsonNode node = mapper.readTree(argumentsJson == null ? "{}" : argumentsJson);
            String task = node.path("task").asText("");
            return task.isBlank() ? node.toString() : task;
        } catch (Exception e) {
            return argumentsJson == null ? "" : argumentsJson;
        }
    }

    /** Attributes usage to the agent that spent it, matching the other backends. */
    private void record(AgentRun run, AgentSpec spec, ChatTypes.ChatReply reply) {
        NodeExec exec = run.nodeExec(spec.nodeId, "agent", spec.name);
        ChatTypes.TokenUsage usage = reply.usage() == null ? ChatTypes.TokenUsage.NONE : reply.usage();
        if (exec != null) {
            exec.inputTokens += usage.inputTokens();
            exec.outputTokens += usage.outputTokens();
            exec.cacheReadTokens += usage.cacheReadTokens();
            exec.cacheWriteTokens += usage.cacheWriteTokens();
            if (reply.text() != null && !reply.text().isBlank()) exec.appendOutput(reply.text());
        }
        run.totalInputTokens += usage.inputTokens();
        run.totalOutputTokens += usage.outputTokens();
        run.cacheReadTokens += usage.cacheReadTokens();
        run.cacheWriteTokens += usage.cacheWriteTokens();
    }

    private String unconfiguredMessage(String model) {
        String providerId = providers.providerIdForModel(model).orElse(null);
        if (providerId == null) {
            return "No provider is configured for model '" + model + "'. Add one via "
                    + "llm.openai-compatible / llm.model-prefixes, or pick a known model.";
        }
        if ("anthropic".equals(providerId)) {
            return "Model '" + model + "' is a Claude model — run this flow on the local "
                    + "(subscription) or cloud backend rather than the api backend.";
        }
        return "Provider '" + providerId + "' has no credential configured, so '" + model
                + "' can't run. Set its API key (see llm.* settings).";
    }

    private static void markFailed(NodeExec exec, String error) {
        if (exec == null) return;
        exec.status = "failed";
        exec.error = error;
        exec.endedAt = System.currentTimeMillis();
    }

    private static void fail(AgentRun run, String message) {
        run.status = "ERROR";
        run.error = message;
        run.emit(RunEvent.of("error", message));
    }
}
