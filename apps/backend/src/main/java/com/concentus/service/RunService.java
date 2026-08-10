package com.concentus.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsStreamSessionEvents;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsUserMessageEventParams;
import com.anthropic.models.beta.sessions.events.EventSendParams;
import com.concentus.model.FlowNode;
import com.concentus.model.NodeExec;
import com.concentus.model.FlowGraph;
import com.concentus.model.RunEvent;
import com.concentus.model.RunSummary;
import com.concentus.model.TriggerSpec;
import com.concentus.store.RunStore;
import com.concentus.support.AnthropicClientProvider;
import com.concentus.support.Ids;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import com.concentus.execution.ExecutionBackend;
import com.concentus.execution.ExecutionBackends;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
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
    private final ExecutionBackends backends;
    private final CloudStreamEventHandler cloudEvents;
    private final RunStore runStore;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final NotificationService notifier;
    private final ExecutorService exec;
    private final int maxRetainedRuns;
    private final PricingTable pricing;
    private final double inputUsdPerMTok;
    private final double outputUsdPerMTok;
    private final ConcurrentHashMap<String, AgentRun> runs = new ConcurrentHashMap<>();

    public RunService(AnthropicClientProvider clientProvider, FlowCompiler compiler,
                      ManagedFlowLauncher launcher, ExecutionBackends backends, PricingTable pricing,
                      CloudStreamEventHandler cloudEvents,
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
        this.backends = backends;
        this.pricing = pricing;
        this.cloudEvents = cloudEvents;
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
        // spawning unbounded threads. A capacity of 0 means "no queueing" — LinkedBlockingQueue
        // rejects a 0 capacity outright, so that case uses a SynchronousQueue (direct handoff)
        // instead, which is the closest equivalent.
        BlockingQueue<Runnable> queue = queueCapacity <= 0
                ? new SynchronousQueue<>()
                : new LinkedBlockingQueue<>(queueCapacity);
        this.exec = new ThreadPoolExecutor(maxConcurrent, maxConcurrent, 60L, TimeUnit.SECONDS,
                queue, threadFactory, new ThreadPoolExecutor.AbortPolicy());
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
     * Refuses a start once the flow's monthly spend has reached its ceiling.
     *
     * <p>Checked at start, not mid-run: a run in flight finishes — cutting an agent off mid-task
     * leaves half-done work that costs more to untangle than the tokens saved. The month is the
     * calendar month in the machine's own timezone, which is the month the user's invoice thinks
     * in. Ad-hoc runs of an unsaved canvas have no flow id and no history to sum, so no ceiling.
     */
    private void enforceBudget(FlowGraph flow) {
        if (flow.id() == null || flow.budgetUsd() == null || flow.budgetUsd() <= 0) return;
        long monthStart = java.time.LocalDate.now().withDayOfMonth(1)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        double spent = runStore.spendUsdSince(flow.id(), monthStart);
        if (spent >= flow.budgetUsd()) {
            throw new IllegalStateException(String.format(java.util.Locale.ROOT,
                    "Budget reached: '%s' has spent $%.2f of its $%.2f monthly ceiling. "
                            + "Raise the budget in the flow's settings, or wait for next month.",
                    flow.name(), spent, flow.budgetUsd()));
        }
    }

    /**
     * Why a flow has nowhere to run, phrased around what the flow actually asks for.
     *
     * <p>"Not signed in" is the right answer for a Claude model and the wrong one for a
     * self-hosted model that simply isn't loaded — sending someone to `claude setup-token` when
     * their Ollama server is down wastes the afternoon.
     */
    private String unroutableMessage(String model) {
        String base = "Not signed in. Sign in to Claude Code (`claude`) to run on your "
                + "subscription, or set ANTHROPIC_API_KEY to use the cloud API.";
        if (model == null || model.isBlank()) return base;
        // A backend that exists but is not answering is the more useful thing to report: it means
        // the flow is configured correctly and the server is simply not up. The advice comes from
        // the down backend itself — the last id comparison here sent everyone to `ollama serve`,
        // whatever runtime a future backend actually needs.
        return backends.all().stream()
                .filter(b -> !b.isAvailable())
                .map(b -> b.unavailableHint(model))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .map(hint -> hint + " If '" + model + "' was meant to be a Claude model instead: " + base)
                .orElse(base);
    }

    /**
     * Records what the trigger handed the flow, on the Input node's own box.
     *
     * <p>Without this the Input node is the one box in the run with nothing in it — which is
     * precisely backwards for a mail trigger, where the email <em>is</em> the run's whole reason
     * for existing and the first thing anyone wants to read when checking what happened. The
     * console shows it too, but buried under everything the agent then did.
     *
     * <p>Marked {@code passed} immediately: a trigger's work is finished the moment it produced
     * this text, and leaving it "running" forever would misreport a node that already succeeded.
     */
    private static void recordTriggerInput(AgentRun run, FlowGraph flow, TriggerSpec trigger) {
        String inputNodeId = null;
        for (FlowNode n : flow.nodesOrEmpty()) {
            if ("input".equalsIgnoreCase(n.type())) {
                inputNodeId = n.id();
                break;
            }
        }
        if (inputNodeId == null) return;

        String mode = trigger.mode() == null ? "manual" : trigger.mode().toLowerCase();
        NodeExec exec = run.nodeExec(inputNodeId, "input", "Input (" + mode + ")");
        if (exec == null) return;
        if (run.initialPrompt != null && !run.initialPrompt.isBlank()) {
            exec.appendOutput(run.initialPrompt);
            exec.status = "passed";
        } else {
            // Manual mode: nothing was handed over, and saying so beats an empty panel that reads
            // as a node that failed to produce anything.
            exec.appendOutput("_Waiting for the first message — this run starts when you send one._");
            exec.status = "passed";
        }
        exec.endedAt = System.currentTimeMillis();
    }

    /**
     * Starts a run. When {@code initialPromptOverride} is non-null it becomes the first turn
     * (used by webhook triggers to inject the event payload); otherwise the Input node's own
     * prompt is used for prompt/cron modes.
     */
    public RunSummary start(FlowGraph flow, String initialPromptOverride) {
        enforceBudget(flow);
        // Compile synchronously so validation errors surface to the caller immediately.
        CompiledFlow compiled = compiler.compile(flow);
        TriggerSpec trigger = TriggerSpec.from(flow);

        // The model decides where the flow runs, and only then does the credential.
        //
        // Asked in this order because a flow naming a self-hosted model must not be sent to Claude
        // — and must not be refused for lacking a Claude credential it was never going to use.
        // A backend only claims a model it can actually serve right now, so an unclaimed model
        // falls through to the Claude paths exactly as before.
        String coordinatorModel = compiled.coordinator().model == null
                ? null : compiled.coordinator().model.id;
        var claimed = backends.forModel(coordinatorModel).filter(ExecutionBackend::isTurnBased);

        String backend;
        if (claimed.isPresent()) {
            backend = claimed.get().id();
        } else {
            // Which Claude credential is available decides the rest: the local CLI on a
            // subscription, or the hosted Managed Agents API on a key.
            backend = clientProvider.backend();
            if ("none".equals(backend)) {
                throw new IllegalStateException(unroutableMessage(coordinatorModel));
            }
        }

        String runId = Ids.generate("run_", 12);
        AgentRun run = new AgentRun(runId, flow.id(), flow.name(), flow.modeOrDefault());
        run.backend = backend;
        run.compiled = compiled;
        run.flowJson = toJson(flow);
        run.notifyWebhook = flow.notifyWebhook();
        run.pricing = pricing;
                run.inputUsdPerMTok = inputUsdPerMTok;
        run.outputUsdPerMTok = outputUsdPerMTok;
        run.trigger = trigger.mode() == null ? "manual" : trigger.mode().toLowerCase();
        run.permissionMode = trigger.permissionMode();
        // Shadow mode: a triggered run plans but never acts, so you can watch what a trigger
        // WOULD have done for a few days before trusting it. Manual runs stay real — you are
        // present for those, and shadowing them would just be a confusing plan mode. The override
        // sits after the normal assignment so the run records both facts honestly.
        boolean triggered = initialPromptOverride != null
                || (trigger.autoStart() && !"manual".equals(run.trigger));
        if (trigger.shadow() && triggered) {
            run.shadow = true;
            run.permissionMode = "plan";
            run.trigger = run.trigger + " (shadow)";
        }
        run.pendingPrompt = initialPromptOverride != null
                ? initialPromptOverride
                : (trigger.autoStart() ? trigger.prompt() : null);
        run.initialPrompt = run.pendingPrompt;
        recordTriggerInput(run, flow, trigger);
        runs.put(runId, run);
        evictOldestCompleted();
        trackForPersistence(run);
        runStore.persist(run);

        // A turn-based backend sits idle until given a first instruction; only the cloud backend
        // launches a hosted session up front. Asked of the backend rather than compared against a
        // hardcoded id, so a third one does not have to be added to this condition to work at all.
        ExecutionBackend chosen = backends.byId(backend).orElse(null);
        if (chosen != null && chosen.isTurnBased()) {
            // Harmless on a backend that has no notion of a CLI session; the claude one needs it.
            run.localSessionId = UUID.randomUUID().toString();
            run.status = "IDLE";
            String where = chosen.startupDescription();
            // Named on every run, because it decides what the agent may do to this machine without
            // asking, and it is otherwise invisible until something has already happened.
            if (!run.permissionMode.isBlank()) {
                where += " · permissions: " + run.permissionMode;
            }
            if (run.pendingPrompt != null) {
                run.emit(RunEvent.of("system", where + "; auto-starting with the Input prompt."));
                String prompt = run.pendingPrompt;
                run.pendingPrompt = null;
                submitOrFail(run, () -> runLocalTurn(run, prompt));
            } else {
                run.emit(RunEvent.of("system", where + " ("
                        + (compiled.subAgents().size() + 1) + " agents). Send a command to start."));
            }
        } else {
            run.emit(RunEvent.of("system", "Launching flow '" + flow.name() + "' in the cloud ("
                    + (compiled.subAgents().size() + 1) + " agents)…"));
            submitOrFail(run, () -> execute(run, compiled));
        }
        return run.toSummary();
    }

    /** Submits work to the run-worker pool; if the queue is full, fails the run instead of blocking. */
    private void submitOrFail(AgentRun run, Runnable task) {
        try {
            exec.submit(task);
        } catch (RejectedExecutionException e) {
            fail(run, "Too many runs in progress right now. Please try again shortly.");
        }
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
                .filter(r -> isTerminal(r.status))
                .sorted(Comparator.comparingLong(r -> r.createdAt))
                .limit(overflow)
                .map(r -> r.id)
                .forEach(runs::remove);
    }

    private static boolean isTerminal(String status) {
        return "TERMINATED".equals(status) || "ERROR".equals(status);
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
                cloudEvents.handle(run, ev);
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

    /** One turn on whichever turn-based backend this run uses. */
    private void runLocalTurn(AgentRun run, String prompt) {
        try {
            // Dispatched through the registry rather than an if-chain, so adding a backend — or
            // moving one behind a network call — does not mean editing this method.
            backends.byId(run.backend)
                    .orElseThrow(() -> new IllegalStateException(
                            "No execution backend '" + run.backend + "' is registered."))
                    .runTurn(run, run.compiled, prompt);
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

        // Asked of the backend rather than compared against a hardcoded id. Every turn-based
        // backend is driven the same way; the previous `"local".equals(...)` silently sent a
        // self-hosted run down the cloud path, where it waited for a session id that a backend
        // with no hosted session never produces — reported as "Run is not ready yet" on a run that
        // was working perfectly.
        boolean turnBased = backends.byId(run.backend)
                .map(ExecutionBackend::isTurnBased)
                .orElse(false);
        if (turnBased) {
            if (run.compiled == null) {
                throw new IllegalStateException("This execution has not finished starting up yet.");
            }
            try {
                exec.submit(() -> runLocalTurn(run, text));
            } catch (RejectedExecutionException e) {
                throw new IllegalStateException("Too many runs in progress right now. Please try again shortly.");
            }
            return;
        }

        if (run.sessionId == null) {
            throw new IllegalStateException("This cloud session has not started yet — give it a moment.");
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

    /**
     * A human approves the plan: the run resumes in the same Claude session, now permitted to act.
     *
     * <p>Resuming rather than restarting is the point — the agent already worked out what to do
     * and said so; making it start over would both waste the tokens and risk it proposing
     * something other than what was approved.
     */
    public void approve(String runId) {
        AgentRun run = require(runId);
        if (!"AWAITING_APPROVAL".equals(run.status)) {
            throw new IllegalStateException("This execution is not waiting for approval.");
        }
        run.approved = true;
        run.emit(RunEvent.of("system", "Approved — carrying out the plan."));
        runStore.persist(run);
        exec.submit(() -> runLocalTurn(run,
                "Approved. Carry out the plan you proposed, exactly as described."));
    }

    /** A human declines: the run ends here, having changed nothing. */
    public void reject(String runId) {
        AgentRun run = require(runId);
        if (!"AWAITING_APPROVAL".equals(run.status)) {
            throw new IllegalStateException("This execution is not waiting for approval.");
        }
        run.status = "TERMINATED";
        run.emit(RunEvent.of("system", "Rejected — the plan was not carried out."));
        run.emit(RunEvent.of("status", "terminated"));
        runStore.persist(run);
    }

    public void stop(String runId) {
        AgentRun run = require(runId);

        // Each backend knows how to stop itself: the CLI kills a child process, a self-hosted model
        // has none and relies on its loop seeing TERMINATED between turns. Only the cloud path,
        // which owns a stream rather than a backend bean, falls through below.
        var backend = backends.byId(run.backend).filter(ExecutionBackend::isTurnBased);
        if (backend.isPresent()) {
            backend.get().stop(run);
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
                run.pricing = pricing;
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
        return runs.values().stream().anyMatch(r -> flowId.equals(r.flowId) && !isTerminal(r.status));
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
