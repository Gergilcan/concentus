package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.config.AgentSpec.McpServerSpec;
import com.concentus.model.NodeExec;
import com.concentus.model.RunEvent;
import com.concentus.support.LocalClaudeSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs a flow locally by driving the {@code claude} CLI (Claude Code) on the user's
 * subscription login — no API key required.
 *
 * <p>Turn-based: each command spawns {@code claude -p ... --output-format stream-json},
 * continuing the same Claude session via {@code --session-id} / {@code --resume}. Large
 * or structured inputs are written to files the CLI auto-discovers ({@code CLAUDE.md},
 * {@code .claude/agents/*.md}, an MCP config file) rather than passed as shell args, which
 * keeps the command line small and avoids cross-platform quoting issues.
 */
@Component
public class LocalClaudeExecutor {

    private final LocalClaudeSupport support;
    private final RagContextInjector ragInjector;
    private final McpRegistry mcpRegistry;
    private final LocalStreamEventHandler streamHandler;
    private final String permissionMode;
    private final String dataDir;
    private final boolean autoRegisterMcp;

    public LocalClaudeExecutor(LocalClaudeSupport support, RagContextInjector ragInjector,
                               McpRegistry mcpRegistry, ObjectMapper mapper,
                               @Value("${local.permission-mode:bypassPermissions}") String permissionMode,
                               @Value("${app.data-dir}") String dataDir,
                               @Value("${local.auto-register-mcp:true}") boolean autoRegisterMcp) {
        this.support = support;
        this.ragInjector = ragInjector;
        this.mcpRegistry = mcpRegistry;
        this.streamHandler = new LocalStreamEventHandler(mapper);
        this.permissionMode = permissionMode;
        this.dataDir = dataDir;
        this.autoRegisterMcp = autoRegisterMcp;
    }

    /** Runs one turn and streams events into the run. Blocking — call on a worker thread. */
    public void runTurn(AgentRun run, CompiledFlow flow, String userText) {
        String cmd = support.command().orElse(null);
        if (cmd == null) {
            fail(run, "The claude CLI was not found. Install Claude Code or set local.claude-command.");
            return;
        }

        boolean first = !run.localStarted;
        // Absolute so the CLI (whose cwd IS this dir) doesn't re-resolve --mcp-config against it.
        Path workdir = Path.of(dataDir, "local", run.id).toAbsolutePath().normalize();
        try {
            if (first) {
                prepareWorkspace(run, flow, workdir);
            }
        } catch (IOException e) {
            fail(run, "Failed to prepare local workspace: " + e.getMessage());
            return;
        }

        // Coordinator node execution: record this turn's input and mark it running.
        AgentSpec coord = flow.coordinator();
        NodeExec coordExec = run.nodeExec(coord.nodeId, "agent", coord.name);
        if (coordExec != null) {
            coordExec.appendInput(userText);
            coordExec.status = "running";
        }

        List<String> args = buildArgs(cmd, run, workdir, first, userText);
        run.status = "RUNNING";
        run.emit(RunEvent.of("system", "› " + userText));

        ProcessBuilder pb = new ProcessBuilder(args).directory(workdir.toFile());
        pb.redirectErrorStream(true);

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            fail(run, "Failed to start claude: " + e.getMessage());
            return;
        }
        run.localProcess = proc;
        run.localStarted = true;
        // The prompt is passed via -p; close the child's stdin so it doesn't wait for piped input.
        try {
            proc.getOutputStream().close();
        } catch (IOException ignored) {
            // best effort
        }

        try (BufferedReader reader = proc.inputReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                streamHandler.handleLine(run, line);
            }
            proc.waitFor();
        } catch (Exception e) {
            run.emit(RunEvent.of("system", "Local run ended: " + e.getMessage()));
        } finally {
            run.localProcess = null;
            if (!"TERMINATED".equals(run.status) && !"ERROR".equals(run.status)) {
                run.status = "IDLE";
            }
        }
    }

    public void stop(AgentRun run) {
        Process p = run.localProcess;
        if (p != null) {
            p.destroy();
        }
        run.status = "TERMINATED";
    }

    // ------------------------------------------------------------- workspace

    private void prepareWorkspace(AgentRun run, CompiledFlow flow, Path workdir) throws IOException {
        Files.createDirectories(workdir);

        // Inject SQL/RAG context into each agent's prompt (once); record per-node for the UI.
        ragInjector.inject(flow.coordinator(), run, m -> run.emit(RunEvent.of("system", m)));
        for (AgentSpec sub : flow.subAgents()) {
            ragInjector.inject(sub, run, m -> run.emit(RunEvent.of("system", m)));
        }

        // Coordinator instructions -> CLAUDE.md (auto-loaded as project context).
        AgentSpec coord = flow.coordinator();
        if (coord.systemPrompt != null && !coord.systemPrompt.isBlank()) {
            Files.writeString(workdir.resolve("CLAUDE.md"), coord.systemPrompt);
        }

        // Sub-agents -> .claude/agents/<name>.md (auto-discovered custom subagents).
        if (!flow.subAgents().isEmpty()) {
            Path agentsDir = workdir.resolve(".claude").resolve("agents");
            Files.createDirectories(agentsDir);
            for (AgentSpec sub : flow.subAgents()) {
                String name = sanitize(sub.name);
                String md = "---\n"
                        + "name: " + name + "\n"
                        + "description: " + delegationDescription(sub) + "\n"
                        + "model: " + modelAlias(sub.model.id) + "\n"
                        + "---\n"
                        + (sub.systemPrompt == null ? "" : sub.systemPrompt) + "\n";
                Files.writeString(agentsDir.resolve(name + ".md"), md);
            }
            run.emit(RunEvent.of("system", flow.subAgents().size() + " sub-agent(s) available for delegation."));
        }

        registerMcpServers(run);
    }

    private List<String> buildArgs(String cmd, AgentRun run, Path workdir, boolean first, String userText) {
        AgentSpec coord = run.compiled.coordinator();
        List<String> a = new ArrayList<>();
        a.add(cmd);
        a.add("-p");
        a.add(userText);
        a.add("--output-format");
        a.add("stream-json");
        a.add("--verbose");
        a.add("--permission-mode");
        a.add(permissionMode);
        a.add("--model");
        a.add(modelAlias(coord.model.id));

        // MCP servers are registered into the user's Claude Code list (see registerMcpServers),
        // so no --mcp-config / --strict-mcp-config here — claude uses the user's own MCP list.
        if (first) {
            a.add("--session-id");
            a.add(run.localSessionId);
        } else {
            a.add("--resume");
            a.add(run.localSessionId);
        }
        return a;
    }

    /**
     * Registers each MCP node into the user's Claude Code MCP list (if missing), so the CLI
     * uses it with its own auth handling. Nodes with a token are added with a bearer header;
     * OAuth servers are added and the user is told to run {@code claude mcp login}.
     */
    private void registerMcpServers(AgentRun run) {
        List<McpServerSpec> mcps = new ArrayList<>(run.compiled.coordinator().mcpServers);
        for (AgentSpec sub : run.compiled.subAgents()) {
            mcps.addAll(sub.mcpServers);
        }
        if (mcps.isEmpty()) return;

        if (!autoRegisterMcp) {
            run.emit(RunEvent.of("system",
                    "MCP auto-registration is off — relying on your existing Claude Code MCP list."));
            return;
        }

        Set<String> existing = new HashSet<>();
        mcpRegistry.list().forEach(s -> existing.add(s.name().toLowerCase()));

        Set<String> handled = new HashSet<>();
        for (McpServerSpec m : mcps) {
            if (m.name == null || m.name.isBlank()) continue;
            NodeExec ne = run.nodeExec(m.nodeId, "mcp", m.name);
            if (ne != null) ne.input = m.url;
            String key = m.name.toLowerCase();
            if (!handled.add(key)) continue;
            if (existing.contains(key)) {
                markMcpResult(ne, "already configured");
                continue; // already configured — stay quiet
            }
            String status = mcpRegistry.add(m.name, m.url, m.resolveToken());
            markMcpResult(ne, status);
            if ("already configured".equals(status)) {
                continue; // registered concurrently / list was stale — stay quiet
            }
            run.emit(RunEvent.of("system", "MCP '" + m.name + "' → " + status));
        }
    }

    /** Records an MCP registration outcome on its node execution; a no-op if {@code ne} is null. */
    private static void markMcpResult(NodeExec ne, String status) {
        if (ne == null) return;
        boolean bad = status != null && status.toLowerCase().contains("fail");
        ne.status = bad ? "failed" : "passed";
        ne.output = status;
        if (bad) ne.error = status;
        ne.endedAt = System.currentTimeMillis();
    }

    private void fail(AgentRun run, String message) {
        run.status = "ERROR";
        run.error = message;
        run.emit(RunEvent.of("error", message));
    }

    /**
     * The subagent's routing signal. Claude Code's coordinator reads this to decide when to
     * hand a task off (via the Task tool). A vague description means the coordinator does the
     * work itself, so a scoped, "use PROACTIVELY"-style line is what makes delegation happen.
     */
    private static String delegationDescription(AgentSpec sub) {
        if (sub.description != null && !sub.description.isBlank()) {
            return sub.description.replaceAll("\\s+", " ").trim();
        }
        return "Use PROACTIVELY for all " + sub.name + " tasks. Give it only the part of the "
                + "plan it needs — its own files and scope — not the whole request.";
    }

    static String modelAlias(String id) {
        if (id == null) return "opus";
        String s = id.toLowerCase();
        if (s.contains("opus")) return "opus";
        if (s.contains("sonnet")) return "sonnet";
        if (s.contains("haiku")) return "haiku";
        if (s.contains("fable")) return "fable";
        return id;
    }

    static String sanitize(String s) {
        if (s == null || s.isBlank()) return "agent";
        return s.trim().toLowerCase().replaceAll("[^a-z0-9_-]+", "-");
    }
}
