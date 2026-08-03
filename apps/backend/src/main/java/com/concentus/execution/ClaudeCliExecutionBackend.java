package com.concentus.execution;

import com.concentus.service.AgentRun;
import com.concentus.service.CompiledFlow;
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
    private final LocalClaudeSupport support;

    public ClaudeCliExecutionBackend(LocalClaudeExecutor executor, LocalClaudeSupport support) {
        this.executor = executor;
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
        executor.runTurn(run, flow, userText);
    }

    @Override
    public void stop(AgentRun run) {
        executor.stop(run);
    }
}
