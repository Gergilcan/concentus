package com.concentus.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsStreamSessionEvents;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsUserMessageEventParams;
import com.anthropic.models.beta.sessions.events.EventSendParams;
import com.concentus.model.FlowGraph;
import com.concentus.model.RunEvent;
import com.concentus.model.RunSummary;
import com.concentus.model.TriggerSpec;
import com.concentus.store.RunStore;
import com.concentus.support.AnthropicClientProvider;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Launches flows, keeps their sessions streaming, and routes commands to them. */
@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    private final AnthropicClientProvider clientProvider;
    private final FlowCompiler compiler;
    private final ManagedFlowLauncher launcher;
    private final LocalClaudeExecutor localExecutor;
    private final RunStore runStore;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final NotificationService notifier;
    private final ExecutorService exec;
    private final int maxRetainedRuns;
    private final double inputUsdPerMTok;
    private final double outputUsdPerMTok;
    private final ConcurrentHashMap<String, AgentRun> runs = new ConcurrentHashMap<>();

    public RunService(AnthropicClientProvider clientProvider, FlowCompiler compiler,
                      ManagedFlowLauncher launcher, LocalClaudeExecutor localExecutor,
                      RunStore runStore, com.fasterxml.jackson.databind.ObjectMapper mapper,
                      NotificationService notifier,
                      @Value("${runs.max-concurrent:8}") int maxConcurrent,
                      @Value("${runs.queue-capacity:64}") int queueCapacity,
                      @Value("${runs.max-retained:200}") int maxRetainedRuns,
                      @Value("${pricing.input-usd-per-mtok:3.0}") double inputUsdPerMTok,
                      @Value("${pricing.output-usd-per-mtok:15.0}") double outputUsdPerMTok) {
        this.clientProvider = clientProvider;
        this.compiler = compiler;
        this.launcher = launcher;
        this.localExecutor = localExecutor;
        this.runStore = runStore;
        this.mapper = mapper;
        this.notifier = notifier;
        this.maxRetainedRuns = maxRetainedRuns;
        this.inputUsdPerMTok = inputUsdPerMTok;
        this.outputUsdPerMTok = outputUsdPerMTok;
        AtomicInteger threadCount = new AtomicInteger(1);
        ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r, "run-worker-" + threadCount.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        // Bounded pool: each worker blocks for a full agent turn, so an unbounded cached pool
        // could exhaust host threads under load. Excess work queues up to queueCapacity, then
        // submissions throw RejectedExecutionException (caught at call sites) instead of
        // spawning unbounded threads.
        this.exec = new ThreadPoolExecutor(maxConcurrent, maxConcurrent, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity), threadFactory, new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    public void shutdown() {
        exec.shutdown();
        try {
            if (!exec.awaitTermination(5, TimeUnit.SECONDS)) {
                exec.shutdownNow();
            }
        } catch (InterruptedException e) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
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
        run.flowJson = toJson(flow);
        run.notifyWebhook = flow.notifyWebhook();
        run.inputUsdPerMTok = inputUsdPerMTok;
        run.outputUsdPerMTok = outputUsdPerMTok;
        run.trigger = trigger.mode() == null ? "manual" : trigger.mode().toLowerCase();
        run.pendingPrompt = initialPromptOverride != null
                ? initialPromptOverride
                : (trigger.autoStart() ? trigger.prompt() : null);
        run.initialPrompt = run.pendingPrompt;
        runs.put(runId, run);
        evictOldestCompleted();
        trackForPersistence(run);
        runStore.persist(run);

        if ("local".equals(backend)) {
            // Local: agents run via the claude CLI on the subscription.
            run.localSessionId = UUID.randomUUID().toString();
            run.status = "IDLE";
            if (run.pendingPrompt != null) {
                run.emit(RunEvent.of("system", "Local mode — auto-starting with the Input prompt."));
                String prompt = run.pendingPrompt;
                run.pendingPrompt = null;
                try {
                    exec.submit(() -> runLocalTurn(run, prompt));
                } catch (RejectedExecutionException e) {
                    fail(run, "Too many runs in progress right now. Please try again shortly.");
                }
            } else {
                run.emit(RunEvent.of("system", "Local mode — running on your Claude subscription ("
                        + (compiled.subAgents().size() + 1) + " agents). Send a command to start."));
            }
        } else {
            run.emit(RunEvent.of("system", "Launching flow '" + flow.name() + "' in the cloud ("
                    + (compiled.subAgents().size() + 1) + " agents)…"));
            try {
                exec.submit(() -> execute(run, compiled));
            } catch (RejectedExecutionException e) {
                fail(run, "Too many runs in progress right now. Please try again shortly.");
            }
        }
        return run.toSummary();
    }

    /**
     * Keeps the run registry bounded: once over {@code maxRetainedRuns}, evicts the oldest
     * completed (TERMINATED/ERROR) runs first. Active/running runs are never evicted, so the
     * registry can briefly exceed the cap while runs are still in flight.
     */
    private void evictOldestCompleted() {
        int overflow = runs.size() - maxRetainedRuns;
        if (overflow <= 0) return;
        runs.values().stream()
                .filter(r -> "TERMINATED".equals(r.status) || "ERROR".equals(r.status))
                .sorted(Comparator.comparingLong(r -> r.createdAt))
                .limit(overflow)
                .map(r -> r.id)
                .forEach(runs::remove);
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
            runStore.persist(run);
        }
    }

    /** Runs one local turn, then snapshots the run and notifies if it failed. */
    private void runLocalTurn(AgentRun run, String prompt) {
        try {
            localExecutor.runTurn(run, run.compiled, prompt);
        } finally {
            runStore.persist(run);
            if ("ERROR".equals(run.status)) {
                notifier.runFailed(run);
            }
        }
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private void forward(AgentRun run, BetaManagedAgentsStreamSessionEvents ev) {
        if (ev.isAgentMessage()) {
            StringBuilder sb = new StringBuilder();
            ev.asAgentMessage().content().forEach(b -> sb.append(b.text()));
            var coord = coordExec(run);
            if (coord != null) coord.appendOutput(sb.toString());
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

    private com.concentus.model.NodeExec coordExec(AgentRun run) {
        if (run.compiled == null) return null;
        var c = run.compiled.coordinator();
        return run.nodeExec(c.nodeId, "agent", c.name);
    }

    /**
     * Re-runs an execution from its stored flow snapshot with the same initial input, as a new run.
     */
    public RunSummary retry(String runId) {
        AgentRun old = require(runId);
        if (old.flowJson == null) {
            throw new IllegalStateException("This execution has no stored flow to retry.");
        }
        FlowGraph flow;
        try {
            flow = mapper.readValue(old.flowJson, FlowGraph.class);
        } catch (Exception e) {
            throw new IllegalStateException("Stored flow for this execution could not be read: " + e.getMessage());
        }
        return start(flow, old.initialPrompt);
    }

    /** Sends an explicit instruction to a running session. */
    public void sendCommand(String runId, String text) {
        AgentRun run = require(runId);
        // The first instruction a manual run receives is what a retry should replay.
        if (run.initialPrompt == null || run.initialPrompt.isBlank()) {
            run.initialPrompt = text;
        }

        if ("local".equals(run.backend)) {
            if (run.compiled == null) {
                throw new IllegalStateException("Run is not ready yet.");
            }
            try {
                exec.submit(() -> runLocalTurn(run, text));
            } catch (RejectedExecutionException e) {
                throw new IllegalStateException("Too many runs in progress right now. Please try again shortly.");
            }
            return;
        }

        if (run.sessionId == null) {
            throw new IllegalStateException("Run is not ready yet.");
        }
        AnthropicClient client = clientProvider.client();
        var coord = coordExec(run);
        if (coord != null) { coord.appendInput(text); coord.status = "running"; }
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
            runStore.persist(run);
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
        runStore.persist(run);
    }

    /** Reload persisted runs on startup so they survive restarts and can be continued. */
    @EventListener(ApplicationReadyEvent.class)
    public void restore() {
        for (RunStore.RunRow row : runStore.loadAll(maxRetainedRuns)) {
            try {
                AgentRun run = new AgentRun(row.id(), row.flowId(), row.flowName(), row.mode());
                run.createdAt = row.createdAt();
                run.backend = row.backend();
                // A run that was mid-flight when the server stopped can be continued, not resumed
                // in place — surface it as IDLE so the user can send the next command.
                run.status = "RUNNING".equals(row.status()) || "STARTING".equals(row.status())
                        ? "IDLE" : row.status();
                run.trigger = row.trigger();
                run.sessionId = row.sessionId();
                run.localSessionId = row.localSessionId();
                run.localStarted = row.localStarted();
                run.error = row.error();
                run.totalInputTokens = row.totalInputTokens();
                run.totalOutputTokens = row.totalOutputTokens();
                run.flowJson = row.flowJson();
                run.initialPrompt = row.initialPrompt();
                run.notifyWebhook = row.notifyWebhook();
                run.inputUsdPerMTok = inputUsdPerMTok;
                run.outputUsdPerMTok = outputUsdPerMTok;
                run.restoreEvents(row.events());
                run.restoreNodeExecs(row.nodeExecs());
                if (row.flowJson() != null) {
                    run.compiled = compiler.compile(mapper.readValue(row.flowJson(), FlowGraph.class));
                }
                trackForPersistence(run); // continued runs keep streaming to the database too
                runs.put(run.id, run);
            } catch (Exception e) {
                log.warn("Could not restore run {}: {}", row.id(), e.getMessage());
            }
        }
        if (runStore.isAvailable()) {
            log.info("Restored {} run(s) from the database.", runs.size());
        }
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

    /**
     * Snapshots the run to the database as it streams. Every emitted event marks it dirty; the
     * store coalesces those into one write every couple of seconds, so a block's input/output is
     * durable while the turn is still running rather than only when it finishes.
     */
    private void trackForPersistence(AgentRun run) {
        run.addListener(e -> runStore.markDirty(run));
    }

    /** The flow snapshot this run executed, if one was stored. */
    public Optional<FlowGraph> flowOf(AgentRun run) {
        if (run.flowJson == null || run.flowJson.isBlank()) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(run.flowJson, FlowGraph.class));
        } catch (Exception e) {
            log.warn("Stored flow for run {} could not be read: {}", run.id, e.getMessage());
            return Optional.empty();
        }
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
        runStore.persist(run);
        notifier.runFailed(run);
    }
}
