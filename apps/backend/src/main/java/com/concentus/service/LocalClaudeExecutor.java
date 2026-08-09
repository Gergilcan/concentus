package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.config.AgentSpec.McpServerSpec;
import com.concentus.model.NodeExec;
import com.concentus.git.GitWorkspace;
import com.concentus.model.RunEvent;
import com.concentus.support.LocalClaudeSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Runs a flow locally by driving the {@code claude} CLI (Claude Code) on the user's
 * subscription login — no API key required.
 *
 * <p>Turn-based: each command spawns {@code claude -p ... --output-format stream-json},
 * continuing the same Claude session via {@code --session-id} / {@code --resume}. Large
 * or structured inputs are written to files the CLI auto-discovers ({@code CLAUDE.md},
 * {@code .claude/agents/*.md}, an MCP config file) rather than passed as shell args, which
 * keeps the command line small and avoids cross-platform quoting issues.
 *
 * <p>The prompt itself follows the same rule once it stops being small: past
 * {@link #MAX_INLINE_PROMPT_CHARS} it is piped on stdin instead of passed as an argument. A
 * mail-triggered run carries a whole email, and a command line is not sized for that.
 */
@Component
public class LocalClaudeExecutor {

    private final LocalClaudeSupport support;
    private final RagContextInjector ragInjector;
    private final McpRegistry mcpRegistry;
    private final LocalStreamEventHandler streamHandler;
    private final ContextFolderResolver contextFolders;
    private final GitWorkspace gitWorkspace;
    private final String permissionMode;
    private final String dataDir;
    private final boolean autoRegisterMcp;
    private final ObjectMapper mapper;
    private final com.concentus.llm.McpOAuthStore mcpOAuthStore;
    private final com.concentus.auth.OrgContext orgContext;
    /** See {@link #writeMcpConfig}: runs see only the flow's MCP servers, not the user's list. */
    private final boolean strictMcp;
    /**
     * The flow's own permission mode meaning "stop and ask a human before acting".
     *
     * <p>Not a CLI mode — the CLI has no way to ask anyone from a piped process. It is expressed
     * as: run the first turn in `plan`, which proposes without touching anything, then hold the
     * run until somebody approves and resume the same session with permission to act.
     */
    public static final String APPROVAL_MODE = "approval";

    /** True while this run is in approval mode and nobody has approved it yet. */
    static boolean awaitingApproval(AgentRun run) {
        return APPROVAL_MODE.equalsIgnoreCase(run.permissionMode) && !run.approved;
    }

    /** The per-run MCP config file, inside the run's workdir. */
    static final String MCP_CONFIG_FILE = "mcp-config.json";

    /**
     * Above this many characters the prompt goes in on stdin instead of as a {@code -p} argument.
     *
     * <p>Command lines are small, and an email is not. Windows caps a whole command line at 32,767
     * characters, and the {@code claude} launcher is a script, so it goes through {@code cmd.exe},
     * which caps at 8,191; Linux caps a <em>single</em> argument at 128 KB. A mail-triggered run
     * carrying a body plus extracted attachment text passes all three limits easily, and the
     * failure is a bare {@code CreateProcess error=206} that names nothing.
     *
     * <p>Deliberately well under the smallest limit: the rest of the command line — context
     * directories, the session id, the model — has to fit too.
     */
    private static final int MAX_INLINE_PROMPT_CHARS = 4000;

    public LocalClaudeExecutor(LocalClaudeSupport support, RagContextInjector ragInjector,
                               McpRegistry mcpRegistry, ContextFolderResolver contextFolders,
                               GitWorkspace gitWorkspace,
                               ObjectMapper mapper,
                               com.concentus.llm.McpOAuthStore mcpOAuthStore,
                               com.concentus.auth.OrgContext orgContext,
                               @Value("${local.permission-mode:bypassPermissions}") String permissionMode,
                               @Value("${app.data-dir}") String dataDir,
                               @Value("${local.auto-register-mcp:true}") boolean autoRegisterMcp,
                               @Value("${local.strict-mcp:true}") boolean strictMcp) {
        this.support = support;
        this.ragInjector = ragInjector;
        this.mcpRegistry = mcpRegistry;
        this.contextFolders = contextFolders;
        this.gitWorkspace = gitWorkspace;
        this.streamHandler = new LocalStreamEventHandler(mapper);
        this.mapper = mapper;
        this.mcpOAuthStore = mcpOAuthStore;
        this.orgContext = orgContext;
        this.permissionMode = permissionMode;
        this.dataDir = dataDir;
        this.autoRegisterMcp = autoRegisterMcp;
        this.strictMcp = strictMcp;
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

        // Rejections are reported on the first turn only, so a resumed session doesn't repeat them.
        List<Path> contextDirs = resolveContextDirs(run, flow, first);
        boolean promptOnStdin = userText.length() > MAX_INLINE_PROMPT_CHARS;
        List<String> args = buildArgs(cmd, run, workdir, first, userText, contextDirs, promptOnStdin);
        run.status = "RUNNING";
        run.emit(RunEvent.of("system", "› " + userText));

        ProcessBuilder pb = new ProcessBuilder(args).directory(workdir.toFile());
        pb.redirectErrorStream(true);
        // Push credentials for the flow's repositories. On the process environment rather than in
        // any .git/config, so the token is not written to disk and does not appear in
        // `git remote -v` or in anything the agent might paste into a commit or a PR body.
        pb.environment().putAll(GitWorkspace.environmentFor(run.checkouts));

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            fail(run, "Failed to start claude: " + e.getMessage());
            return;
        }
        run.localProcess = proc;
        run.localStarted = true;
        if (promptOnStdin) {
            writePromptToStdin(proc, userText);
        } else {
            // The prompt went in as an argument; close stdin so the CLI doesn't wait for input.
            try {
                proc.getOutputStream().close();
            } catch (IOException ignored) {
                // best effort
            }
        }

        // UTF-8 explicitly. Process.inputReader() with no argument decodes with `native.encoding`
        // — the OS charset, which on a Spanish Windows is Cp1252 — while the CLI emits UTF-8. The
        // mismatch does not fail: it silently turns every accent, curly quote and em dash in the
        // agent's output into mojibake, and file.encoding being UTF-8 does not cover this reader.
        try (BufferedReader reader = proc.inputReader(StandardCharsets.UTF_8)) {
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
                // Not IDLE: idle means "waiting for whatever you want next", and this run is
                // waiting for one specific answer. The distinct status is what lets the UI offer
                // Approve/Reject and the desktop shell raise a notification.
                boolean waiting = awaitingApproval(run);
                run.status = waiting ? "AWAITING_APPROVAL" : "IDLE";
                if (waiting) {
                    run.emit(RunEvent.of("system",
                            "Waiting for your approval — nothing has been changed yet."));
                }
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

    /**
     * Tells the agent which repositories are checked out and how to hand work back.
     *
     * <p>Written into {@code CLAUDE.md} rather than left to be discovered: the agent is sitting in
     * a scratch directory with some folders in it, and nothing about that says "these are git
     * checkouts you may push, on a branch, and open a pull request from".
     *
     * <p>Branch-and-PR rather than pushing to the default branch, stated explicitly. An agent
     * driven by an email from a stranger must not be one commit away from a protected branch, and
     * a proposal a human reviews is the whole point of the workflow.
     */
    private static void appendRepositoryNote(AgentRun run, StringBuilder md) {
        List<GitWorkspace.Checkout> ok = run.checkouts.stream().filter(GitWorkspace.Checkout::ok).toList();
        List<GitWorkspace.Checkout> failed = run.checkouts.stream().filter(c -> !c.ok()).toList();
        if (ok.isEmpty() && failed.isEmpty()) return;

        md.append("\n## Repositories checked out for you\n\n");
        for (GitWorkspace.Checkout c : ok) {
            md.append("- `./").append(c.folderName()).append("` — ").append(c.spec().url);
            if (c.spec().branch != null && !c.spec().branch.isBlank()) {
                md.append(" (branch `").append(c.spec().branch).append("`)");
            }
            md.append('\n');
        }
        for (GitWorkspace.Checkout c : failed) {
            // A group node that could not be listed has no URL to name it by — say which group it
            // was, or the agent is told "null could not be cloned".
            String what = c.spec().isGroup() ? "the group `" + c.spec().group + "`" : c.spec().url;
            md.append("- ").append(what).append(" — **could not be cloned**: ")
              .append(c.error()).append(". Do not guess at its contents.\n");
        }

        if (ok.isEmpty()) return;
        md.append("""

            They are real clones with a working remote, and pushing is already authenticated — you
            do not need, and will not be given, a token to paste anywhere.

            To hand work back:

            1. Create a branch. Never commit to the default branch, and never force-push.
            2. Make the change, and keep it to what was actually asked for.
            3. Commit with a message that says what changed and why.
            4. `git push -u origin <branch>`.
            5. Open the pull request (GitHub) or merge request (GitLab) through that provider's MCP
               server, targeting the default branch. On GitLab you may instead push with
               `-o merge_request.create -o merge_request.target=<default-branch>`, which opens the
               MR from the push itself.

            Then report the branch name and the PR/MR URL.

            If the change is not safe to make automatically — the request is ambiguous, the tests
            do not pass, or it would touch something unrelated — stop and say so instead. An
            unopened PR costs someone five minutes; a wrong one costs a review and a revert.
            """);
    }

    private void prepareWorkspace(AgentRun run, CompiledFlow flow, Path workdir) throws IOException {
        Files.createDirectories(workdir);

        // Clone the flow's repositories into this workdir, which is already the CLI's working
        // directory — so the checkouts are simply there, needing no --add-dir grant and no entry
        // in local.context-roots. A directory this process created for this run is not the
        // filesystem exposure that allowlist exists to prevent.
        run.checkouts = gitWorkspace.prepare(flow.allRepos(), workdir);
        for (GitWorkspace.Checkout c : run.checkouts) {
            run.emit(RunEvent.of("system", c.ok()
                    ? "Cloned " + c.spec().url + " into ./" + c.folderName()
                    : "Could not clone " + c.spec().url + ": " + c.error()));
        }

        // Inject SQL/RAG context into each agent's prompt (once); record per-node for the UI.
        ragInjector.inject(flow.coordinator(), run, m -> run.emit(RunEvent.of("system", m)));
        for (AgentSpec sub : flow.subAgents()) {
            ragInjector.inject(sub, run, m -> run.emit(RunEvent.of("system", m)));
        }

        // Coordinator instructions -> CLAUDE.md (auto-loaded as project context). A referenced
        // CLAUDE.md is inlined rather than relied on for discovery: the CLI's cwd is this scratch
        // workspace, not the user's project, so it would never be found by walking up from here.
        AgentSpec coord = flow.coordinator();
        StringBuilder claudeMd = new StringBuilder();
        appendReferencedClaudeMd(run, coord, claudeMd);
        if (coord.systemPrompt != null && !coord.systemPrompt.isBlank()) {
            claudeMd.append(coord.systemPrompt).append('\n');
        }
        appendContextFolderNote(coord, claudeMd);
        appendRepositoryNote(run, claudeMd);
        appendDelegationRoster(coord, claudeMd);
        if (!claudeMd.isEmpty()) {
            Files.writeString(workdir.resolve("CLAUDE.md"), claudeMd.toString());
        }

        // Sub-agents -> .claude/agents/<name>.md (auto-discovered custom subagents).
        if (!flow.subAgents().isEmpty()) {
            Path agentsDir = workdir.resolve(".claude").resolve("agents");
            Files.createDirectories(agentsDir);
            for (AgentSpec sub : flow.subAgents()) {
                // cliName, not sanitize(name): the compiler already made it unique, so two nodes
                // both called "Code Reviewer" get their own file instead of one clobbering the other.
                String name = sub.cliName;
                StringBuilder body = new StringBuilder();
                appendReferencedClaudeMd(run, sub, body);
                if (sub.systemPrompt != null) body.append(sub.systemPrompt).append('\n');
                appendContextFolderNote(sub, body);
                appendDelegationRoster(sub, body);
                String md = "---\n"
                        + "name: " + name + "\n"
                        + "description: " + delegationDescription(sub) + "\n"
                        + "model: " + modelAlias(sub.model.id) + "\n"
                        + "---\n"
                        + body;
                Files.writeString(agentsDir.resolve(name + ".md"), md);
            }
            run.emit(RunEvent.of("system", flow.subAgents().size() + " sub-agent(s) available for delegation."));
        }

        registerMcpServers(run);
        writeMcpConfig(run, workdir);
    }

    /**
     * Writes the run's own MCP configuration: exactly the servers wired into this flow.
     *
     * <p>This is what confines a run to its flow. Without it the CLI used the user's whole MCP
     * list, so an agent in a "summarise my mail" flow could also see the Holded server registered
     * last month for something else — every tool the user had ever configured, exposed to every
     * flow. The config file plus {@code --strict-mcp-config} makes the canvas the truth: what is
     * drawn is what the run can reach, and a flow with no MCP nodes reaches nothing.
     *
     * <p>Written even when empty, deliberately — an absent file would mean "fall back to the
     * user's list", which is the exposure this exists to close.
     *
     * <p>Auth per server, in order: the node's stored credential; else an OAuth grant Concentus
     * holds for that URL (refreshed on read); else no header, which leaves room for a grant the
     * CLI itself holds from {@code claude mcp login}. The file lands in the run's scratch workdir;
     * the token was already stored by {@code claude mcp add -H} in the user's own config before
     * this change, so this moves where a token rests rather than newly exposing one.
     */
    private void writeMcpConfig(AgentRun run, Path workdir) throws IOException {
        if (!strictMcp) return;

        Map<String, McpServerSpec> byName = new LinkedHashMap<>();
        for (AgentSpec agent : run.compiled.allAgents()) {
            for (McpServerSpec m : agent.mcpServers) {
                if (m.name != null && !m.name.isBlank()) byName.putIfAbsent(m.name.toLowerCase(), m);
            }
        }

        var servers = mapper.createObjectNode();
        for (McpServerSpec m : byName.values()) {
            var server = mapper.createObjectNode();
            server.put("type", "http");
            server.put("url", m.url);
            String token = m.resolveToken();
            String header = null;
            String value = null;
            if (token != null && !token.isBlank()) {
                header = m.authHeader == null || m.authHeader.isBlank()
                        ? McpRegistry.DEFAULT_AUTH_HEADER : m.authHeader.trim();
                value = McpRegistry.DEFAULT_AUTH_HEADER.equalsIgnoreCase(header)
                        ? "Bearer " + token : token;
            } else {
                String granted = mcpOAuthStore
                        .accessToken(orgContext.defaultOrganizationId(), m.url)
                        .orElse(null);
                if (granted != null) {
                    header = McpRegistry.DEFAULT_AUTH_HEADER;
                    value = "Bearer " + granted;
                }
            }
            if (header != null) {
                var headers = mapper.createObjectNode();
                headers.put(header, value);
                server.set("headers", headers);
            }
            servers.set(m.name, server);
        }

        var root = mapper.createObjectNode();
        root.set("mcpServers", servers);
        Files.writeString(workdir.resolve(MCP_CONFIG_FILE), mapper.writeValueAsString(root));

        run.emit(RunEvent.of("system", byName.isEmpty()
                ? "MCP: this flow has no MCP nodes, so the run sees no MCP servers."
                : "MCP: this run sees only the flow's server(s): "
                        + String.join(", ", byName.keySet()) + "."));
    }

    /** Inlines the agent's referenced CLAUDE.md, if it names one and it passes the allowlist. */
    private void appendReferencedClaudeMd(AgentRun run, AgentSpec spec, StringBuilder out) {
        Path file = contextFolders.resolveClaudeMd(spec.claudeMdPath,
                (path, reason) -> run.emit(RunEvent.of("system",
                        "CLAUDE.md ignored for " + spec.name + " — " + path + ": " + reason)));
        if (file == null) return;
        try {
            out.append(Files.readString(file)).append("\n\n");
            run.emit(RunEvent.of("system", "Loaded CLAUDE.md for " + spec.name + " from " + file));
        } catch (IOException e) {
            run.emit(RunEvent.of("system",
                    "CLAUDE.md could not be read for " + spec.name + ": " + e.getMessage()));
        }
    }

    /**
     * Tells an agent which agents it may hand work to.
     *
     * <p>Every agent in the flow is registered with the CLI, so technically any of them can call
     * any other. This is what makes a reviewer wired behind one engineer review <em>that</em>
     * engineer's work rather than acting as a general-purpose peer: the roster is scoped to the
     * edges drawn from this agent. Like the context-folder note, it steers rather than enforces.
     */
    private static void appendDelegationRoster(AgentSpec spec, StringBuilder out) {
        if (spec.delegatesTo == null || spec.delegatesTo.isEmpty()) return;
        out.append("\n## Agents you can delegate to\n\n");
        for (String name : spec.delegatesTo) {
            out.append("- `").append(name).append("`\n");
        }
        out.append("\nUse the Task tool with `subagent_type` set to one of these exact names, and"
                + " give it only the part of the work it needs. Other agents exist in this session"
                + " but are not yours to call — they belong to other parts of the flow.\n");
    }

    /**
     * Names the agent's folders in its own instructions. {@code --add-dir} grants the union to the
     * whole session, so this is what actually tells an agent which checkout is <em>its</em> one —
     * the guidance that stops a "WireJ" agent working in some other repo it can also see.
     */
    private static void appendContextFolderNote(AgentSpec spec, StringBuilder out) {
        if (spec.contextFolders == null || spec.contextFolders.isEmpty()) return;
        out.append("\n## Your context folders\n\n")
                .append("Use these paths as the source of truth for your work:\n");
        for (String f : spec.contextFolders) {
            out.append("- ").append(f).append('\n');
        }
        out.append("\nOther directories may be readable in this session but belong to other agents. "
                + "Do not assume a folder is yours because its name looks related — work only in "
                + "the paths listed above.\n");
    }

    /**
     * The folders every agent in this flow is allowed to read, de-duplicated.
     *
     * <p>Local mode runs a <b>single</b> CLI process — sub-agents are Claude Code subagents inside
     * that one session — so {@code --add-dir} is necessarily session-wide and cannot be scoped per
     * agent. The union is granted here and the per-agent split is written into each sub-agent's
     * definition as instruction text (see {@link #prepareWorkspace}), which steers the agent but
     * does not enforce isolation.
     */
    private List<Path> resolveContextDirs(AgentRun run, CompiledFlow flow, boolean report) {
        BiConsumer<String, String> onRejected = (path, reason) -> {
            if (report) run.emit(RunEvent.of("system", "Context folder ignored — " + path + ": " + reason));
        };
        List<Path> all = new ArrayList<>();
        for (AgentSpec spec : flow.allAgents()) {
            for (Path p : contextFolders.resolve(spec.contextFolders, onRejected)) {
                if (!all.contains(p)) all.add(p);
            }
        }
        if (report && !all.isEmpty()) {
            run.emit(RunEvent.of("system", "Context folders: "
                    + all.stream().map(Path::toString).collect(Collectors.joining(", "))));
        }
        return all;
    }

    /**
     * Feeds the prompt to the CLI's stdin, on its own thread.
     *
     * <p>A thread rather than a straight write, because a large prompt does not fit in the pipe
     * buffer: writing it inline would block here until the child drains it, while the child may
     * itself be blocked writing stdout that nobody is reading yet. Both sides wait, and the run
     * hangs with no output at all.
     *
     * <p>The stream is closed when the write finishes — that end-of-input is what tells the CLI
     * the prompt is complete.
     */
    private static void writePromptToStdin(Process proc, String userText) {
        Thread writer = new Thread(() -> {
            try (var out = proc.getOutputStream()) {
                out.write(userText.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException ignored) {
                // The child exited before reading it all; its own error surfaces on stdout.
            }
        }, "claude-stdin");
        writer.setDaemon(true);
        writer.start();
    }

    // Package-private for the arg-shape test: the ordering here is load-bearing and silent when
    // wrong — a misplaced `-p` would take the next flag as the prompt.
    List<String> buildArgs(String cmd, AgentRun run, Path workdir, boolean first, String userText,
                                   List<Path> contextDirs, boolean promptOnStdin) {
        AgentSpec coord = run.compiled.coordinator();
        List<String> a = new ArrayList<>();
        a.add(cmd);
        if (!promptOnStdin) {
            a.add("-p");
            a.add(userText);
        }
        for (Path dir : contextDirs) {
            a.add("--add-dir");
            a.add(dir.toString());
        }
        a.add("--output-format");
        a.add("stream-json");
        a.add("--verbose");
        a.add("--permission-mode");
        // The run's own mode when its flow named one, otherwise the deployment default. Read from
        // the run rather than the flow so that editing the flow mid-run cannot change what an
        // already-running agent is permitted to do.
        // Approval mode is ours, not the CLI's: it maps to `plan` until a human approves — so the
        // agent can read and propose but change nothing — and to full permission afterwards.
        String mode = run.permissionMode == null || run.permissionMode.isBlank()
                ? permissionMode : run.permissionMode;
        if (APPROVAL_MODE.equalsIgnoreCase(mode)) {
            mode = run.approved ? "bypassPermissions" : "plan";
        }
        a.add(mode);
        a.add("--model");
        a.add(modelAlias(coord.model.id));

        // The run's own MCP configuration (see writeMcpConfig): only the flow's servers, with
        // --strict-mcp-config so the user's personal MCP list stays theirs. Registration into the
        // user list still happens separately — it is what `claude mcp login` and the tool picker
        // need — but the run does not read it.
        if (strictMcp) {
            a.add("--mcp-config");
            a.add(workdir.resolve(MCP_CONFIG_FILE).toString());
            a.add("--strict-mcp-config");
        }
        if (first) {
            a.add("--session-id");
            a.add(run.localSessionId);
        } else {
            a.add("--resume");
            a.add(run.localSessionId);
        }
        // Last, and with no value: `-p` takes an optional argument, so putting it anywhere else
        // risks the following flag being read as the prompt. With nothing after it the CLI can
        // only read the prompt from stdin, which is the point.
        if (promptOnStdin) a.add("-p");
        return a;
    }

    /**
     * Registers each MCP node into the user's Claude Code MCP list (if missing), so the CLI
     * uses it with its own auth handling. Nodes with a token are added with a bearer header;
     * OAuth servers are added and the user is told to run {@code claude mcp login}.
     */
    private void registerMcpServers(AgentRun run) {
        List<McpServerSpec> mcps = new ArrayList<>();
        for (AgentSpec agent : run.compiled.allAgents()) {
            mcps.addAll(agent.mcpServers);
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
