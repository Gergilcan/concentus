package com.concentus.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsStreamSessionEvents;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsUserMessageEventParams;
import com.anthropic.models.beta.sessions.events.EventSendParams;
import com.concentus.config.AgentSpec;
import com.concentus.model.FlowNode;
import com.concentus.model.NodeExec;
import com.concentus.model.FlowGraph;
import com.concentus.model.RunEvent;
import com.concentus.model.RunSummary;
import com.concentus.model.TriggerSpec;
import com.concentus.store.RunStore;
import com.concentus.support.AnthropicClientProvider;
import com.concentus.support.Ids;
import com.concentus.support.Variables;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
    /** Read-only here: runs stamp the flow's version number at launch, they never write history. */
    private final com.concentus.store.FlowVersionStore flowVersions;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final NotificationService notifier;
    /** Starts the flows a finished run hands off to, and enforces the loop guards. */
    private final SubflowService subflows;
    private final RemoteApprovalService remoteApprovals;
    private final com.concentus.store.VariableStore variableStore;
    private final ExecutorService exec;
    private final int maxRetainedRuns;
    private final PricingTable pricing;
    private final double inputUsdPerMTok;
    private final double outputUsdPerMTok;
    private final ConcurrentHashMap<String, AgentRun> runs = new ConcurrentHashMap<>();

    public RunService(AnthropicClientProvider clientProvider, FlowCompiler compiler,
                      ManagedFlowLauncher launcher, ExecutionBackends backends, PricingTable pricing,
                      CloudStreamEventHandler cloudEvents,
                      RunStore runStore, com.concentus.store.FlowVersionStore flowVersions,
                      com.fasterxml.jackson.databind.ObjectMapper mapper,
                      NotificationService notifier, RemoteApprovalService remoteApprovals,
                      SubflowService subflows,
                      com.concentus.store.VariableStore variableStore,
                      com.concentus.config.Settings settings) {
        this.clientProvider = clientProvider;
        this.compiler = compiler;
        this.launcher = launcher;
        this.backends = backends;
        this.pricing = pricing;
        this.cloudEvents = cloudEvents;
        this.runStore = runStore;
        this.flowVersions = flowVersions;
        this.mapper = mapper;
        this.notifier = notifier;
        this.subflows = subflows;
        this.remoteApprovals = remoteApprovals;
        this.variableStore = variableStore;
        // Read here rather than injected as placeholders, so what somebody set under Settings is
        // what this pool is sized with. Still read once — a thread pool cannot be resized under a
        // run in flight — which is why the settings screen says these wait for a restart.
        int maxConcurrent = settings.number("runs.max-concurrent", 8);
        int queueCapacity = settings.number("runs.queue-capacity", 64);
        this.maxRetainedRuns = settings.number("runs.max-retained", 200);
        this.inputUsdPerMTok = settings.decimal("pricing.input-usd-per-mtok", 3.0);
        this.outputUsdPerMTok = settings.decimal("pricing.output-usd-per-mtok", 15.0);
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
     * Refuses a run whose flow has spent its monthly ceiling — but only where a run is actually
     * billed.
     *
     * <p>Checked at start, not mid-run: a run in flight finishes, because cutting an agent off
     * mid-task leaves half-done work that costs more to untangle than the tokens saved. The month
     * is the calendar month in the machine's own timezone, which is the month the invoice thinks
     * in. An ad-hoc run of an unsaved canvas has no flow id and no history to sum, so no ceiling.
     *
     * <p>A ceiling exists to stop a flow running up an API bill nobody is watching. On the
     * {@code claude} CLI there is no per-token bill at all: the work comes out of a subscription
     * somebody already paid for, flat. Same for a model on your own hardware. Refusing those runs
     * did not save anything — it stopped work over a number that describes what the same tokens
     * WOULD have cost on the API, which is a useful figure to read and a nonsensical one to be
     * blocked by. The estimate is still recorded either way; it just no longer decides.
     *
     * <p>Checked after the backend is chosen, which means after compiling. That reversed an
     * earlier decision to refuse before compiling so a broken graph would still report its budget:
     * being right about whether money is involved is worth more than which of two actionable
     * messages arrives first, and a compile error names the box to fix.
     */
    private void enforceBudget(FlowGraph flow, String backend) {
        if (flow.id() == null || flow.budgetUsd() == null || flow.budgetUsd() <= 0) return;
        // An unregistered backend is the hosted API path, which bills. Unknown means billed on
        // purpose: forgetting to declare a new backend must not quietly disable everyone's ceiling.
        if (!backends.byId(backend).map(ExecutionBackend::billsPerToken).orElse(true)) return;
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
     * Where this flow will run.
     *
     * <p>The model decides, and only then does the credential. Asked in this order because a flow
     * naming a self-hosted model must not be sent to Claude — and must not be refused for lacking
     * a Claude credential it was never going to use. A backend only claims a model it can actually
     * serve right now, so an unclaimed model falls through to the Claude paths exactly as before.
     */
    private String chooseBackend(CompiledFlow compiled) {
        String coordinatorModel = compiled.coordinator().model == null
                ? null : compiled.coordinator().model.id;
        var claimed = backends.forModel(coordinatorModel).filter(ExecutionBackend::isTurnBased);
        if (claimed.isPresent()) return claimed.get().id();

        // Which Claude credential is available decides the rest: the local CLI on a subscription,
        // or the hosted Managed Agents API on a key.
        String backend = clientProvider.backend();
        if ("none".equals(backend)) {
            throw new IllegalStateException(unroutableMessage(coordinatorModel));
        }
        return backend;
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

    /** A value as one log-line token: whitespace collapsed, cut at 60 — a signal, not a dump. */
    private static String oneLine(String value) {
        String flat = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return flat.length() > 60 ? flat.substring(0, 57) + "…" : flat;
    }

    /**
     * Says in the run itself which values it executed with, and which names nothing defined.
     *
     * <p>Recorded on the run because the flow's settings only ever say what the NEXT run would
     * use — a run that behaved oddly is read months later, when the values have moved on.
     */
    private static void reportVariables(AgentRun run, Map<String, String> values,
                                        Set<String> unresolved) {
        if (!values.isEmpty()) {
            run.emit(RunEvent.of("system", "Variables: " + values.entrySet().stream()
                    .map(e -> e.getKey() + "=" + oneLine(e.getValue()))
                    .collect(Collectors.joining(" · "))));
        }
        if (!unresolved.isEmpty()) {
            run.emit(RunEvent.of("system", "Variables without a value (left as written): "
                    + unresolved.stream().map(n -> "{{" + n + "}}")
                            .collect(Collectors.joining(", "))));
        }
    }

    /**
     * Starts a run. When {@code initialPromptOverride} is non-null it becomes the first turn
     * (used by webhook triggers to inject the event payload); otherwise the Input node's own
     * prompt is used for prompt/cron modes.
     */
    /**
     * A run started by another flow.
     *
     * <p>Same launch as any other, with two differences that both exist to keep the loop guard
     * honest: the run carries the chain of flows above it, and its trigger says so, which is what
     * lets the executions list show a child as work rather than as something that started itself.
     * Everything else — budget, permission mode, shadow — is the child flow's own, deliberately:
     * a ceiling a parent could spend through would not be a ceiling.
     *
     * @param chain the flows already running above this one, oldest first
     */
    public RunSummary startSubflow(FlowGraph flow, String prompt, List<String> chain) {
        RunSummary summary = start(flow, prompt);
        AgentRun run = runs.get(summary.id());
        if (run != null) {
            run.flowChain = chain == null ? List.of() : List.copyOf(chain);
            run.trigger = "subflow";
            run.emit(RunEvent.of("system", "Started by another flow. Chain: "
                    + String.join(" → ", run.flowChain) + "."));
            runStore.persist(run);
            return run.toSummary();
        }
        return summary;
    }

    public RunSummary start(FlowGraph flow, String initialPromptOverride) {
        // Variables resolve at start time, not at save time: the flow's prompts keep their
        // {{NAME}} placeholders on disk, and each run stamps in whatever the organization and the
        // flow say TODAY — which is also what makes the values editable without touching prompts.
        Map<String, String> variableValues = variableStore.merged(flow.variables());
        Set<String> unresolvedVariables = new LinkedHashSet<>();
        // Compile synchronously so validation errors surface to the caller immediately.
        CompiledFlow compiled = compiler.compile(flow, variableValues, unresolvedVariables);
        TriggerSpec trigger = TriggerSpec.from(flow);
        String backend = chooseBackend(compiled);
        enforceBudget(flow, backend);

        String runId = Ids.generate("run_", 12);
        AgentRun run = new AgentRun(runId, flow.id(), flow.name(), flow.modeOrDefault());
        run.backend = backend;
        run.compiled = compiled;
        run.flowJson = toJson(flow);
        // Stamped once, at launch, next to the snapshot it names — reading it later would report
        // whatever version the flow has been edited to since, which is the opposite of what an
        // execution's version badge is for.
        run.flowVersion = flowVersions.currentVersion(flow.id());
        run.notifyWebhook = flow.notifyWebhook();
        run.approvalSlackCredentialId = flow.approvalSlackCredentialId();
        run.approvalSlackChannel = flow.approvalSlackChannel();
        run.approvalTeamsWebhook = flow.approvalTeamsWebhook();
        run.pricing = pricing;
        run.inputUsdPerMTok = inputUsdPerMTok;
        run.outputUsdPerMTok = outputUsdPerMTok;
        run.trigger = trigger.mode() == null ? "manual" : trigger.mode().toLowerCase();
        // Who pressed Run, when a person did. A cron thread and a webhook have no security
        // context, so this stays null there and the trigger already says what they were.
        run.startedBy = signedInEmail();
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
        run.pendingPrompt = Variables.substitute(
                initialPromptOverride != null
                        ? initialPromptOverride
                        : (trigger.autoStart() ? trigger.prompt() : null),
                variableValues, unresolvedVariables);
        run.initialPrompt = run.pendingPrompt;
        recordTriggerInput(run, flow, trigger);
        reportVariables(run, variableValues, unresolvedVariables);
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

    /**
     * The signed-in person behind this request, or null.
     *
     * <p>Read from the security context rather than injected, so nothing about running a flow
     * depends on authentication being switched on: with accounts off there is no context, no name,
     * and the run is credited to nobody — which is the truth on a single-user desktop install.
     */
    private static String signedInEmail() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        return auth.getPrincipal() instanceof com.concentus.auth.ConcentusUserDetails user
                ? user.email() : null;
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
                // A golden run is a reference, not history — evicting it would quietly disable
                // "compare against golden" on precisely the flows that run most often.
                .filter(r -> !r.golden)
                .sorted(Comparator.comparingLong(r -> r.createdAt))
                .limit(overflow)
                .map(r -> r.id)
                .forEach(runs::remove);
    }

    private static boolean isTerminal(String status) {
        // COMPLETED is terminal for retention: a delivered result can be evicted. Its session
        // is still continuable while it lives — terminal here means "safe to forget", not
        // "cannot be spoken to".
        return "TERMINATED".equals(status) || "ERROR".equals(status) || "COMPLETED".equals(status);
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
            // Same contract as runLocalTurn's finally: a run that ended failed notifies,
            // whoever set the status. The cloud handler marks ERROR from session events —
            // without this, only exception-shaped cloud failures ever reached the webhook.
            if ("ERROR".equals(run.status)) {
                notifier.runFailed(run);
            }
        }
    }

    /** One turn on whichever turn-based backend this run uses. */
    private void runLocalTurn(AgentRun run, String prompt) {
        // A new turn means whatever was asked before has been answered. Cleared here rather than
        // when the answer arrives, so the next question — a different question — gets posted.
        run.answerRemoteNotified = false;
        try {
            // Dispatched through the registry rather than an if-chain, so adding a backend — or
            // moving one behind a network call — does not mean editing this method.
            backends.byId(run.backend)
                    .orElseThrow(() -> new IllegalStateException(
                            "No execution backend '" + run.backend + "' is registered."))
                    .runTurn(run, run.compiled, prompt);
        } finally {
            // A finished turn lands in one of two resting states, not a generic IDLE: the final
            // answer either ASKS the user something — the run now waits for a reply — or it
            // delivered a result and is done. IDLE stays what it always was: a run waiting for
            // its FIRST instruction. Heuristic on purpose: the stream carries no structured
            // "question" signal, so the final answer's closing line decides.
            if ("IDLE".equals(run.status)) {
                run.status = endsWithQuestion(run.finalOutput()) ? "AWAITING_ANSWER" : "COMPLETED";
                run.emit(RunEvent.of("status", run.status.toLowerCase()));
            }
            runStore.persist(run);
            if ("ERROR".equals(run.status)) {
                notifier.runFailed(run);
            }
            // The graph, not just the compiled specs: the condition and for-each nodes on the way
            // to a hand-off are drawn, not compiled, and this is where they are read.
            subflows.handOffAfter(run, flowOf(run).orElse(null));
            // The turn ended with the run stopped, waiting for a human. Once per run: a second
            // command sent while waiting would end the same way, and a second Slack message for
            // the same question reads as two questions.
            if ("AWAITING_APPROVAL".equals(run.status) && !run.approvalRemoteNotified) {
                run.approvalRemoteNotified = true;
                remoteApprovals.runAwaitingApproval(run,
                        () -> approve(run.id), () -> reject(run.id));
            }
            // The turn ended with the agent ASKING something. The same channels carry it, and a
            // threaded reply comes back through sendCommand — the same door the app's own command
            // box uses, so a remote answer is indistinguishable from one typed here.
            if ("AWAITING_ANSWER".equals(run.status) && !run.answerRemoteNotified) {
                run.answerRemoteNotified = true;
                remoteApprovals.runAwaitingAnswer(run, reply -> sendCommand(run.id, reply));
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

    private NodeExec coordExec(AgentRun run) {
        if (run.compiled == null) return null;
        var c = run.compiled.coordinator();
        return run.nodeExec(c.nodeId, "agent", c.name);
    }

    /**
     * Marks or unmarks a run as its flow's golden reference. One per flow: marking a run clears
     * the previous holder, so "the golden run" is always a single answer.
     */
    public RunSummary setGolden(String runId, boolean golden) {
        AgentRun run = require(runId);
        if (golden && (run.flowId == null || run.flowId.isBlank())) {
            throw new IllegalStateException(
                    "Only runs of a saved flow can be a golden reference — save the flow first.");
        }
        if (golden) {
            runs.values().stream()
                    .filter(r -> r.golden && run.flowId.equals(r.flowId) && !r.id.equals(runId))
                    .forEach(r -> {
                        r.golden = false;
                        runStore.persist(r);
                    });
        }
        run.golden = golden;
        runStore.persist(run);
        return run.toSummary();
    }

    /**
     * Re-runs the golden reference's first input against the flow as it stands NOW.
     *
     * <p>The deliberate opposite of {@link #retry}: retry replays against the run's stored flow
     * snapshot (same flow, same input — reproduce what happened), while this replays against the
     * current flow (same input, edited flow — show what the edit changes). The caller passes the
     * current flow in, because this service deliberately does not read the flow store.
     */
    public RunSummary startGoldenCheck(FlowGraph currentFlow, String goldenRunId) {
        AgentRun golden = require(goldenRunId);
        if (!golden.golden) {
            throw new IllegalStateException("This execution is not the golden reference.");
        }
        if (golden.initialPrompt == null || golden.initialPrompt.isBlank()) {
            throw new IllegalStateException("The golden run recorded no initial input to replay.");
        }
        RunSummary started = start(currentFlow, golden.initialPrompt);
        AgentRun run = runs.get(started.id());
        if (run != null) {
            // Labelled for what it is, in every list and in the history: a comparison run, not
            // the trigger the flow normally fires on. Shadow stays visible — a flow whose trigger
            // is shadowed plans without acting, and its golden check inherits that, so the
            // comparison reads plan-vs-run unless the shadow is lifted first.
            run.trigger = run.shadow ? "golden (shadow)" : "golden";
            runStore.persist(run);
            return run.toSummary();
        }
        return started;
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
        RunSummary started = start(flow, old.initialPrompt);
        AgentRun run = runs.get(started.id());
        if (run == null) return started;
        // A retry replays the ORIGINAL revision, so it carries the original's version number.
        // start() stamped the flow's current version, which for an edited flow would label this
        // run with a revision it never executed.
        run.flowVersion = old.flowVersion;
        runStore.persist(run);
        return run.toSummary();
    }

    /**
     * Runs one block of a finished execution again, on its own, with an input you can edit first.
     *
     * <p>The input defaults to the one the block actually received — that is the whole point.
     * Tuning a sub-agent's prompt used to mean re-running everything before it to reproduce the
     * conditions it failed under; the recorded input reproduces them exactly, for the price of one
     * block. Editing it is how the same machinery answers "what would it have done if I had asked
     * differently".
     *
     * @param downstream keep the agents this one delegates to, which is what continuing a failed
     *                   run from the point it broke means
     */
    public RunSummary rerunBlock(String runId, String nodeId, String inputOverride, boolean downstream) {
        return rerunBlock(runId, nodeId, inputOverride, downstream, null);
    }

    /**
     * As above, with the block run on a different model — same input, same instructions, one
     * thing changed, which is the only shape in which "would the cheaper model do" has an answer.
     */
    public RunSummary rerunBlock(String runId, String nodeId, String inputOverride,
                                 boolean downstream, String model) {
        AgentRun old = require(runId);
        FlowGraph flow = flowOf(old).orElseThrow(() ->
                new IllegalStateException("This execution has no stored flow to run a block from."));

        String input = inputOverride;
        if (input == null || input.isBlank()) {
            NodeExec recorded = old.nodeExecOrNull(nodeId);
            input = recorded == null ? null : recorded.input;
        }
        if (input == null || input.isBlank()) {
            // Refused rather than started empty: a block with no instruction does not fail, it
            // improvises, and an improvised run looks like a real result until someone reads it.
            throw new IllegalStateException("This block recorded no input to run again. Type the "
                    + "input to run it with.");
        }

        FlowGraph slice = BlockSlice.of(flow, nodeId, downstream, model);
        RunSummary started = start(slice, input);
        AgentRun run = runs.get(started.id());
        if (run == null) return started;
        // Named for what it is wherever runs are listed, and pointed back at the execution it was
        // cut from — a block re-run that looked like an ordinary manual run would quietly pollute
        // a flow's history and its success rate with fragments of other runs.
        run.trigger = downstream ? "continued" : "block re-run";
        run.parentRunId = old.id;
        run.flowVersion = old.flowVersion;
        run.emit(RunEvent.of("system", (downstream ? "Continuing from" : "Running") + " block '"
                + BlockSlice.labelOf(flow, nodeId) + "' of execution " + old.id
                + (inputOverride == null || inputOverride.isBlank()
                        ? " with the input it received."
                        : " with an edited input.")
                + (model == null || model.isBlank() ? "" : " On " + model.trim() + ".")));
        runStore.persist(run);
        return run.toSummary();
    }

    /** Sends an explicit instruction to a running session. */
    public void sendCommand(String runId, String text) {
        AgentRun run = require(runId);
        if (run.handOffsFired) {
            // This flow already handed its answer to the next one. Another message would produce a
            // second answer that nobody passes on, and the hand-off cannot fire again — so the run
            // would look like it did the work while quietly doing half the job. A finished chain
            // is finished: run the flow again if there is more to say.
            throw new IllegalStateException("This execution handed its result to another flow and "
                    + "is closed. Run the flow again to continue.");
        }
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
        // Whoever decided — app button or Slack reaction — the remote message shows the outcome.
        remoteApprovals.settled(run.id, "approved");
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
        remoteApprovals.settled(run.id, "rejected");
    }

    public void stop(String runId) {
        AgentRun run = require(runId);

        // Each backend knows how to stop itself: the CLI kills a child process, a self-hosted model
        // has none and relies on its loop seeing TERMINATED between turns. Only the cloud path,
        // which owns a stream rather than a backend bean, closes its own stream here.
        var backend = backends.byId(run.backend).filter(ExecutionBackend::isTurnBased);
        if (backend.isPresent()) {
            backend.get().stop(run);
        } else {
            run.status = "TERMINATED";
            AutoCloseable s = run.stream;
            if (s != null) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }
        run.emit(RunEvent.of("status", "terminated"));
        runStore.persist(run);
        // A stopped run is no longer asking anyone anything.
        remoteApprovals.settled(runId, "closed");
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
                run.startedBy = row.startedBy();
                run.sessionId = row.sessionId();
                run.localSessionId = row.localSessionId();
                run.localStarted = row.localStarted();
                run.error = row.error();
                run.totalInputTokens = row.totalInputTokens();
                run.totalOutputTokens = row.totalOutputTokens();
                run.flowJson = row.flowJson();
                run.flowVersion = row.flowVersion();
                run.initialPrompt = row.initialPrompt();
                run.notifyWebhook = row.notifyWebhook();
                run.golden = row.golden();
                run.pricing = pricing;
                run.inputUsdPerMTok = inputUsdPerMTok;
                run.outputUsdPerMTok = outputUsdPerMTok;
                run.restoreEvents(row.events());
                run.restoreNodeExecs(row.nodeExecs());
                if (row.flowJson() != null) {
                    // With variables substituted, same as when the run first compiled: a continued
                    // turn built from this spec must not send {{NAME}} placeholders the original
                    // run had already resolved.
                    FlowGraph snapshot = mapper.readValue(row.flowJson(), FlowGraph.class);
                    run.compiled = compiler.compile(snapshot,
                            variableStore.merged(snapshot.variables()), null);
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

    /**
     * True if a run for this flow is actually IN FLIGHT (used to avoid overlapping cron fires).
     *
     * <p>In flight means working or waiting on a human decision — not IDLE. An idle run is a
     * finished turn kept around so its session can be continued; counting it as active made
     * every cron flow fire exactly once and then starve, because local runs never terminate
     * on their own and yesterday's idle run blocked every later tick.
     */
    public boolean hasActiveRun(String flowId) {
        if (flowId == null) return false;
        return runs.values().stream().anyMatch(r -> flowId.equals(r.flowId) && isInFlight(r.status));
    }

    private static boolean isInFlight(String status) {
        // Waiting on a human — approval or an answer — is still in flight: firing a second run
        // would stack a duplicate behind the question being asked.
        return "STARTING".equals(status) || "RUNNING".equals(status)
                || "AWAITING_APPROVAL".equals(status) || "AWAITING_ANSWER".equals(status);
    }

    /** The stream has no structured "question" signal; the final answer's last line decides. */
    private static boolean endsWithQuestion(String text) {
        if (text == null || text.isBlank()) return false;
        String[] lines = text.strip().split("\\R");
        String last = lines[lines.length - 1].strip();
        return last.endsWith("?") || last.contains("¿");
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
