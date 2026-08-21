package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.model.NodeExec;
import com.concentus.model.RunEvent;
import com.concentus.model.RunSummary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** In-memory state for one launched flow: status, session ids, buffered output, live listeners. */
public class AgentRun {

    private static final int MAX_BUFFER = 4000;

    public final String id;
    public final String flowId;
    public final String flowName;
    public final String mode;
    /** Settable so restored runs keep their original ordering timestamp. */
    public volatile long createdAt = System.currentTimeMillis();
    /** FlowGraph snapshot (JSON) used to recompile and continue this run after a restart. */
    public volatile String flowJson;
    /**
     * The flow's version number at launch — the human-readable anchor between an execution and the
     * Versions list. 0 when the flow has no history (unsaved, or history unavailable). What the
     * run actually executed is {@link #flowJson}, always: this number names that revision, it does
     * not define it.
     */
    public volatile int flowVersion;

    // STARTING | RUNNING | IDLE (waiting for its first instruction) | AWAITING_APPROVAL |
    // AWAITING_ANSWER (the final answer asked the user something) | COMPLETED | ERROR | TERMINATED
    public volatile String status = "STARTING";
    public volatile String sessionId;
    public volatile List<String> agentIds = List.of();
    public volatile String error;

    /** "cloud" (Managed Agents / API key) or "local" (claude CLI / subscription). */
    public volatile String backend = "cloud";

    /** How this execution was triggered: "manual" | "prompt" | "cron" | "webhook". */
    public volatile String trigger = "manual";
    /**
     * The person who started this run, by email; null when nothing did.
     *
     * <p>Null is the honest answer for a schedule, a webhook delivery or a flow started by another
     * flow: those already say what they were in {@link #trigger}, and inventing a name for them
     * would be worse than the gap. Flow edits have been credited to an author since versions
     * gained one; this is the other half of the same question.
     */
    public volatile String startedBy;
    /**
     * The flows that led here, oldest first, when this run was started by another flow.
     *
     * <p>Carried on the run rather than looked up, because the loop it prevents is not visible
     * anywhere else: two flows pointing at each other each look like an ordinary graph, and only
     * the chain shows that the second one is already running above the first.
     */
    public volatile java.util.List<String> flowChain = java.util.List.of();
    /** The run that started this one, when a flow ran another. Null for everything else. */
    public volatile String parentRunId;
    /**
     * Whether this run has already fired its hand-offs.
     *
     * <p>A finished run returns to RUNNING when somebody sends it another message, and reaches
     * COMPLETED again at the end of that turn. Without this, the second turn would start the same
     * chained flow a second time: the mail sent twice, the invoice raised twice.
     */
    public volatile boolean handOffsFired;
    /** Initial input to fire automatically once the run is ready (null = wait for the user). */
    public volatile String pendingPrompt;
    /** The first input this run was given — replayed when the execution is retried. */
    public volatile String initialPrompt;
    /** Flow's failure-notification URL, copied at start so it survives flow edits. */
    public volatile String notifyWebhook;
    /** USD per million tokens, used for the cost estimate shown in the UI. */
    public volatile double inputUsdPerMTok;
    public volatile double outputUsdPerMTok;
    /** Per-model rates; set at launch so a run prices each block by the model it actually used. */
    public volatile PricingTable pricing;

    /**
     * Repositories cloned into this run.s working directory, with the environment variable each
     * one.s credential helper reads. Per run rather than per flow: two runs of the same
     * mail-triggered flow can overlap, and one working tree would have them writing over each
     * other.s branch mid-edit.
     */
    public volatile java.util.List<com.concentus.git.GitWorkspace.Checkout> checkouts = java.util.List.of();

    /** Open event stream (cloud), stored so {@code stop()} can break the loop. */
    public volatile AutoCloseable stream;


    // --- local (claude CLI) run state ---
    public volatile CompiledFlow compiled;
    public volatile String localSessionId;
    public volatile boolean localStarted = false;
    public volatile Process localProcess;

    // --- fanout (independent worker processes) run state ---
    /**
     * Live worker processes by agent node id, so Stop can kill every one of them — a fan-out that
     * only killed the coordinator would leave N orphaned {@code claude} processes still working.
     */
    public final Map<String, Process> workerProcesses = new ConcurrentHashMap<>();
    /**
     * Worker node ids whose workspace (CLAUDE.md, RAG injection) is already prepared. Guarded per
     * run because RAG injection appends to the spec's system prompt — running it again on the next
     * turn would stack a second copy of the same rows onto the same prompt.
     */
    public final java.util.Set<String> workersPrepared = ConcurrentHashMap.newKeySet();
    /**
     * Each worker's facade profile, frozen when its workspace is prepared. Frozen like
     * {@link #permissionMode}: editing a profile mid-run must not widen what an already-running
     * worker may do — the next run picks the edit up.
     */
    public final Map<String, com.concentus.model.FacadeProfile> workerFacadeProfiles =
            new ConcurrentHashMap<>();
    /**
     * Bearer per worker for its facade endpoint, keyed by agent node id. Per worker rather than
     * the run's {@link #toolToken}: a worker holding the run-wide token could call the
     * coordinator's tools endpoint and reach APIs its own facade never granted.
     */
    public final Map<String, String> workerToolTokens = new ConcurrentHashMap<>();
    /**
     * The plan the coordinator submitted this turn via {@code plan_submit}, already validated
     * and with profile names resolved. Null until it arrives; cleared before each planning turn
     * so a stale plan from the previous turn can never run twice.
     */
    public volatile com.concentus.model.WorkPlan submittedPlan;
    /**
     * Specs of plan-born workers, keyed by their synthetic node id ({@code worker:<itemId>}).
     * The facade endpoint resolves workers here when they are not canvas nodes; in-memory only,
     * like the rest of the fan-out state — a restart ends the turn either way.
     */
    public final Map<String, com.concentus.config.AgentSpec> syntheticWorkers =
            new ConcurrentHashMap<>();
    /**
     * Bearer for the verifier's verdict endpoint. Its own token rather than {@link #toolToken}:
     * the verifier holding the planner's token could resubmit the plan, and the planner holding
     * this one could pre-approve its own workers' outputs.
     */
    public volatile String verdictToken;
    /**
     * The verdict the verifier submitted this turn via {@code verdict_submit}, already validated
     * against {@link #verdictExpected}. Null until it arrives; cleared before each verification
     * so a stale verdict can never pass judgment on outputs it did not see.
     */
    public volatile com.concentus.model.WorkVerdict submittedVerdict;
    /**
     * Node ids of the worker outputs currently awaiting judgment — the exact set a submitted
     * verdict must cover, no more and no less.
     */
    public final java.util.Set<String> verdictExpected = ConcurrentHashMap.newKeySet();

    // --- self-hosted model run state ---
    /**
     * MCP sessions, kept for the life of the run.
     *
     * <p>The handshake is per session, so reconnecting each turn would pay it repeatedly and churn
     * sessions on the server. Keyed by server name, which is unique within a flow.
     */
    public final Map<String, com.concentus.llm.McpClient> mcpClients = new ConcurrentHashMap<>();

    /**
     * What the workers of this run have told each other.
     *
     * <p>Independent workers each get their own process and their own context window, which is
     * what makes them independent and also what makes five of them research the same thing five
     * times. Nothing reached from one worker to another: the coordinator's plan went out, the
     * reports came back, and in between they were blind to each other.
     *
     * <p>Append-only, and shared. A worker that learns something the others would waste time
     * re-learning says so, and the others can read it. It costs one tool call and saves whatever
     * the duplicated work would have cost — which in a fan-out is the largest avoidable expense
     * there is.
     *
     * <p>On the run rather than in the database, because it is scratch: it belongs to this
     * attempt, not to the flow, and a note that outlived its run would be read next time as a
     * fact about a different question. Each note is also emitted as an event, so the run report
     * keeps a record without a table that has to be cleaned up.
     */
    public final java.util.List<SharedNote> sharedNotes =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /**
     * One thing a worker told the others.
     *
     * @param author the worker's name, because "somebody found this" is not usable by a reader
     *               deciding whether to trust it
     */
    public record SharedNote(String author, String text, long at) {
    }

    /** Notes kept. Past this a worker is narrating rather than sharing, and the rest go unread. */
    public static final int MAX_SHARED_NOTES = 60;
    /** Characters per note. A finding that needs more than this is a report, not a note. */
    public static final int MAX_SHARED_NOTE_CHARS = 600;

    /**
     * Records a note, or says why it was not.
     *
     * @return null when it was recorded, or the reason to hand back to the worker
     */
    public String shareNote(String author, String text) {
        String note = text == null ? "" : text.strip();
        if (note.isEmpty()) return "A note needs something in it.";
        if (note.length() > MAX_SHARED_NOTE_CHARS) {
            note = note.substring(0, MAX_SHARED_NOTE_CHARS) + "…";
        }
        synchronized (sharedNotes) {
            if (sharedNotes.size() >= MAX_SHARED_NOTES) {
                return "The shared notes for this run are full (" + MAX_SHARED_NOTES + "). Keep "
                        + "the rest for your final report.";
            }
            // A worker repeating itself adds nothing and costs every sibling a read.
            for (SharedNote existing : sharedNotes) {
                if (existing.text().equalsIgnoreCase(note)) {
                    return "That note is already there — no need to say it twice.";
                }
            }
            sharedNotes.add(new SharedNote(author, note, System.currentTimeMillis()));
        }
        return null;
    }

    /** Every note except the reader's own: a worker does not need to be told what it just said. */
    public java.util.List<SharedNote> notesFor(String author) {
        synchronized (sharedNotes) {
            return sharedNotes.stream().filter(n -> !n.author().equals(author)).toList();
        }
    }
    /**
     * Whether SQL/RAG context has been injected for this run.
     *
     * <p>Once per run, not once per turn: the injector runs the query and appends the rows to the
     * system prompt, so doing it again would re-read the database and stack a second copy of the
     * same rows onto the prompt.
     */
    public volatile boolean modelContextPrepared = false;

    /**
     * The permission mode this run was launched with, or blank for the deployment default.
     *
     * <p>Fixed at launch rather than read per turn: a flow edited mid-run must not quietly change
     * what an already-running agent is allowed to do.
     */
    public volatile String permissionMode = "";
    /**
     * Set once a human has approved the plan, in approval mode. Not persisted as a permission:
     * it is a fact about this run, and a restart that lost it would re-ask rather than proceed —
     * which is the safe direction for the one setting whose whole point is asking first.
     */
    public volatile boolean approved = false;
    /**
     * Bearer for this run's local MCP tool endpoint (API nodes). Minted when the workspace is
     * prepared; the CLI receives it inside its own mcp-config, so only the process this run
     * spawned can call this run's tools.
     */
    public volatile String toolToken;
    /** True when this run executed under a shadow trigger: it planned, it never acted. */
    public volatile boolean shadow;
    /**
     * True when this run is its flow's golden reference — the known-good execution that edits of
     * the flow are compared against. At most one per flow; marking another clears this one.
     */
    public volatile boolean golden;

    // --- remote approval (Slack / Teams), copied from the flow at start like notifyWebhook so a
    // flow edit mid-run cannot redirect an approval request already underway. Not persisted: a
    // restart loses the watch, and the run then waits in the app exactly as it did before this
    // feature existed.
    public volatile String approvalSlackCredentialId;
    public volatile String approvalSlackChannel;
    public volatile String approvalTeamsWebhook;
    /** Set when the remote channels were told about this run's approval wait — told once. */
    public volatile boolean approvalRemoteNotified;
    /**
     * Set when this run's current QUESTION was posted remotely. Reset at the start of every turn,
     * unlike the approval flag: a run can ask several questions in a row, and each new one is a
     * new thing to ask — while a second message about the SAME question reads as two questions.
     */
    public volatile boolean answerRemoteNotified;

    private final CopyOnWriteArrayList<RunEvent> buffer = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<RunEvent>> listeners = new CopyOnWriteArrayList<>();

    // --- per-node execution state (Input/Output tabs, step status, tokens) ---
    private final Map<String, NodeExec> nodeExecs = new LinkedHashMap<>();
    /** toolUseId of a Task call -> the sub-agent node it spawned (to attribute its output/tokens). */
    public final Map<String, String> taskToNode = new ConcurrentHashMap<>();
    /**
     * toolUseId -> the {@code subagent_type} the CLI reported, for Task calls that matched no
     * agent node (a built-in subagent, or a renamed one). Their output still belongs to a distinct
     * agent, so it is labelled with the name the CLI actually used rather than lumped under one
     * generic "sub-agent" bucket.
     */
    public final Map<String, String> taskToLabel = new ConcurrentHashMap<>();
    /**
     * Cloud analogue of {@link #taskToNode}: sessionThreadId -> the node whose agent owns that
     * thread. Managed Agents names the agent only on the thread-created event, so every later
     * event that carries a thread id is traced back to its node through this map.
     */
    public final Map<String, String> threadToNode = new ConcurrentHashMap<>();
    public volatile long totalInputTokens;
    public volatile long totalOutputTokens;
    /**
     * Cached-prompt tokens, tracked apart from {@link #totalInputTokens} because they are not
     * billed at the input rate: a cache read costs ~0.1x and a cache write ~1.25x. Resuming a
     * session re-sends the whole conversation each turn, so these dominate the raw token count —
     * folding them in at full price overstated cost by roughly an order of magnitude.
     */
    public volatile long cacheReadTokens;
    public volatile long cacheWriteTokens;

    /**
     * Adds one usage report to the run's totals.
     *
     * <p>Synchronized, and this is not defensive decoration: fan-out workers stream concurrently,
     * and {@code volatile total += n} is a read-modify-write that loses updates under contention.
     * CI caught it — two workers each reporting 10 input tokens totalled 10. The single-session
     * paths accrue from one thread and were never wrong, but they use this too so there is one
     * way to add usage rather than a safe one and a racy one.
     */
    public synchronized void accrueUsage(long input, long output, long cacheRead, long cacheWrite) {
        totalInputTokens += input;
        totalOutputTokens += output;
        cacheReadTokens += cacheRead;
        cacheWriteTokens += cacheWrite;
    }

    /** Get or create the execution record for a node. Returns null if nodeId is unknown. */
    public NodeExec nodeExec(String nodeId, String kind, String label) {
        if (nodeId == null || nodeId.isBlank()) return null;
        synchronized (nodeExecs) {
            return nodeExecs.computeIfAbsent(nodeId, k -> {
                NodeExec n = new NodeExec();
                n.nodeId = nodeId;
                n.kind = kind;
                n.label = label;
                // Resolved once here rather than at each of the dozen call sites, so a block is
                // always priced at its own model's rate.
                n.model = modelOf(nodeId);
                n.startedAt = System.currentTimeMillis();
                return n;
            });
        }
    }

    /**
     * The record for a node if this run has one, without creating it. {@link #nodeExec} creates on
     * demand, which is right while a run streams and wrong for a reader: asking a finished run what
     * a block received would otherwise mint an empty pending block and persist it.
     */
    public NodeExec nodeExecOrNull(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) return null;
        synchronized (nodeExecs) {
            return nodeExecs.get(nodeId);
        }
    }

    /** The model configured for an agent node, or null for non-agent nodes / unknown ids. */
    private String modelOf(String nodeId) {
        CompiledFlow flow = compiled;
        if (flow == null) return null;
        if (nodeId.equals(flow.coordinator().nodeId)) return modelId(flow.coordinator());
        for (AgentSpec s : flow.subAgents()) {
            if (nodeId.equals(s.nodeId)) return modelId(s);
        }
        return null;
    }

    private static String modelId(AgentSpec spec) {
        return spec.model == null ? null : spec.model.id;
    }

    public List<NodeExec> nodeExecList() {
        synchronized (nodeExecs) {
            return new ArrayList<>(nodeExecs.values());
        }
    }

    /** Repopulate buffer from persisted events (no listeners, no re-persist). */
    public void restoreEvents(List<RunEvent> events) {
        if (events == null) return;
        buffer.addAll(events);
        while (buffer.size() > MAX_BUFFER) buffer.remove(0);
    }

    /** Repopulate node execs from persisted state. */
    public void restoreNodeExecs(List<NodeExec> execs) {
        if (execs == null) return;
        synchronized (nodeExecs) {
            for (NodeExec n : execs) {
                if (n.nodeId != null) nodeExecs.put(n.nodeId, n);
            }
        }
    }

    public AgentRun(String id, String flowId, String flowName, String mode) {
        this.id = id;
        this.flowId = flowId;
        this.flowName = flowName;
        this.mode = mode;
    }

    public void emit(RunEvent e) {
        buffer.add(e);
        if (buffer.size() > MAX_BUFFER) {
            buffer.remove(0);
        }
        for (Consumer<RunEvent> l : listeners) {
            try {
                l.accept(e);
            } catch (Exception ignored) {
                // a dead listener must not break emission for the others
            }
        }
    }

    public void addListener(Consumer<RunEvent> l) {
        listeners.add(l);
    }

    public void removeListener(Consumer<RunEvent> l) {
        listeners.remove(l);
    }

    public List<RunEvent> bufferedEvents() {
        return List.copyOf(buffer);
    }

    public RunSummary toSummary() {
        return new RunSummary(id, flowId, flowName, mode, status, createdAt, sessionId, agentIds, error,
                trigger, totalInputTokens, totalOutputTokens, estimatedCostUsd(), golden, flowVersion,
                startedBy);
    }

    /**
     * What this run ultimately answered: the last agent message, which for a finished run is the
     * coordinator's closing report. Falls back to nothing rather than guessing — a run that never
     * produced an agent message has no final output, and showing a tool call or a system line as
     * "the result" would misrepresent what happened.
     */
    public String finalOutput() {
        List<RunEvent> events = bufferedEvents();
        for (int i = events.size() - 1; i >= 0; i--) {
            if ("agent_message".equals(events.get(i).type())) {
                return events.get(i).text();
            }
        }
        return null;
    }

    /**
     * The node list with each block's cost filled in. The one place per-node pricing happens:
     * it lived in RunController before, so any other consumer of node execs — persistence, a
     * websocket push, an export — silently reported $0 per node unless it remembered to price.
     */
    public List<NodeExec> pricedNodeExecList() {
        List<NodeExec> nodes = nodeExecList();
        for (NodeExec n : nodes) {
            if (pricing != null) {
                n.estimatedCostUsd = pricing.costUsd(
                        n.model, n.inputTokens, n.cacheReadTokens, n.cacheWriteTokens, n.outputTokens);
            }
            n.contextWindow = ContextWindows.windowFor(n.model);
        }
        return nodes;
    }

    /**
     * Sum of each block's cost, so the run and its blocks are priced the same way — per model,
     * with cached tokens weighted. Summing blocks rather than pricing the run's totals at one flat
     * rate is what makes a mixed-model flow (an Opus coordinator delegating to Sonnet sub-agents)
     * add up.
     */
    public double estimatedCostUsd() {
        PricingTable table = pricing;
        if (table == null) return 0d;
        double total = 0d;
        for (NodeExec n : nodeExecList()) {
            total += table.costUsd(n.model, n.inputTokens, n.cacheReadTokens, n.cacheWriteTokens,
                    n.outputTokens);
        }
        return PricingTable.round(total);
    }

    /**
     * This run's fan-out health, derived from the per-node records rather than accrued
     * separately — the node timings and statuses already are the truth, and a second set of
     * counters would only get the chance to disagree with them. Null when the run never fanned
     * out: a single-session flow has no graph to measure, and a strip of zeros would imply it
     * does.
     */
    public com.concentus.model.GraphMetrics graphMetrics() {
        CompiledFlow flow = compiled;
        List<NodeExec> nodes = nodeExecList();
        List<NodeExec> workers = nodes.stream().filter(n -> isWorker(n, flow)).toList();
        boolean fanout = flow != null && flow.fanout();
        if (workers.isEmpty() || (!fanout && workers.stream().noneMatch(n -> "worker".equals(n.kind)))) {
            return null;
        }

        long now = System.currentTimeMillis();
        int failed = 0, rejected = 0, verdicts = 0, retries = 0;
        long sum = 0, firstStart = Long.MAX_VALUE, lastEnd = 0;
        for (NodeExec n : workers) {
            if ("failed".equals(n.status)) failed++;
            if (n.verdict != null) verdicts++;
            if ("rejected".equals(n.verdict)) rejected++;
            if (n.startedAt > 0) {
                long end = n.endedAt > 0 ? n.endedAt : now;
                sum += end - n.startedAt;
                firstStart = Math.min(firstStart, n.startedAt);
                lastEnd = Math.max(lastEnd, end);
            }
        }
        // Retries across the whole graph, not just workers: a planner or merge that only passed
        // on its second launch is the same health signal.
        for (NodeExec n : nodes) retries += n.retries;
        long wall = firstStart == Long.MAX_VALUE ? 0 : lastEnd - firstStart;
        return new com.concentus.model.GraphMetrics(workers.size(), failed, rejected, retries,
                verdicts, wall, sum);
    }

    /** Whether this node record is a fan-out worker: plan-born, or a drawn sub-agent's. */
    private static boolean isWorker(NodeExec n, CompiledFlow flow) {
        if ("worker".equals(n.kind)) return true;
        if (!"agent".equals(n.kind) || flow == null) return false;
        for (AgentSpec s : flow.subAgents()) {
            if (s.nodeId.equals(n.nodeId)) return true;
        }
        return false;
    }
}
