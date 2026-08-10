package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.model.NodeExec;
import com.concentus.model.RunEvent;
import com.concentus.support.LocalClaudeSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs a flow's sub-agents as independent {@code claude} processes — one per agent — instead of
 * as Claude Code subagents inside the coordinator's session. Selected per flow by the
 * coordinator's {@code execution: "fanout"} setting; the default path is untouched.
 *
 * <p>What a process boundary buys over the single-session path, stated because it is the whole
 * reason this exists: each worker gets its <b>own</b> workspace, its own {@code CLAUDE.md} with
 * only its own instructions (the single session necessarily shares one), its own
 * {@code --add-dir} grants (the single session grants the union to everyone), its own model —
 * and true parallelism, where subagents in one session run one at a time.
 *
 * <p>What it deliberately does not do yet, said where the run can see it rather than silently:
 * MCP servers are not reachable from workers (each worker's MCP config is written empty and
 * strict — the per-worker facade with allowlist/read-only/dry-run comes next), and repository
 * nodes are not cloned into workers. The plan is fixed — one work item per drawn sub-agent, each
 * receiving the turn's text; a coordinator-authored dynamic plan comes later.
 *
 * <p>Workers cannot spawn workers: no {@code .claude/agents/} directory is written and the Task
 * tool is disallowed outright, so the fan-out is one level deep by construction.
 */
@Component
public class FanoutExecutor {

    /** {@code AgentSpec.execution} value that selects this executor. */
    public static final String EXECUTION_FANOUT = "fanout";

    /** Grace after a soft kill before the process is killed hard; workers get no cleanup ritual. */
    private static final long FORCE_KILL_AFTER_SECONDS = 5;

    /** Seam for tests: spawning is the one thing a unit test cannot do for real. */
    interface ProcessStarter {
        Process start(List<String> args, Path workdir) throws IOException;
    }

    private final LocalClaudeSupport support;
    private final RagContextInjector ragInjector;
    private final ContextFolderResolver contextFolders;
    private final ObjectMapper mapper;
    private final com.concentus.store.FacadeProfileStore profiles;
    private final String dataDir;
    private final String permissionMode;
    private final int serverPort;
    private final int timeoutSeconds;
    private final int retries;
    private final ProcessStarter starter;
    private final ExecutorService pool;
    private final ScheduledExecutorService watchdogs;

    @Autowired
    public FanoutExecutor(LocalClaudeSupport support, RagContextInjector ragInjector,
                          ContextFolderResolver contextFolders, ObjectMapper mapper,
                          com.concentus.store.FacadeProfileStore profiles,
                          @Value("${app.data-dir}") String dataDir,
                          @Value("${local.permission-mode:bypassPermissions}") String permissionMode,
                          @Value("${server.port:8734}") int serverPort,
                          @Value("${workers.max-concurrent:4}") int maxConcurrent,
                          @Value("${workers.timeout-seconds:900}") int timeoutSeconds,
                          @Value("${workers.retries:1}") int retries) {
        this(support, ragInjector, contextFolders, mapper, profiles, dataDir, permissionMode,
                serverPort, maxConcurrent, timeoutSeconds, retries, (args, workdir) ->
                        new ProcessBuilder(args).directory(workdir.toFile())
                                .redirectErrorStream(true).start());
    }

    FanoutExecutor(LocalClaudeSupport support, RagContextInjector ragInjector,
                   ContextFolderResolver contextFolders, ObjectMapper mapper,
                   com.concentus.store.FacadeProfileStore profiles,
                   String dataDir, String permissionMode, int serverPort, int maxConcurrent,
                   int timeoutSeconds, int retries, ProcessStarter starter) {
        this.support = support;
        this.ragInjector = ragInjector;
        this.contextFolders = contextFolders;
        this.mapper = mapper;
        this.profiles = profiles;
        this.dataDir = dataDir;
        this.permissionMode = permissionMode;
        this.serverPort = serverPort;
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.retries = Math.max(0, retries);
        this.starter = starter;
        AtomicInteger n = new AtomicInteger(1);
        // Its own pool, deliberately not RunService's: the coordinator turn already holds one of
        // that pool's threads while it waits here, so borrowing worker threads from the same pool
        // is how a couple of concurrent fan-outs deadlock the whole deployment.
        this.pool = Executors.newFixedThreadPool(Math.max(1, maxConcurrent), r -> {
            Thread t = new Thread(r, "fanout-worker-" + n.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
        this.watchdogs = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "fanout-watchdog");
            t.setDaemon(true);
            return t;
        });
    }

    /** One fan-out turn: every sub-agent runs the turn's text as its own process. Blocking. */
    public void runTurn(AgentRun run, CompiledFlow flow, String userText) {
        String cmd = support.command().orElse(null);
        if (cmd == null) {
            fail(run, "The claude CLI was not found. Install Claude Code or set local.claude-command.");
            return;
        }
        List<AgentSpec> workers = flow.subAgents();
        if (workers.isEmpty()) {
            fail(run, "Independent workers need at least one sub-agent wired to the coordinator — "
                    + "this flow has none. Draw the workers, or switch execution back to subagents.");
            return;
        }

        AgentSpec coord = flow.coordinator();
        NodeExec coordExec = run.nodeExec(coord.nodeId, "agent", coord.name);
        if (coordExec != null) {
            coordExec.appendInput(userText);
            coordExec.status = "running";
        }
        run.status = "RUNNING";
        run.emit(RunEvent.of("system", "› " + userText));
        run.emit(RunEvent.of("system", "Fan-out: " + workers.size() + " independent worker "
                + "process(es), up to " + timeoutSeconds + "s each. Each has its own workspace, "
                + "instructions and model; none can delegate further."));
        sayWhatIsMissing(run, flow);

        List<Future<Outcome>> futures = new ArrayList<>();
        for (AgentSpec spec : workers) {
            futures.add(pool.submit(() -> runWorker(run, spec, cmd, userText)));
        }

        List<Outcome> outcomes = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                outcomes.add(futures.get(i).get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                outcomes.add(new Outcome(workers.get(i), false, null, "interrupted while waiting"));
            } catch (Exception e) {
                outcomes.add(new Outcome(workers.get(i), false,
                        null, "worker thread failed: " + e.getMessage()));
            }
        }

        report(run, coordExec, userText, outcomes);
    }

    /** Kills every live worker of this run. The run's status is the caller's to set. */
    public void stopWorkers(AgentRun run) {
        for (Process p : run.workerProcesses.values()) {
            p.destroy();
        }
    }

    // ---------------------------------------------------------------- one worker

    private record Outcome(AgentSpec spec, boolean ok, String finalText, String error) {
    }

    private Outcome runWorker(AgentRun run, AgentSpec spec, String cmd, String userText) {
        NodeExec exec = run.nodeExec(spec.nodeId, "agent", spec.name);
        if (exec != null) {
            exec.appendInput(userText);
            exec.status = "running";
        }

        Path workdir = Path.of(dataDir, "local", run.id, "workers", workerFolder(spec))
                .toAbsolutePath().normalize();
        try {
            prepareWorkspace(run, spec, workdir);
        } catch (IOException e) {
            return finish(exec, new Outcome(spec, false, null,
                    "workspace could not be prepared: " + e.getMessage()));
        }

        List<Path> dirs = contextFolders.resolve(spec.contextFolders, (path, reason) ->
                run.emit(RunEvent.of("system",
                        "Context folder ignored — " + path + ": " + reason, spec.name, spec.nodeId)));

        int attempts = 1 + retries;
        String lastError = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if ("TERMINATED".equals(run.status)) {
                return finish(exec, new Outcome(spec, false, null, "run was stopped"));
            }
            if (attempt > 1) {
                run.emit(RunEvent.of("system", "Retrying (" + attempt + "/" + attempts + ") after: "
                        + lastError, spec.name, spec.nodeId));
            }
            Attempt result = attempt(run, spec, exec, cmd, userText, workdir, dirs);
            if (result.ok()) {
                return finish(exec, new Outcome(spec, true, result.finalText(), null));
            }
            lastError = result.error();
            if (result.timedOut() || "TERMINATED".equals(run.status)) {
                // A timeout retried is a doubled timeout: the next attempt would spend the same
                // budget on the same task. Fail it and let the report say so.
                break;
            }
        }
        return finish(exec, new Outcome(spec, false, null, lastError));
    }

    private record Attempt(boolean ok, boolean timedOut, String finalText, String error) {
    }

    private Attempt attempt(AgentRun run, AgentSpec spec, NodeExec exec, String cmd,
                            String userText, Path workdir, List<Path> dirs) {
        boolean promptOnStdin = userText.length() > LocalClaudeExecutor.MAX_INLINE_PROMPT_CHARS;
        List<String> args = buildWorkerArgs(cmd, run, spec, workdir, dirs,
                UUID.randomUUID().toString(), userText, promptOnStdin);

        Process proc;
        try {
            proc = starter.start(args, workdir);
        } catch (IOException e) {
            return new Attempt(false, false, null, "failed to start claude: " + e.getMessage());
        }
        run.workerProcesses.put(spec.nodeId, proc);
        if (promptOnStdin) {
            LocalClaudeExecutor.writePromptToStdin(proc, userText);
        } else {
            try {
                proc.getOutputStream().close();
            } catch (IOException ignored) {
                // best effort — the CLI simply sees end-of-input
            }
        }

        // The watchdog is what enforces the timeout: the reading thread is blocked on stdout and
        // cannot watch a clock. Destroying the process is what unblocks the reader (EOF).
        AtomicBoolean timedOut = new AtomicBoolean(false);
        ScheduledFuture<?> soft = watchdogs.schedule(() -> {
            timedOut.set(true);
            proc.destroy();
        }, timeoutSeconds, TimeUnit.SECONDS);
        ScheduledFuture<?> hard = watchdogs.schedule(() -> {
            if (proc.isAlive()) proc.destroyForcibly();
        }, timeoutSeconds + FORCE_KILL_AFTER_SECONDS, TimeUnit.SECONDS);

        String finalText = null;
        boolean resultError = false;
        String resultMessage = null;
        try (BufferedReader reader = proc.inputReader(StandardCharsets.UTF_8)) {
            // UTF-8 explicitly — the default decodes with the OS charset (Cp1252 on a Spanish
            // Windows) and silently turns every accent in the worker's output into mojibake.
            String line;
            while ((line = reader.readLine()) != null) {
                Parsed p = handleLine(run, spec, exec, line);
                if (p.text() != null) finalText = p.text();
                if (p.resultError() != null) {
                    resultError = p.resultError();
                    resultMessage = p.resultMessage();
                }
            }
            proc.waitFor();
        } catch (Exception e) {
            // The reader died before the process did — do not leave the child running unread.
            proc.destroy();
            return new Attempt(false, timedOut.get(), null, "worker stream ended: " + e.getMessage());
        } finally {
            soft.cancel(false);
            hard.cancel(false);
            run.workerProcesses.remove(spec.nodeId, proc);
        }

        if (timedOut.get()) {
            return new Attempt(false, true, null,
                    "timed out after " + timeoutSeconds + "s and was stopped");
        }
        int exit = proc.exitValue();
        if (exit != 0) {
            return new Attempt(false, false, null, "claude exited with code " + exit
                    + (resultMessage == null ? "" : ": " + resultMessage));
        }
        if (resultError) {
            return new Attempt(false, false, null,
                    resultMessage == null ? "the worker reported an error" : resultMessage);
        }
        return new Attempt(true, false, finalText, null);
    }

    private Outcome finish(NodeExec exec, Outcome outcome) {
        if (exec != null) {
            exec.status = outcome.ok() ? "passed" : "failed";
            if (!outcome.ok()) exec.error = outcome.error();
            exec.endedAt = System.currentTimeMillis();
        }
        return outcome;
    }

    // ---------------------------------------------------------------- workspace

    /**
     * The worker's workspace: its own {@code CLAUDE.md} carrying <b>only this agent's</b>
     * instructions, and an empty-but-strict MCP config.
     *
     * <p>The empty config is written on purpose, not omitted: an absent file plus
     * {@code --strict-mcp-config} still means "no servers", but an absent file <em>without</em>
     * the flag would mean the user's whole personal MCP list — the exact exposure the per-run
     * config exists to close. Empty-and-strict states the intent in both places.
     */
    private void prepareWorkspace(AgentRun run, AgentSpec spec, Path workdir) throws IOException {
        Files.createDirectories(workdir);
        if (!run.workersPrepared.add(spec.nodeId)) return;

        ragInjector.inject(spec, run, m -> run.emit(RunEvent.of("system", m)));
        boolean facade = resolveFacade(run, spec);

        StringBuilder md = new StringBuilder();
        md.append("You are ").append(spec.name).append(", one of several independent workers in a "
                + "larger flow. Do only the task you are given, in your own workspace, and end "
                + "with a plain report of what you found or produced — another step merges the "
                + "workers' reports, so write yours to be read next to the others.\n");
        if (facade) {
            md.append("""

                    Your MCP tools go through a controlled facade. Some write actions may be
                    blocked or simulated (the result will say "DRY RUN"). A simulated action did
                    NOT happen: report it as a proposed action for someone with write permission
                    to confirm — never claim it was done.
                    """);
        }
        if (spec.systemPrompt != null && !spec.systemPrompt.isBlank()) {
            md.append('\n').append(spec.systemPrompt).append('\n');
        }
        LocalClaudeExecutor.appendContextFolderNote(spec, md);
        Files.writeString(workdir.resolve("CLAUDE.md"), md.toString());

        // With a facade: exactly one server — this backend, scoped to this worker, authenticated
        // by a token only this worker's process is given. Without one: empty and strict, which
        // states "no servers" in both places rather than leaving one to be inferred.
        var root = mapper.createObjectNode();
        var servers = root.putObject("mcpServers");
        if (facade) {
            var server = servers.putObject("concentus-facade");
            server.put("type", "http");
            server.put("url", "http://127.0.0.1:" + serverPort + "/api/runs/" + run.id
                    + "/workers/" + spec.nodeId + "/tools");
            server.putObject("headers").put(
                    com.concentus.web.RunToolsController.TOKEN_HEADER,
                    run.workerToolTokens.get(spec.nodeId));
        }
        Files.writeString(workdir.resolve(LocalClaudeExecutor.MCP_CONFIG_FILE),
                mapper.writeValueAsString(root));
    }

    /**
     * Resolves and freezes this worker's facade profile, minting its endpoint token. Returns
     * whether the worker gets a facade at all.
     *
     * <p>MCP servers wired but no profile (or a profile that no longer exists) means <b>no MCP
     * tools</b>, said out loud: "never the full tool set" is the rule for workers, and a missing
     * profile must fail closed rather than quietly expose everything the flow has.
     */
    private boolean resolveFacade(AgentRun run, AgentSpec spec) {
        if (spec.mcpServers.isEmpty()) return false;
        var profile = spec.facadeProfileId == null || spec.facadeProfileId.isBlank()
                ? java.util.Optional.<com.concentus.model.FacadeProfile>empty()
                : profiles.get(spec.facadeProfileId);
        if (profile.isEmpty()) {
            run.emit(RunEvent.of("system", "Worker '" + spec.name + "' has MCP server(s) wired "
                    + "but no facade profile selected" + (spec.facadeProfileId == null
                            || spec.facadeProfileId.isBlank() ? "" : " (the selected one no longer exists)")
                    + " — it gets NO MCP tools this run. Pick a profile on the agent node "
                    + "(Resources → Facades defines them).", spec.name, spec.nodeId));
            return false;
        }
        run.workerFacadeProfiles.put(spec.nodeId, profile.get());
        run.workerToolTokens.put(spec.nodeId, UUID.randomUUID().toString());
        run.emit(RunEvent.of("system", "Worker '" + spec.name + "' runs behind facade profile '"
                + profile.get().name() + "'" + (profile.get().readOnly() ? " (read-only)" : "")
                + (profile.get().dryRunEnabled() && !profile.get().readOnly()
                        ? " (writes are dry-run)" : "") + ".", spec.name, spec.nodeId));
        return true;
    }

    /** Folder-safe, unique per agent: the compiler already made {@code cliName} both. */
    private static String workerFolder(AgentSpec spec) {
        return spec.cliName != null ? spec.cliName : LocalClaudeExecutor.sanitize(spec.name);
    }

    // Package-private for the arg-shape test, like LocalClaudeExecutor.buildArgs: the ordering is
    // load-bearing and silent when wrong.
    List<String> buildWorkerArgs(String cmd, AgentRun run, AgentSpec spec, Path workdir,
                                 List<Path> contextDirs, String sessionId, String userText,
                                 boolean promptOnStdin) {
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
        // Same mapping as the coordinator process, so a worker can never be more permissive than
        // the flow's own mode — an approval flow's workers plan, they do not act.
        a.add(LocalClaudeExecutor.effectivePermissionMode(run, permissionMode));
        a.add("--model");
        a.add(LocalClaudeExecutor.modelAlias(spec.model.id));
        a.add("--mcp-config");
        a.add(workdir.resolve(LocalClaudeExecutor.MCP_CONFIG_FILE).toString());
        a.add("--strict-mcp-config");
        // One level deep by construction: a worker that could open its own Task fan-out would turn
        // a bounded N processes into an unbounded tree.
        a.add("--disallowedTools");
        a.add("Task");
        a.add("--session-id");
        a.add(sessionId);
        // Last and bare, exactly like the coordinator path: with nothing after it the CLI can
        // only read the prompt from stdin.
        if (promptOnStdin) a.add("-p");
        return a;
    }

    // ---------------------------------------------------------------- stream parsing

    private record Parsed(String text, Boolean resultError, String resultMessage) {
        static final Parsed NONE = new Parsed(null, null, null);
    }

    /**
     * One stream-json line from one worker. Far simpler than the coordinator's handler on
     * purpose: a worker is a single agent, so there is no Task attribution to untangle — text and
     * tokens belong to its own node, always.
     */
    private Parsed handleLine(AgentRun run, AgentSpec spec, NodeExec exec, String line) {
        String t = line.trim();
        if (t.isEmpty()) return Parsed.NONE;
        JsonNode node;
        try {
            node = mapper.readTree(t);
        } catch (Exception e) {
            run.emit(RunEvent.of("system", t, spec.name, spec.nodeId));
            return Parsed.NONE;
        }
        switch (node.path("type").asText("")) {
            case "assistant" -> {
                JsonNode usage = node.path("message").path("usage");
                if (exec != null && usage.isObject()) {
                    exec.inputTokens += usage.path("input_tokens").asLong(0);
                    exec.outputTokens += usage.path("output_tokens").asLong(0);
                    exec.cacheReadTokens += usage.path("cache_read_input_tokens").asLong(0);
                    exec.cacheWriteTokens += usage.path("cache_creation_input_tokens").asLong(0);
                }
                String text = null;
                JsonNode content = node.path("message").path("content");
                if (content.isArray()) {
                    for (JsonNode b : content) {
                        if ("text".equals(b.path("type").asText(""))) {
                            String s = b.path("text").asText("");
                            if (!s.isBlank()) {
                                if (exec != null) exec.appendOutput(s);
                                run.emit(RunEvent.of("agent_message", s, spec.name, spec.nodeId));
                                text = s;
                            }
                        } else if ("tool_use".equals(b.path("type").asText(""))) {
                            run.emit(RunEvent.of("tool_use", b.path("name").asText("tool"),
                                    spec.name, spec.nodeId));
                        }
                    }
                }
                return new Parsed(text, null, null);
            }
            case "result" -> {
                JsonNode usage = node.path("usage");
                if (usage.isObject()) {
                    run.totalInputTokens += usage.path("input_tokens").asLong(0);
                    run.totalOutputTokens += usage.path("output_tokens").asLong(0);
                    run.cacheReadTokens += usage.path("cache_read_input_tokens").asLong(0);
                    run.cacheWriteTokens += usage.path("cache_creation_input_tokens").asLong(0);
                }
                boolean bad = node.path("is_error").asBoolean(false);
                String text = node.path("result").asText("");
                return new Parsed(text.isBlank() ? null : text, bad, text.isBlank() ? null : text);
            }
            default -> {
                return Parsed.NONE;
            }
        }
    }

    // ---------------------------------------------------------------- reporting

    /**
     * The combined report, until the merge node exists: what each worker concluded, failures
     * included by name. Attributed to the coordinator node — it is the one box every flow has,
     * and the report is the answer to the instruction that box received.
     */
    private void report(AgentRun run, NodeExec coordExec, String userText, List<Outcome> outcomes) {
        long failed = outcomes.stream().filter(o -> !o.ok()).count();

        StringBuilder md = new StringBuilder("## Independent workers — combined report\n");
        for (Outcome o : outcomes) {
            md.append("\n### ").append(o.spec().name)
                    .append(o.ok() ? "" : " — FAILED").append('\n');
            if (o.ok()) {
                md.append(o.finalText() == null || o.finalText().isBlank()
                        ? "_(finished without a final message)_" : o.finalText()).append('\n');
            } else {
                md.append("_").append(o.error()).append("_\n");
            }
        }
        if (failed > 0) {
            md.append("\n").append(failed).append(" of ").append(outcomes.size())
                    .append(" worker(s) failed — their sections above say why. The rest is real.\n");
        }

        String report = md.toString();
        AgentSpec coord = run.compiled.coordinator();
        if (coordExec != null) {
            coordExec.appendOutput(report);
            coordExec.status = failed == outcomes.size() ? "failed" : "passed";
            coordExec.endedAt = System.currentTimeMillis();
        }
        run.emit(RunEvent.of("agent_message", report, coord.name, coord.nodeId));

        // A stopped run keeps saying it was stopped. Its workers were killed and every outcome
        // reads "failed", but reporting that as ERROR would page someone about a run a human
        // ended on purpose.
        if ("TERMINATED".equals(run.status)) return;

        if (failed == outcomes.size()) {
            fail(run, "Every worker failed. The combined report lists each reason.");
        } else {
            boolean waiting = LocalClaudeExecutor.awaitingApproval(run);
            run.status = waiting ? "AWAITING_APPROVAL" : "IDLE";
            if (waiting) {
                run.emit(RunEvent.of("system",
                        "Waiting for your approval — nothing has been changed yet."));
            }
        }
    }

    /**
     * What this executor knowingly leaves out, said at the start of every turn. A canvas showing
     * an MCP node wired to a worker that quietly cannot reach it is the failure mode this
     * codebase keeps paying for — the honest line is cheaper.
     */
    private void sayWhatIsMissing(AgentRun run, CompiledFlow flow) {
        if (!flow.allRepos().isEmpty()) {
            run.emit(RunEvent.of("system", "Fan-out note: repository nodes are not cloned into "
                    + "workers yet. A flow that must push code still belongs on subagents "
                    + "execution for now."));
        }
    }

    private static void fail(AgentRun run, String message) {
        run.status = "ERROR";
        run.error = message;
        run.emit(RunEvent.of("error", message));
    }
}
