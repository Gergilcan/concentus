package com.concentus.service;

import com.concentus.auth.OrgContext;
import com.concentus.config.AgentSpec;
import com.concentus.config.AgentSpec.McpServerSpec;
import com.concentus.llm.McpOAuthStore;
import com.concentus.model.NodeExec;
import com.concentus.git.GitWorkspace;
import com.concentus.model.RunEvent;
import com.concentus.model.SkillDef;
import com.concentus.store.SkillStore;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
    /** Flows wired INTO an agent: they run before it and their answers become its context. */
    private final PreRunSubflows preRunSubflows;
    private final McpRegistry mcpRegistry;
    private final PluginRegistry pluginRegistry;
    /** The machine-wide cap on claude processes, shared with {@link FanoutExecutor}. */
    private final ProcessCeiling ceiling;
    private final LocalStreamEventHandler streamHandler;
    private final ContextFolderResolver contextFolders;
    private final GitWorkspace gitWorkspace;
    private final String permissionMode;
    private final String dataDir;
    private final boolean autoRegisterMcp;
    private final ObjectMapper mapper;
    private final McpOAuthStore mcpOAuthStore;
    /** Decides, per server, whether the CLI talks to it directly or through this backend. */
    private final CliMcpServers cliMcpServers;
    private final SkillStore skillStore;
    private final SkillService skillService;
    private final OrgContext orgContext;
    /** See {@link #writeMcpConfig}: runs see only the flow's MCP servers, not the user's list. */
    private final boolean strictMcp;
    private final int serverPort;
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

    /**
     * The resting state after a turn that ended without error: idle, or held for a human under
     * approval mode. Not IDLE for the latter: idle means "waiting for whatever you want next", and
     * an approval run is waiting for one specific answer — the distinct status is what lets the UI
     * offer Approve/Reject and the desktop shell raise a notification. Shared with the fan-out
     * executor so both paths park a run the same way.
     */
    static void settleIdle(AgentRun run) {
        boolean waiting = awaitingApproval(run);
        run.status = waiting ? "AWAITING_APPROVAL" : "IDLE";
        if (waiting) {
            run.emit(RunEvent.of("system",
                    "Waiting for your approval — nothing has been changed yet."));
        }
    }

    /** The per-run MCP config file, inside the run's workdir. */
    static final String MCP_CONFIG_FILE = "mcp-config.json";

    /** Where the run's {@code --settings} document is written. See {@link #writeSettingsFile}. */
    static final String SETTINGS_FILE = "settings.json";

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
    static final int MAX_INLINE_PROMPT_CHARS = 4000;

    /**
     * The CLI's own name for the tool that invokes a skill. Taken away from a run that assigned
     * none, which is the only way to keep the machine's personal skills out of a flow that did
     * not ask for any — they are discovered from the user's home, not from the workspace.
     */
    static final String SKILL_TOOL = "Skill";

    public LocalClaudeExecutor(LocalClaudeSupport support, RagContextInjector ragInjector,
                               PreRunSubflows preRunSubflows,
                               McpRegistry mcpRegistry, ContextFolderResolver contextFolders,
                               GitWorkspace gitWorkspace,
                               ObjectMapper mapper,
                               McpOAuthStore mcpOAuthStore,
                               CliMcpServers cliMcpServers,
                               SkillStore skillStore,
                               SkillService skillService,
                               OrgContext orgContext,
                               PluginRegistry pluginRegistry,
                               ProcessCeiling ceiling,
                               @Value("${local.permission-mode:bypassPermissions}") String permissionMode,
                               @Value("${app.data-dir}") String dataDir,
                               @Value("${local.auto-register-mcp:true}") boolean autoRegisterMcp,
                               @Value("${local.strict-mcp:true}") boolean strictMcp,
                               @Value("${server.port:8734}") int serverPort) {
        this.support = support;
        this.ragInjector = ragInjector;
        this.preRunSubflows = preRunSubflows;
        this.mcpRegistry = mcpRegistry;
        this.contextFolders = contextFolders;
        this.gitWorkspace = gitWorkspace;
        this.streamHandler = new LocalStreamEventHandler(mapper);
        this.mapper = mapper;
        this.mcpOAuthStore = mcpOAuthStore;
        this.cliMcpServers = cliMcpServers;
        this.skillStore = skillStore;
        this.skillService = skillService;
        this.orgContext = orgContext;
        this.pluginRegistry = pluginRegistry;
        this.ceiling = ceiling;
        this.permissionMode = permissionMode;
        this.dataDir = dataDir;
        this.autoRegisterMcp = autoRegisterMcp;
        this.strictMcp = strictMcp;
        this.serverPort = serverPort;
    }

    /** Runs one turn and streams events into the run. Blocking — call on a worker thread. */
    public void runTurn(AgentRun run, CompiledFlow flow, String userText) {
        String cmd = support.command().orElse(null);
        if (cmd == null) {
            run.fail("The claude CLI was not found. Install Claude Code or set local.claude-command.");
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
            run.fail("Failed to prepare local workspace: " + e.getMessage());
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
        List<String> args = buildArgs(cmd, run, workdir, first, userText, contextDirs, promptOnStdin,
                writePluginSettings(run, workdir));
        run.status = "RUNNING";
        run.emit(RunEvent.of("system", "› " + userText));

        ProcessBuilder pb = new ProcessBuilder(args).directory(workdir.toFile());
        pb.redirectErrorStream(true);
        // Push credentials for the flow's repositories. On the process environment rather than in
        // any .git/config, so the token is not written to disk and does not appear in
        // `git remote -v` or in anything the agent might paste into a commit or a PR body.
        pb.environment().putAll(GitWorkspace.environmentFor(run.checkouts));

        // The machine-wide ceiling on claude processes, shared with every fan-out's workers.
        // Acquired before the process exists, held until it has exited (the finally below).
        ProcessCeiling.Slot slot;
        try {
            slot = ceiling.acquire(() -> "TERMINATED".equals(run.status),
                    msg -> run.emit(RunEvent.of("system", msg)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            run.fail("Interrupted while waiting for a process slot.");
            return;
        }
        if (slot == null) {
            // The run was stopped while it waited; there is nothing to start.
            return;
        }

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            slot.close();
            run.fail("Failed to start claude: " + e.getMessage());
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
            slot.close();
            run.localProcess = null;
            if (!"TERMINATED".equals(run.status) && !"ERROR".equals(run.status)) {
                settleIdle(run);
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
    static void appendRepositoryNote(List<GitWorkspace.Checkout> checkouts, StringBuilder md, boolean mayPush) {
        List<GitWorkspace.Checkout> ok = checkouts.stream().filter(GitWorkspace.Checkout::ok).toList();
        List<GitWorkspace.Checkout> failed = checkouts.stream().filter(c -> !c.ok()).toList();
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
        if (!mayPush) {
            // A worker: it can read and change the files, and that is all. Its changes leave as
            // a patch the merge step applies — said here so it neither tries git nor claims a
            // push it could not have made.
            md.append("""

                They are yours to read and change. You cannot run git here: when you finish, the
                changes you made inside these folders are collected as a patch and handed to the
                merge step, which applies them, runs the checks and opens the pull request. Edit
                the files themselves; do not describe changes you did not make, and do not claim
                anything was committed or pushed.
                """);
            return;
        }
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
            // Registered for review under the coordinator: one session works every clone, and
            // the commit it starts from is what the diff is measured against later — the
            // coordinator has a shell and may well commit before anyone looks.
            if (c.ok()) {
                run.recordPatch(com.concentus.model.RunPatch.registered(
                        flow.coordinator().nodeId, flow.coordinator().name, c.folderName(),
                        c.spec().url, c.directory(), gitWorkspace.headOf(c.directory())));
            }
        }

        // Inject SQL/RAG context into each agent's prompt (once); record per-node for the UI.
        for (AgentSpec agent : flow.allAgents()) {
            ragInjector.inject(agent, run, m -> run.emit(RunEvent.of("system", m)));
        }

        // Flows wired into an agent run here, before its first turn, exactly as an input should.
        // After the RAG injection because both append to the same briefing and this is the more
        // expensive one: a failure in it should not cost the cheap context that already succeeded.
        for (AgentSpec agent : flow.allAgents()) {
            preRunSubflows.inject(agent, run, m -> run.emit(RunEvent.of("system", m)));
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
        appendRepositoryNote(run.checkouts, claudeMd, true);
        appendMemoryNote(run, claudeMd);
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
                appendSkillRoster(sub, body);
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
        materialiseSkills(run, flow, workdir);

        // Said rather than silently skipped: the canvas shows the node, so the run must say why
        // nothing ever lands on it here.
        if (flow.merger() != null) {
            run.emit(RunEvent.of("system", "This flow has a merge node, which only runs under "
                    + "independent-workers execution — on subagents execution it is ignored."));
        }
    }

    /**
     * Skills assigned to any agent, written once into the workspace's {@code .claude/skills/}.
     *
     * <p>Flow-level because Claude Code discovers skills per project, not per subagent; the
     * per-agent part is the roster note appended to each agent's instructions. Steering, not
     * enforcement — the same honest deal as context folders.
     */
    private void materialiseSkills(AgentRun run, CompiledFlow flow, Path workdir) {
        Set<String> ids = new LinkedHashSet<>();
        for (AgentSpec agent : flow.allAgents()) {
            for (AgentSpec.SkillSpec skill : agent.skills) {
                if ("custom".equals(skill.type) && skill.id != null) ids.add(skill.id);
            }
        }
        if (ids.isEmpty()) return;
        List<SkillDef> defs = ids.stream()
                .map(skillStore::get)
                .flatMap(Optional::stream)
                .toList();
        try {
            skillService.materialise(workdir, defs);
            run.emit(RunEvent.of("system", defs.size() + " skill(s) installed for this run: "
                    + defs.stream().map(SkillDef::name)
                            .collect(Collectors.joining(", ")) + "."));
        } catch (IOException e) {
            run.emit(RunEvent.of("system", "Skills could not be installed: " + e.getMessage()));
        }
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

        // Minted before the servers are written, not after: a server whose authorization is an
        // OAuth grant is pointed at this backend's proxy, and that route is what the token opens.
        if (run.toolToken == null) run.toolToken = UUID.randomUUID().toString();

        var servers = mapper.createObjectNode();
        for (McpServerSpec m : byName.values()) {
            servers.set(m.name, cliMcpServers.node(m, run.organizationId, run.id, run.toolToken));
        }

        // API nodes become tools served by this very backend, per run. The CLI reaches them like
        // any other MCP server; the per-run token in the header is what scopes it. The same
        // endpoint also serves the flow's memory tools, which every saved flow gets — so the
        // server entry is written whenever either is present, not only for API nodes.
        List<AgentSpec.ApiSourceSpec> apis = new ArrayList<>();
        for (AgentSpec agent : run.compiled.allAgents()) apis.addAll(agent.apiSources);
        boolean hasMemory = run.flowId != null && !run.flowId.isBlank();
        if (!apis.isEmpty() || hasMemory) {
            var server = mapper.createObjectNode();
            server.put("type", "http");
            server.put("url", "http://127.0.0.1:" + serverPort + "/api/runs/" + run.id + "/tools");
            server.putObject("headers")
                    .put(com.concentus.web.RunToolsController.TOKEN_HEADER, run.toolToken);
            servers.set("concentus-apis", server);
            for (AgentSpec.ApiSourceSpec api : apis) {
                NodeExec ne = run.nodeExec(api.nodeId, "api", api.label);
                if (ne != null) {
                    ne.input = api.isEndpoint()
                            ? api.method + " " + api.url
                            : (api.specUrl.isBlank() ? "(pasted spec)" : api.specUrl);
                    ne.status = "passed";
                    ne.output = api.isEndpoint()
                            ? "1 endpoint exposed"
                            : api.ops.size() + " operation(s) exposed";
                    ne.endedAt = System.currentTimeMillis();
                }
            }
            if (!apis.isEmpty()) {
                run.emit(RunEvent.of("system", "API tools: " + apis.size() + " node(s), "
                        + apis.stream().mapToInt(a -> a.ops.size()).sum() + " operation(s) allowed."));
            }
            if (hasMemory) {
                run.emit(RunEvent.of("system",
                        "Memory: this flow keeps notes across runs (memory_read / memory_append)."));
            }
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
     * Tells the agent its flow has a memory, and what it is for.
     *
     * <p>Written into the instructions rather than left to tool discovery: a tool list says a
     * memory <em>exists</em>, but not that reading it first is the expected opening move — without
     * this note agents reliably redo whatever the previous run already learned.
     */
    private static void appendMemoryNote(AgentRun run, StringBuilder md) {
        if (run.flowId == null || run.flowId.isBlank()) return;
        md.append("""

            ## Flow memory

            This flow keeps a persistent memory: short notes that survive between runs, shared by
            every execution of this flow. Read it with the `memory_read` tool before starting
            work — a previous run may have left you something. When you learn something a future
            run should know (a decision taken, state reached, an approach that failed), save it
            with `memory_append`. Keep notes short and factual; they are shared notes for your
            future self, not a transcript of this conversation.
            """);
    }

    /** Names the skills assigned to this agent, so it reaches for them instead of improvising. */
    private void appendSkillRoster(AgentSpec spec, StringBuilder out) {
        List<String> names = spec.skills.stream()
                .filter(sk -> "custom".equals(sk.type) && sk.id != null)
                .map(sk -> skillStore.get(sk.id).map(SkillDef::name).orElse(null))
                .filter(Objects::nonNull)
                .toList();
        if (names.isEmpty()) return;
        out.append("\n## Skills assigned to you\n\n");
        for (String n : names) out.append("- ").append(n).append('\n');
        out.append("\nUse the Skill tool with these when the task matches; they are installed in "
                + "this workspace.\n");
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
    static void appendContextFolderNote(AgentSpec spec, StringBuilder out) {
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
    static void writePromptToStdin(Process proc, String userText) {
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
                                   List<Path> contextDirs, boolean promptOnStdin, Path settingsFile) {
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
        a.add(effectivePermissionMode(run, permissionMode));
        a.add("--model");
        a.add(modelAlias(coord.model.id));

        // Plugins are session-wide: the coordinator and its Task sub-agents share one CLI
        // process, so the session loads the union of every agent's selection — and nothing else,
        // including when that union is empty. Passed as a FILE, never as inline JSON: see
        // writePluginSettings.
        if (settingsFile != null) {
            a.add("--settings");
            a.add(settingsFile.toString());
        }

        // Skills are the same deal: what the flow assigned, or nothing. Assigned ones are written
        // into the workspace (see materialiseSkills), but the CLI also finds the machine's own
        // personal skills, so "none assigned" only means "none" if the tool that reaches them is
        // gone. Session-wide again, so it takes the union: one agent with a skill keeps the tool
        // for everyone, which is the price of a shared process.
        if (noSkillsAnywhere(run)) {
            a.add("--disallowedTools");
            a.add(SKILL_TOOL);
        }

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
     * Whether this flow assigned no skill to any agent.
     *
     * <p>Only then is the Skill tool taken away — the enforcement behind "nothing selected means
     * nothing used". With a skill assigned the tool has to exist, and the machine's own personal
     * skills come with it: they live in the user's home, which the run shares. The workspace copy
     * and the roster note steer towards the assigned ones; that part is guidance, not a fence.
     */
    static boolean noSkillsAnywhere(AgentRun run) {
        if (run.compiled == null) return true;
        for (AgentSpec agent : run.compiled.allAgents()) {
            if (agent.skills != null && !agent.skills.isEmpty()) return false;
        }
        return true;
    }

    /**
     * The union of every agent's plugin selection, written into the run's workdir as the file
     * {@code --settings} will be pointed at. Null when there are no plugins installed to speak
     * about — nothing to say, no flag.
     *
     * <p>A file, not the JSON itself. {@code --settings} accepts either, and the string form is
     * what the CLI documents first, but a JSON argument does not survive the trip through
     * ProcessBuilder on Windows: the quotes are lost and the CLI answers "Invalid JSON provided to
     * --settings" and exits 1 before doing anything. A path has no quoting to lose.
     *
     * <p>An empty union still produces a file: it disables every installed plugin, because a flow
     * that ticked nothing asked for nothing. Union because the coordinator and its sub-agents
     * share one CLI process; only fan-out workers (separate processes) get truly per-agent sets.
     */
    private Path writePluginSettings(AgentRun run, Path workdir) {
        if (pluginRegistry == null || run.compiled == null) return null;
        LinkedHashSet<String> union = new LinkedHashSet<>(run.compiled.coordinator().plugins);
        for (AgentSpec s : run.compiled.subAgents()) union.addAll(s.plugins);
        return writeSettingsFile(run, workdir, pluginRegistry.settingsJsonFor(union));
    }

    /**
     * Writes a settings document into a run directory and returns its path, or null when there is
     * nothing to write. A failure is reported and treated as "no settings" rather than killing the
     * turn — the run is still worth having without the plugin fence.
     */
    static Path writeSettingsFile(AgentRun run, Path workdir, String json) {
        if (json == null) return null;
        Path file = workdir.resolve(SETTINGS_FILE);
        try {
            Files.createDirectories(workdir);
            Files.writeString(file, json);
            return file;
        } catch (IOException e) {
            run.emit(RunEvent.of("system",
                    "Plugin settings could not be written, so this run uses the CLI's own: "
                            + e.getMessage()));
            return null;
        }
    }

    /**
     * The CLI mode a run's turn actually gets. The run's own mode when its flow named one,
     * otherwise the deployment default — read from the run rather than the flow so that editing
     * the flow mid-run cannot change what an already-running agent is permitted to do.
     *
     * <p>Approval mode is ours, not the CLI's: it maps to {@code plan} until a human approves —
     * so the agent can read and propose but change nothing — and to full permission afterwards.
     * Shared with the fan-out executor so an independent worker can never be more permissive
     * than the coordinator process would have been.
     */
    static String effectivePermissionMode(AgentRun run, String deploymentDefault) {
        String mode = run.permissionMode == null || run.permissionMode.isBlank()
                ? deploymentDefault : run.permissionMode;
        if (APPROVAL_MODE.equalsIgnoreCase(mode)) {
            mode = run.approved ? "bypassPermissions" : "plan";
        }
        return clampToCeiling(run, mode);
    }

    /**
     * The organization's ceiling, applied last — after the flow's choice, the deployment default
     * and the approval mapping have all had their say — so nothing a flow, a setting or an
     * approval can do reaches above it. Said once per run rather than on every turn: the mode
     * does not change between turns, and a line per turn would be noise about a fact already told.
     */
    private static String clampToCeiling(AgentRun run, String mode) {
        String ceiling = run.maxPermissionMode;
        if (!com.concentus.policy.PermissionCeiling.above(mode, ceiling)) return mode;
        if (!run.permissionClampNoted) {
            run.permissionClampNoted = true;
            run.emit(RunEvent.of("system", "Permission mode '" + mode + "' is above the organization's "
                    + "ceiling '" + ceiling.trim() + "', so this run gets '" + ceiling.trim()
                    + "' (organization policy)."));
        }
        return ceiling.trim();
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
            if (ne != null) ne.input = m.isStdio() ? m.command : m.url;
            String key = m.name.toLowerCase();
            if (!handled.add(key)) continue;
            if (m.isStdio()) {
                // Nothing to register: registration exists for OAuth sign-ins and the designer's
                // tool picker, and a stdio server has neither — the run's own config launches it.
                markMcpResult(ne, "stdio — launched per run");
                continue;
            }
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
