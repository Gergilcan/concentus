package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.config.AgentSpec.McpServerSpec;
import com.concentus.model.NodeExec;
import com.concentus.model.RunEvent;
import com.concentus.support.LocalClaudeSupport;
import com.fasterxml.jackson.databind.JsonNode;
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
    private final ObjectMapper mapper;
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
        this.mapper = mapper;
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
                handleLine(run, line);
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
                if (ne != null) { ne.status = "passed"; ne.output = "already configured"; ne.endedAt = System.currentTimeMillis(); }
                continue; // already configured — stay quiet
            }
            String status = mcpRegistry.add(m.name, m.url, m.resolveToken());
            if (ne != null) {
                boolean bad = status != null && status.toLowerCase().contains("fail");
                ne.status = bad ? "failed" : "passed";
                ne.output = status;
                if (bad) ne.error = status;
                ne.endedAt = System.currentTimeMillis();
            }
            if ("already configured".equals(status)) {
                continue; // registered concurrently / list was stale — stay quiet
            }
            run.emit(RunEvent.of("system", "MCP '" + m.name + "' → " + status));
        }
    }

    // ------------------------------------------------------------- stream-json

    private void handleLine(AgentRun run, String line) {
        String t = line.trim();
        if (t.isEmpty()) return;
        JsonNode node;
        try {
            node = mapper.readTree(t);
        } catch (Exception e) {
            run.emit(RunEvent.of("system", t)); // non-JSON progress / error text
            return;
        }
        switch (node.path("type").asText("")) {
            case "system" -> {
                if ("init".equals(node.path("subtype").asText())) {
                    run.emit(RunEvent.of("system", "Local session ready (model "
                            + node.path("model").asText("?") + ")."));
                }
            }
            case "assistant" -> handleAssistant(run, node);
            case "user" -> handleUser(run, node);
            case "result" -> {
                accrueTotals(run, node.path("usage"));
                NodeExec coordExec = coordExec(run);
                if (node.path("is_error").asBoolean(false)) {
                    String err = node.path("result").asText("run failed");
                    if (coordExec != null) { coordExec.status = "failed"; coordExec.error = err; coordExec.endedAt = System.currentTimeMillis(); }
                    // Mark the run itself failed so it shows as failed and triggers notifications.
                    run.status = "ERROR";
                    run.error = err;
                    run.emit(RunEvent.of("error", err));
                } else {
                    if (coordExec != null && !"failed".equals(coordExec.status)) {
                        coordExec.status = "passed";
                        coordExec.endedAt = System.currentTimeMillis();
                    }
                    run.emit(RunEvent.of("status", "idle"));
                }
            }
            case "rate_limit_event" -> {
                String status = node.path("rate_limit_info").path("status").asText("");
                if (!status.isEmpty() && !"allowed".equals(status)) {
                    run.emit(RunEvent.of("system", "Rate limit: " + status));
                }
            }
            default -> {
                // stream_event / etc. — ignored for the console
            }
        }
    }

    /** Attribute an assistant message's text, tool calls, and token usage to the right node. */
    private void handleAssistant(AgentRun run, JsonNode node) {
        String parent = node.path("parent_tool_use_id").asText("");
        boolean isSub = !parent.isBlank() && run.taskToNode.containsKey(parent);
        String targetNodeId = isSub ? run.taskToNode.get(parent) : coordNodeId(run);
        String label = isSub ? "subagent" : "coordinator";
        NodeExec target = run.nodeExec(targetNodeId, "agent", agentLabel(run, targetNodeId));

        JsonNode usage = node.path("message").path("usage");
        if (target != null && usage.isObject()) {
            target.outputTokens += usage.path("output_tokens").asLong(0);
            target.inputTokens += usage.path("input_tokens").asLong(0)
                    + usage.path("cache_read_input_tokens").asLong(0);
        }

        JsonNode content = node.path("message").path("content");
        if (!content.isArray()) return;
        for (JsonNode b : content) {
            String bt = b.path("type").asText("");
            if ("text".equals(bt)) {
                String text = b.path("text").asText("");
                if (!text.isBlank()) {
                    if (target != null) target.appendOutput(text);
                    run.emit(RunEvent.of("agent_message", text, label));
                }
            } else if ("tool_use".equals(bt)) {
                String name = b.path("name").asText("tool");
                if ("Task".equals(name)) {
                    String subtype = b.path("input").path("subagent_type").asText("");
                    String subNodeId = subNodeIdByAgentName(run, subtype);
                    if (subNodeId != null) {
                        NodeExec sub = run.nodeExec(subNodeId, "agent", agentLabel(run, subNodeId));
                        if (sub != null) {
                            sub.status = "running";
                            String brief = b.path("input").path("prompt").asText(
                                    b.path("input").path("description").asText(""));
                            sub.appendInput(brief);
                        }
                        run.taskToNode.put(b.path("id").asText(""), subNodeId);
                        run.emit(RunEvent.of("tool_use", "Task → " + subtype));
                        continue;
                    }
                }
                run.emit(RunEvent.of("tool_use", name));
            }
        }
    }

    /** A user event may carry tool_result blocks — a Task result closes out that sub-agent node. */
    private void handleUser(AgentRun run, JsonNode node) {
        JsonNode content = node.path("message").path("content");
        if (!content.isArray()) return;
        for (JsonNode b : content) {
            if ("tool_result".equals(b.path("type").asText(""))) {
                String id = b.path("tool_use_id").asText("");
                String subNodeId = run.taskToNode.get(id);
                if (subNodeId != null) {
                    NodeExec sub = run.nodeExec(subNodeId, "agent", agentLabel(run, subNodeId));
                    if (sub != null && !"failed".equals(sub.status)) {
                        sub.status = b.path("is_error").asBoolean(false) ? "failed" : "passed";
                        if (b.path("is_error").asBoolean(false)) sub.error = "sub-agent reported an error";
                        sub.endedAt = System.currentTimeMillis();
                    }
                }
            }
        }
    }

    private void accrueTotals(AgentRun run, JsonNode usage) {
        if (!usage.isObject()) return;
        run.totalInputTokens += usage.path("input_tokens").asLong(0)
                + usage.path("cache_read_input_tokens").asLong(0)
                + usage.path("cache_creation_input_tokens").asLong(0);
        run.totalOutputTokens += usage.path("output_tokens").asLong(0);
    }

    private static String coordNodeId(AgentRun run) {
        return run.compiled == null ? null : run.compiled.coordinator().nodeId;
    }

    private NodeExec coordExec(AgentRun run) {
        String id = coordNodeId(run);
        return id == null ? null : run.nodeExec(id, "agent", run.compiled.coordinator().name);
    }

    private static String agentLabel(AgentRun run, String nodeId) {
        if (run.compiled == null || nodeId == null) return nodeId;
        if (nodeId.equals(run.compiled.coordinator().nodeId)) return run.compiled.coordinator().name;
        for (AgentSpec s : run.compiled.subAgents()) {
            if (nodeId.equals(s.nodeId)) return s.name;
        }
        return nodeId;
    }

    private static String subNodeIdByAgentName(AgentRun run, String sanitizedName) {
        if (run.compiled == null || sanitizedName == null || sanitizedName.isBlank()) return null;
        for (AgentSpec s : run.compiled.subAgents()) {
            if (sanitize(s.name).equals(sanitizedName)) return s.nodeId;
        }
        return null;
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

    private static String sanitize(String s) {
        if (s == null || s.isBlank()) return "agent";
        return s.trim().toLowerCase().replaceAll("[^a-z0-9_-]+", "-");
    }
}
