package com.concentus.execution;

import com.concentus.service.AgentRun;
import com.concentus.service.CompiledFlow;

/**
 * One way of executing a turn of a flow — the seam behind {@code AgentRun.backend}.
 *
 * <p>Orchestration deliberately stays above this line: the flow compiler, delegation chains,
 * per-agent attribution, cost and persistence all live in app-backend. A backend only executes a
 * turn; it never decides who delegates to whom. Duplicating that per execution path is exactly how
 * local and cloud attribution diverged once already.
 *
 * <p>Backends needing a heavyweight local runtime — the {@code claude} CLI, the Copilot CLI in
 * headless mode — are candidates to move into their own container. This interface is what makes
 * that a deployment change rather than a rewrite: callers already talk to an implementation, not
 * to a named executor.
 *
 * <p>Not to be confused with {@code com.concentus.runner.AgentRunner}, which drives the standalone
 * YAML CLI and writes to stdout.
 */
public interface ExecutionBackend {

    /** Stable id, matching what is stored in {@code AgentRun.backend}: "local" or "cloud". */
    String id();

    /** Name shown in the designer when explaining where a flow will run. */
    String displayName();

    /**
     * One line naming where this run executes, shown at run start. On the backend rather than
     * decided by id at the call site — that comparison was exactly the registry-bypass this
     * interface exists to prevent, and a third backend would have been announced as "running on
     * your Claude subscription".
     */
    default String startupDescription() {
        return "Local mode — running on your Claude subscription";
    }

    /**
     * Whether this backend can execute right now — CLI installed, service reachable, credential
     * present. Checked so a missing dependency surfaces at launch rather than mid-turn, and so the
     * designer can avoid offering models it cannot actually run.
     */
    boolean isAvailable();

    /**
     * Whether this backend serves the given model id. Exactly one backend should claim a model;
     * the resolver treats a second claim as a configuration problem rather than picking arbitrarily.
     */
    boolean supportsModel(String model);

    /**
     * Whether this backend is driven a turn at a time by us, rather than launching a session that
     * runs itself.
     *
     * <p>The distinction decides what {@code start()} does: a turn-based backend sits idle until
     * given a first instruction, while a session-based one is launched up front and streams back.
     * Getting it wrong means either a run that never starts or one that starts twice.
     *
     * <p>True for the {@code claude} CLI and for a self-hosted model; false for Managed Agents,
     * which owns its own loop once launched.
     */
    default boolean isTurnBased() {
        return true;
    }

    /** Runs one turn. Blocking — callers run it on a worker thread. */
    void runTurn(AgentRun run, CompiledFlow flow, String userText);

    /** Stops an in-flight turn. Best effort; a backend with no child process may simply mark it. */
    default void stop(AgentRun run) {
        run.status = "TERMINATED";
    }
}
