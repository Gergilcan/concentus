package com.concentus.service;

import com.concentus.model.RunEvent;
import com.concentus.model.RunSummary;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** In-memory state for one launched flow: status, session ids, buffered output, live listeners. */
public class AgentRun {

    private static final int MAX_BUFFER = 4000;

    public final String id;
    public final String flowId;
    public final String flowName;
    public final String mode;
    public final long createdAt = System.currentTimeMillis();

    public volatile String status = "STARTING"; // STARTING | RUNNING | IDLE | ERROR | TERMINATED
    public volatile String sessionId;
    public volatile List<String> agentIds = List.of();
    public volatile String error;

    /** "cloud" (Managed Agents / API key) or "local" (claude CLI / subscription). */
    public volatile String backend = "cloud";

    /** How this execution was triggered: "manual" | "prompt" | "cron". */
    public volatile String trigger = "manual";
    /** Initial input to fire automatically once the run is ready (null = wait for the user). */
    public volatile String pendingPrompt;

    /** Open event stream (cloud), stored so {@code stop()} can break the loop. */
    public volatile AutoCloseable stream;

    // --- local (claude CLI) run state ---
    public volatile CompiledFlow compiled;
    public volatile String localSessionId;
    public volatile boolean localStarted = false;
    public volatile Process localProcess;

    private final CopyOnWriteArrayList<RunEvent> buffer = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<RunEvent>> listeners = new CopyOnWriteArrayList<>();

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
        return new RunSummary(id, flowId, flowName, mode, status, createdAt, sessionId, agentIds, error, trigger);
    }
}
