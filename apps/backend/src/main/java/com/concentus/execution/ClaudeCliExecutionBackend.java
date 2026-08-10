package com.concentus.execution;

import com.concentus.service.AgentRun;
import com.concentus.service.CompiledFlow;
import com.concentus.service.FanoutExecutor;
import com.concentus.service.LocalClaudeExecutor;
import com.concentus.support.LocalClaudeSupport;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * The {@code claude} CLI running on the host, against a Claude subscription.
 *
 * <p>This is the backend that most wants its own container: it needs Node, the Claude Code npm
 * package and access to the user's real folders, none of which the rest of app-backend uses. Behind
 * this interface, moving it becomes a deployment change rather than a rewrite.
 */
@Component
public class ClaudeCliExecutionBackend implements ExecutionBackend {

    private final LocalClaudeExecutor executor;
    private final FanoutExecutor fanout;
    private final LocalClaudeSupport support;

    public ClaudeCliExecutionBackend(LocalClaudeExecutor executor, FanoutExecutor fanout,
                                     LocalClaudeSupport support) {
        this.executor = executor;
        this.fanout = fanout;
        this.support = support;
    }

    @Override
    public String id() {
        return "local";
    }

    @Override
    public String displayName() {
        return "Claude CLI (your subscription)";
    }

    @Override
    public boolean isAvailable() {
        // available(), not command(): command() falls back to bare "claude" for PATH resolution and
        // so is never empty, which would report this backend usable on a machine with no CLI and no
        // login at all. available() checks for both.
        return support.available();
    }

    @Override
    public boolean supportsModel(String model) {
        return model != null && model.toLowerCase(Locale.ROOT).startsWith("claude-");
    }

    @Override
    public void runTurn(AgentRun run, CompiledFlow flow, String userText) {
        // The flow's own choice, read from the run's compiled snapshot so editing the flow
        // mid-run cannot move an already-running flow between execution styles.
        if (flow.fanout()) {
            fanout.runTurn(run, flow, userText);
        } else {
            executor.runTurn(run, flow, userText);
        }
    }

    @Override
    public void stop(AgentRun run) {
        // Workers first: executor.stop marks the run TERMINATED, and a fan-out that only killed
        // the coordinator would leave N claude processes still working for a dead run.
        fanout.stopWorkers(run);
        executor.stop(run);
    }
}
