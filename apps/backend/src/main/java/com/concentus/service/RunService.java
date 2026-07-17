package com.concentus.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsStreamSessionEvents;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsUserMessageEventParams;
import com.anthropic.models.beta.sessions.events.EventSendParams;
import com.concentus.model.FlowGraph;
import com.concentus.model.RunEvent;
import com.concentus.model.RunSummary;
import com.concentus.model.TriggerSpec;
import com.concentus.support.AnthropicClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Launches flows, keeps their sessions streaming, and routes commands to them. */
@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    private final AnthropicClientProvider clientProvider;
    private final FlowCompiler compiler;
    private final ManagedFlowLauncher launcher;
    private final LocalClaudeExecutor localExecutor;
    private final ExecutorService exec = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, AgentRun> runs = new ConcurrentHashMap<>();

    public RunService(AnthropicClientProvider clientProvider, FlowCompiler compiler,
                      ManagedFlowLauncher launcher, LocalClaudeExecutor localExecutor) {
        this.clientProvider = clientProvider;
        this.compiler = compiler;
        this.launcher = launcher;
        this.localExecutor = localExecutor;
    }

    public RunSummary start(FlowGraph flow) {
        return start(flow, null);
    }

    /**
     * Starts a run. When {@code initialPromptOverride} is non-null it becomes the first turn
     * (used by webhook triggers to inject the event payload); otherwise the Input node's own
     * prompt is used for prompt/cron modes.
     */
    public RunSummary start(FlowGraph flow, String initialPromptOverride) {
        // Compile synchronously so validation errors surface to the caller immediately.
        CompiledFlow compiled = compiler.compile(flow);
        TriggerSpec trigger = TriggerSpec.from(flow);

        String backend = clientProvider.backend();
        if ("none".equals(backend)) {
            throw new IllegalStateException("Not signed in. Sign in to Claude Code (`claude`) to run on your "
                    + "subscription, or set ANTHROPIC_API_KEY to use the cloud API.");
        }

        String runId = "run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        AgentRun run = new AgentRun(runId, flow.id(), flow.name(), flow.modeOrDefault());
        run.backend = backend;
        run.compiled = compiled;
        run.trigger = trigger.mode() == null ? "manual" : trigger.mode().toLowerCase();
        run.pendingPrompt = initialPromptOverride != null
                ? initialPromptOverride
                : (trigger.autoStart() ? trigger.prompt() : null);
        runs.put(runId, run);

        if ("local".equals(backend)) {
            // Local: agents run via the claude CLI on the subscription.
            run.localSessionId = UUID.randomUUID().toString();
            run.status = "IDLE";
            if (run.pendingPrompt != null) {
                run.emit(RunEvent.of("system", "Local mode — auto-starting with the Input prompt."));
                String prompt = run.pendingPrompt;
                run.pendingPrompt = null;
                exec.submit(() -> localExecutor.runTurn(run, run.compiled, prompt));
            } else {
                run.emit(RunEvent.of("system", "Local mode — running on your Claude subscription ("
                        + (compiled.subAgents().size() + 1) + " agents). Send a command to start."));
            }
        } else {
            run.emit(RunEvent.of("system", "Launching flow '" + flow.name() + "' in the cloud ("
                    + (compiled.subAgents().size() + 1) + " agents)…"));
            exec.submit(() -> execute(run, compiled));
        }
        return run.toSummary();
    }

    private void execute(AgentRun run, CompiledFlow compiled) {
        AnthropicClient client;
        try {
            client = clientProvider.client();
        } catch (Exception e) {
            fail(run, "No Anthropic credentials available. Run `ant auth login` (Claude login) "
                    + "or set ANTHROPIC_API_KEY. Details: " + e.getMessage());
            return;
        }
        try {
            var result = launcher.launch(client, compiled, msg -> run.emit(RunEvent.of("system", msg)));
            run.sessionId = result.sessionId();
            List<String> ids = new ArrayList<>();
            ids.add(result.coordinatorId());
            ids.addAll(result.subAgentIds());
            run.agentIds = ids;
            run.status = "RUNNING";
            run.emit(RunEvent.of("system", "Session " + result.sessionId() + " ready — coordinator "
                    + result.coordinatorId() + " + " + result.subAgentIds().size() + " sub-agent(s). "
                    + "Send a command to start work."));
            // Auto-start with the Input prompt, if the trigger asked for it.
            if (run.pendingPrompt != null) {
                String prompt = run.pendingPrompt;
                run.pendingPrompt = null;
                try {
                    sendCommand(run.id, prompt);
                } catch (Exception e) {
                    run.emit(RunEvent.of("system", "Could not auto-start with the Input prompt: " + e.getMessage()));
                }
            }
            streamLoop(client, run);
        } catch (Exception e) {
            log.warn("run {} failed", run.id, e);
            fail(run, e.getMessage());
        }
    }

    private void streamLoop(AnthropicClient client, AgentRun run) {
        try (var stream = client.beta().sessions().events().streamStreaming(run.sessionId)) {
            run.stream = stream;
            for (BetaManagedAgentsStreamSessionEvents ev :
                    (Iterable<BetaManagedAgentsStreamSessionEvents>) stream.stream()::iterator) {
                forward(run, ev);
                if (ev.isSessionStatusTerminated()) break;
            }
        } catch (Exception e) {
            if (!"TERMINATED".equals(run.status)) {
                run.emit(RunEvent.of("system", "Output stream closed: " + e.getMessage()));
            }
        } finally {
            if (!"ERROR".equals(run.status)) {
                run.status = "TERMINATED";
                run.emit(RunEvent.of("status", "terminated"));
            }
        }
    }

    private void forward(AgentRun run, BetaManagedAgentsStreamSessionEvents ev) {
        if (ev.isAgentMessage()) {
            StringBuilder sb = new StringBuilder();
            ev.asAgentMessage().content().forEach(b -> sb.append(b.text()));
            run.emit(RunEvent.of("agent_message", sb.toString(), "coordinator"));
        } else if (ev.isAgentThreadMessageReceived()) {
            run.emit(RunEvent.of("system", "A sub-agent returned results to the coordinator."));
        } else if (ev.isAgentToolUse()) {
            run.emit(RunEvent.of("tool_use", ev.asAgentToolUse().name()));
        } else if (ev.isAgentMcpToolUse()) {
            run.emit(RunEvent.of("tool_use", "(MCP tool)"));
        } else if (ev.isSessionThreadCreated()) {
            run.emit(RunEvent.of("system", "Sub-agent thread started."));
        } else if (ev.isSessionStatusRunning()) {
            run.status = "RUNNING";
            run.emit(RunEvent.of("status", "running"));
        } else if (ev.isSessionStatusIdle()) {
            run.status = "IDLE";
            run.emit(RunEvent.of("status", "idle"));
        } else if (ev.isSessionError()) {
            run.emit(RunEvent.of("error", "Session reported an error."));
        }
    }

    /** Sends an explicit instruction to a running session. */
    public void sendCommand(String runId, String text) {
        AgentRun run = require(runId);

        if ("local".equals(run.backend)) {
            if (run.compiled == null) {
                throw new IllegalStateException("Run is not ready yet.");
            }
            exec.submit(() -> localExecutor.runTurn(run, run.compiled, text));
            return;
        }

        if (run.sessionId == null) {
            throw new IllegalStateException("Run is not ready yet.");
        }
        AnthropicClient client = clientProvider.client();
        run.emit(RunEvent.of("system", "› " + text));
        client.beta().sessions().events().send(run.sessionId, EventSendParams.builder()
                .addEvent(BetaManagedAgentsUserMessageEventParams.builder()
                        .type(BetaManagedAgentsUserMessageEventParams.Type.USER_MESSAGE)
                        .addTextContent(text)
                        .build())
                .build());
    }

    public void stop(String runId) {
        AgentRun run = require(runId);

        if ("local".equals(run.backend)) {
            localExecutor.stop(run);
            run.emit(RunEvent.of("status", "terminated"));
            return;
        }

        run.status = "TERMINATED";
        AutoCloseable s = run.stream;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
        run.emit(RunEvent.of("status", "terminated"));
    }

    public List<RunSummary> list() {
        List<RunSummary> out = new ArrayList<>();
        runs.values().forEach(r -> out.add(r.toSummary()));
        out.sort(Comparator.comparingLong(RunSummary::createdAt).reversed());
        return out;
    }

    public Optional<AgentRun> get(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    /** True if a non-terminal run for this flow already exists (used to avoid overlapping cron fires). */
    public boolean hasActiveRun(String flowId) {
        if (flowId == null) return false;
        return runs.values().stream().anyMatch(r -> flowId.equals(r.flowId)
                && !"TERMINATED".equals(r.status) && !"ERROR".equals(r.status));
    }

    private AgentRun require(String runId) {
        AgentRun run = runs.get(runId);
        if (run == null) throw new IllegalArgumentException("No such run: " + runId);
        return run;
    }

    private void fail(AgentRun run, String message) {
        run.status = "ERROR";
        run.error = message;
        run.emit(RunEvent.of("error", message));
    }
}
