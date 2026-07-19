package com.concentus.service;

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

    public volatile String status = "STARTING"; // STARTING | RUNNING | IDLE | ERROR | TERMINATED
    public volatile String sessionId;
    public volatile List<String> agentIds = List.of();
    public volatile String error;

    /** "cloud" (Managed Agents / API key) or "local" (claude CLI / subscription). */
    public volatile String backend = "cloud";

    /** How this execution was triggered: "manual" | "prompt" | "cron" | "webhook". */
    public volatile String trigger = "manual";
    /** Initial input to fire automatically once the run is ready (null = wait for the user). */
    public volatile String pendingPrompt;
    /** The first input this run was given — replayed when the execution is retried. */
    public volatile String initialPrompt;
    /** Flow's failure-notification URL, copied at start so it survives flow edits. */
    public volatile String notifyWebhook;
    /** USD per million tokens, used for the cost estimate shown in the UI. */
    public volatile double inputUsdPerMTok;
    public volatile double outputUsdPerMTok;

    /** Open event stream (cloud), stored so {@code stop()} can break the loop. */
    public volatile AutoCloseable stream;

    // --- local (claude CLI) run state ---
    public volatile CompiledFlow compiled;
    public volatile String localSessionId;
    public volatile boolean localStarted = false;
    public volatile Process localProcess;

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

    /** Get or create the execution record for a node. Returns null if nodeId is unknown. */
    public NodeExec nodeExec(String nodeId, String kind, String label) {
        if (nodeId == null || nodeId.isBlank()) return null;
        synchronized (nodeExecs) {
            return nodeExecs.computeIfAbsent(nodeId, k -> {
                NodeExec n = new NodeExec();
                n.nodeId = nodeId;
                n.kind = kind;
                n.label = label;
                n.startedAt = System.currentTimeMillis();
                return n;
            });
        }
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
        // Cache reads bill at ~0.1x the input rate and cache writes at ~1.25x, so each is weighted
        // rather than counted as ordinary input.
        double billableInput = totalInputTokens + (cacheReadTokens * 0.1) + (cacheWriteTokens * 1.25);
        double cost = (billableInput / 1_000_000d) * inputUsdPerMTok
                + (totalOutputTokens / 1_000_000d) * outputUsdPerMTok;
        return new RunSummary(id, flowId, flowName, mode, status, createdAt, sessionId, agentIds, error,
                trigger, totalInputTokens, totalOutputTokens, Math.round(cost * 10_000d) / 10_000d);
    }
}
