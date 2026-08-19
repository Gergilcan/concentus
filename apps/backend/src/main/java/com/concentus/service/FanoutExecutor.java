package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.model.NodeExec;
import com.concentus.model.RunEvent;
import com.concentus.model.WorkPlan;
import com.concentus.model.WorkVerdict;
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
import java.util.stream.Collectors;

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
    /** Flows wired INTO an agent, run before it — the same rule the shared-session path follows. */
    private final PreRunSubflows preRunSubflows;
    private final ContextFolderResolver contextFolders;
    private final ObjectMapper mapper;
    private final com.concentus.store.FacadeProfileStore profiles;
    private final PluginRegistry pluginRegistry;
    /** Null in the arg-shape tests, which never build a workspace. */
    private final com.concentus.store.SkillStore skillStore;
    private final SkillService skillService;
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
                          PreRunSubflows preRunSubflows,
                          ContextFolderResolver contextFolders, ObjectMapper mapper,
                          com.concentus.store.FacadeProfileStore profiles,
                          PluginRegistry pluginRegistry,
                          com.concentus.store.SkillStore skillStore, SkillService skillService,
                          @Value("${app.data-dir}") String dataDir,
                          @Value("${local.permission-mode:bypassPermissions}") String permissionMode,
                          @Value("${server.port:8734}") int serverPort,
                          @Value("${workers.max-concurrent:4}") int maxConcurrent,
                          @Value("${workers.timeout-seconds:900}") int timeoutSeconds,
                          @Value("${workers.retries:1}") int retries) {
        this(support, ragInjector, preRunSubflows, contextFolders, mapper, profiles, pluginRegistry,
                skillStore, skillService, dataDir,
                permissionMode, serverPort, maxConcurrent, timeoutSeconds, retries, (args, workdir) ->
                        new ProcessBuilder(args).directory(workdir.toFile())
                                .redirectErrorStream(true).start());
    }

    FanoutExecutor(LocalClaudeSupport support, RagContextInjector ragInjector,
                   PreRunSubflows preRunSubflows,
                   ContextFolderResolver contextFolders, ObjectMapper mapper,
                   com.concentus.store.FacadeProfileStore profiles,
                   PluginRegistry pluginRegistry,
                   com.concentus.store.SkillStore skillStore, SkillService skillService,
                   String dataDir, String permissionMode, int serverPort, int maxConcurrent,
                   int timeoutSeconds, int retries, ProcessStarter starter) {
        this.support = support;
        this.ragInjector = ragInjector;
        this.preRunSubflows = preRunSubflows;
        this.contextFolders = contextFolders;
        this.mapper = mapper;
        this.profiles = profiles;
        this.pluginRegistry = pluginRegistry;
        this.skillStore = skillStore;
        this.skillService = skillService;
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
        AgentSpec coord = flow.coordinator();
        NodeExec coordExec = run.nodeExec(coord.nodeId, "agent", coord.name);
        if (coordExec != null) {
            coordExec.appendInput(userText);
            coordExec.status = "running";
        }
        run.status = "RUNNING";
        run.emit(RunEvent.of("system", "› " + userText));

        // The drawn sub-agents ARE the plan when there are any: each runs the turn's text. With
        // none, the coordinator runs first as a read-only planning process, and each plan item
        // becomes a worker running ITS OWN prompt — the item is the whole instruction.
        List<WorkerJob> jobs = new ArrayList<>();
        for (AgentSpec spec : flow.subAgents()) {
            jobs.add(new WorkerJob(spec, userText));
        }
        if (jobs.isEmpty()) {
            WorkPlan plan = planPhase(run, flow, cmd, userText, coordExec);
            if (plan == null) return; // planPhase already reported why
            List<AgentSpec> specs = syntheticWorkers(run, flow, plan);
            List<WorkPlan.WorkItem> items = plan.itemsOrEmpty();
            for (int i = 0; i < specs.size(); i++) {
                jobs.add(new WorkerJob(specs.get(i), items.get(i).prompt()));
            }
        }

        run.emit(RunEvent.of("system", "Fan-out: " + jobs.size() + " independent worker "
                + "process(es), up to " + timeoutSeconds + "s each. Each has its own workspace, "
                + "instructions and model; none can delegate further."));
        sayWhatIsMissing(run, flow);

        List<Future<Outcome>> futures = new ArrayList<>();
        for (WorkerJob job : jobs) {
            futures.add(pool.submit(() -> runWorker(run, job.spec(), cmd, job.prompt())));
        }

        List<Outcome> outcomes = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                outcomes.add(futures.get(i).get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                outcomes.add(new Outcome(jobs.get(i).spec(), false, null, "interrupted while waiting"));
            } catch (Exception e) {
                outcomes.add(new Outcome(jobs.get(i).spec(), false,
                        null, "worker thread failed: " + e.getMessage()));
            }
        }

        long failed = outcomes.stream().filter(o -> !o.ok()).count();
        writeCombinedReport(run, coordExec, outcomes, failed);

        // A stopped run keeps saying it was stopped. Its workers were killed and every outcome
        // reads "failed", but reporting that as ERROR would page someone about a run a human
        // ended on purpose.
        if ("TERMINATED".equals(run.status)) return;
        if (failed == outcomes.size()) {
            fail(run, "Every worker failed. The combined report lists each reason.");
            return;
        }

        // Adversarial verification, between the workers and the merge: a separate process whose
        // objective is to REJECT each output, with the power to make that stick — a rejected
        // output is withheld from the merge. A verifier that could only comment would be
        // decoration.
        List<Outcome> surviving = outcomes;
        AgentSpec verifier = flow.verifier();
        if (verifier != null) {
            WorkVerdict verdict = runVerifier(run, verifier, cmd, userText, outcomes);
            if ("ERROR".equals(run.status) || "TERMINATED".equals(run.status)) return;
            surviving = applyVerdict(run, verdict, outcomes);
            surviving = escalateRejected(run, verifier, cmd, userText, surviving);
            if ("ERROR".equals(run.status) || "TERMINATED".equals(run.status)) return;
            if (surviving.stream().noneMatch(Outcome::ok)) {
                fail(run, "The verifier rejected every worker's output — nothing survived to "
                        + "merge. Each rejection's reason is on its worker's box.");
                return;
            }
        }

        AgentSpec merger = flow.merger();
        if (merger != null) {
            runMerge(run, merger, cmd, userText, surviving);
            if ("ERROR".equals(run.status) || "TERMINATED".equals(run.status)) return;
        }
        settleIdle(run);
    }

    /** The turn ended without error: idle, or held for a human under approval mode. */
    private static void settleIdle(AgentRun run) {
        boolean waiting = LocalClaudeExecutor.awaitingApproval(run);
        run.status = waiting ? "AWAITING_APPROVAL" : "IDLE";
        if (waiting) {
            run.emit(RunEvent.of("system",
                    "Waiting for your approval — nothing has been changed yet."));
        }
    }

    /** Kills every live worker of this run. The run's status is the caller's to set. */
    public void stopWorkers(AgentRun run) {
        for (Process p : run.workerProcesses.values()) {
            p.destroy();
        }
    }

    // ---------------------------------------------------------------- planning phase

    /** The read-only planner's denylist: everything that changes anything, plus delegation. */
    private static final String PLANNER_READ_ONLY = "Task,Bash,Write,Edit,NotebookEdit";
    /**
     * The acting planner's denylist. Only delegation: a coordinator allowed to act may edit and
     * run commands while planning, but a planner that could open its own fan-out would still
     * turn bounded N processes into an unbounded tree.
     */
    private static final String PLANNER_MAY_ACT = "Task";

    /**
     * Whether this flow's planning coordinator keeps its hands off the machine.
     *
     * <p>The default is derived, not fixed: a coordinator with sub-agents wired to it exists to
     * distribute work, so it plans read-only; a solo coordinator is the one doing the work and
     * may act. The node's {@code coordinatorAccess} forces either shape when someone decides —
     * and a typo, normalized away in the spec, can only ever land back on the derived rule.
     */
    static boolean plannerReadOnly(CompiledFlow flow) {
        return switch (flow.coordinator().coordinatorAccess) {
            case "read-only" -> true;
            case "may-act" -> false;
            default -> !flow.subAgents().isEmpty();
        };
    }

    /**
     * Runs the coordinator as a read-only planning process and returns the plan it submitted,
     * or null after reporting why there is nothing to run.
     *
     * <p>The planner's only MCP server is the plan endpoint — not the run's tools endpoint,
     * which also carries the flow's API operations. A planner that could call external APIs
     * while "just planning" would act before anyone fanned anything out.
     */
    private WorkPlan planPhase(AgentRun run, CompiledFlow flow, String cmd, String userText,
                               NodeExec coordExec) {
        AgentSpec coord = flow.coordinator();
        Path workdir = runWorkspace(run, "coordinator");
        run.submittedPlan = null; // a stale plan from the previous turn must never run twice
        boolean readOnly = plannerReadOnly(flow);
        // Named on every planning turn, because it decides what the planner may do to this
        // machine and is otherwise invisible until something has already happened.
        run.emit(RunEvent.of("system", readOnly
                ? "Planning: the coordinator runs read-only"
                        + ("read-only".equals(coord.coordinatorAccess)
                                ? " (forced on this node)" : " (it has workers wired to it)")
                        + " and must submit a plan of independent work items (plan_submit)."
                : "Planning: the coordinator MAY EDIT FILES AND RUN COMMANDS while planning"
                        + ("may-act".equals(coord.coordinatorAccess)
                                ? " (forced on this node)"
                                : " (no sub-agents are wired to it, so it works alone)")
                        + "; delegation stays denied. It must still submit a plan (plan_submit).",
                coord.name, coord.nodeId));

        try {
            preparePlanningWorkspace(run, coord, workdir);
        } catch (IOException e) {
            fail(run, "The planning workspace could not be prepared: " + e.getMessage());
            return null;
        }

        List<Path> dirs = contextFoldersFor(run, coord);

        Outcome outcome = execute(run, coord, coordExec, cmd, userText, workdir, dirs,
                readOnly ? PLANNER_READ_ONLY : PLANNER_MAY_ACT);
        if ("TERMINATED".equals(run.status)) return null;
        if (!outcome.ok()) {
            markFailed(coordExec, outcome.error());
            fail(run, "The planning step failed: " + outcome.error());
            return null;
        }
        WorkPlan plan = run.submittedPlan;
        if (plan == null) {
            markFailed(coordExec, "finished without submitting a plan");
            fail(run, "The coordinator finished without submitting a plan (plan_submit was never "
                    + "accepted), so nothing ran."
                    + (outcome.finalText() == null || outcome.finalText().isBlank()
                            ? "" : " Its final message: " + outcome.finalText()));
            return null;
        }
        return plan;
    }

    private void preparePlanningWorkspace(AgentRun run, AgentSpec coord, Path workdir)
            throws IOException {
        Files.createDirectories(workdir);
        if (run.toolToken == null) run.toolToken = UUID.randomUUID().toString();

        StringBuilder md = new StringBuilder();
        md.append("""
                You are the planner of a fan-out flow. This turn you do exactly one thing: read
                whatever context you need, split the request into INDEPENDENT work items, and
                submit them with the plan_submit tool. You cannot edit files or run commands
                here, and you do not do the work yourself — workers do, in parallel, each seeing
                only its own item.

                Rules the submission enforces:
                - Each item: a short unique id, and a self-contained prompt (the worker sees
                  nothing else of this conversation — repeat what it needs in `context`).
                - Declare the files each item will touch; two items sharing a file reject the plan.
                - No dependencies between items — they run in parallel. A step that must come
                  after everything else belongs to the merge, not to an item.
                - After the plan is accepted, finish with a one-line summary. Do not keep working.
                """);
        List<com.concentus.model.FacadeProfile> available = profiles.list();
        if (!available.isEmpty()) {
            md.append("\nFacade profiles you may assign per item (field `profile`, by name):\n");
            for (com.concentus.model.FacadeProfile p : available) {
                md.append("- ").append(p.name())
                        .append(p.readOnly() ? " (read-only)"
                                : p.dryRunEnabled() ? " (writes are dry-run)" : " (writes execute)")
                        .append(p.description() == null || p.description().isBlank()
                                ? "" : " — " + p.description())
                        .append('\n');
            }
            md.append("An item without a profile reaches the servers wired to it with nothing "
                    + "filtered; name one to narrow that.\n");
        }
        if (!coord.mcpServers.isEmpty()) {
            md.append("\nMCP servers wired on this canvas (workers reach them through their "
                    + "facade profile): ").append(coord.mcpServers.stream()
                            .map(m -> m.name).collect(Collectors.joining(", "))).append(".\n");
        }
        appendSystemPrompt(coord, md);
        LocalClaudeExecutor.appendContextFolderNote(coord, md);
        Files.writeString(workdir.resolve("CLAUDE.md"), md.toString());

        // The planner's whole MCP world is the plan endpoint.
        writeSingleServerMcpConfig(workdir, "concentus-plan",
                runEndpoint(run, "plan"), run.toolToken);
    }

    /**
     * Turns accepted plan items into worker specs. Synthetic node ids ({@code worker:<id>})
     * mark them for the UI, which draws their boxes from the run report rather than the canvas.
     *
     * <p>Every synthetic worker inherits the canvas's MCP wiring (the coordinator's servers) —
     * which servers exist is drawn, which tools an item gets is its profile. Context folders
     * from the plan pass through the same allowlist as canvas values; a planner cannot name
     * host paths the deployment never opted into.
     */
    private List<AgentSpec> syntheticWorkers(AgentRun run, CompiledFlow flow, WorkPlan plan) {
        AgentSpec coord = flow.coordinator();
        List<AgentSpec> out = new ArrayList<>();
        for (WorkPlan.WorkItem item : plan.itemsOrEmpty()) {
            AgentSpec s = new AgentSpec();
            s.nodeId = "worker:" + item.id().trim();
            s.name = item.displayName();
            s.cliName = LocalClaudeExecutor.sanitize("w-" + item.id());
            s.systemPrompt = item.contextOrEmpty().isEmpty()
                    ? "" : String.join("\n", item.contextOrEmpty());
            s.model.id = item.model() == null || item.model().isBlank()
                    ? coord.model.id : item.model();
            s.model.maxTokens = coord.model.maxTokens;
            s.contextFolders = item.contextFoldersOrEmpty();
            s.facadeProfileId = item.profileId() == null ? "" : item.profileId();
            s.mcpServers = coord.mcpServers;
            run.syntheticWorkers.put(s.nodeId, s);
            out.add(s);
        }
        return out;
    }

    // ---------------------------------------------------------------- one worker

    /** One spawn: which spec runs, and the exact prompt it gets. */
    private record WorkerJob(AgentSpec spec, String prompt) {
    }

    private record Outcome(AgentSpec spec, boolean ok, String finalText, String error) {
    }

    private Outcome runWorker(AgentRun run, AgentSpec spec, String cmd, String userText) {
        boolean synthetic = spec.nodeId.startsWith("worker:");
        NodeExec exec = run.nodeExec(spec.nodeId, synthetic ? "worker" : "agent", spec.name);
        if (exec != null) {
            // Plan-born workers have no canvas node, so the model lookup by node id found
            // nothing — priced at the fallback unless attributed here.
            if (exec.model == null) exec.model = spec.model.id;
            exec.appendInput(userText);
            exec.status = "running";
        }

        Path workdir = runWorkspace(run, "workers", workerFolder(spec));
        try {
            prepareWorkspace(run, spec, workdir);
        } catch (IOException e) {
            return finish(exec, new Outcome(spec, false, null,
                    "workspace could not be prepared: " + e.getMessage()));
        }

        List<Path> dirs = contextFoldersFor(run, spec);

        // No Bash, deliberately: a fan-out is N unattended processes, and N shells is N times
        // the blast radius. Verification commands belong to the single merge step.
        return finish(exec, execute(run, spec, exec, cmd, userText, workdir, dirs, "Task,Bash"));
    }

    /** The attempts loop shared by workers and the merge step. Does not settle NodeExec status. */
    private Outcome execute(AgentRun run, AgentSpec spec, NodeExec exec, String cmd,
                            String prompt, Path workdir, List<Path> dirs, String disallowedTools) {
        int attempts = 1 + retries;
        String lastError = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if ("TERMINATED".equals(run.status)) {
                return new Outcome(spec, false, null, "run was stopped");
            }
            if (attempt > 1) {
                // Counted on the node so the graph metrics can say how much retrying propped
                // this run up — a run that only passes on second launches is not healthy.
                if (exec != null) exec.retries++;
                run.emit(RunEvent.of("system", "Retrying (" + attempt + "/" + attempts + ") after: "
                        + lastError, spec.name, spec.nodeId));
            }
            Attempt result = attempt(run, spec, exec, cmd, prompt, workdir, dirs, disallowedTools);
            if (result.ok()) {
                return new Outcome(spec, true, result.finalText(), null);
            }
            lastError = result.error();
            if (result.timedOut() || "TERMINATED".equals(run.status)) {
                // A timeout retried is a doubled timeout: the next attempt would spend the same
                // budget on the same task. Fail it and let the report say so.
                break;
            }
        }
        return new Outcome(spec, false, null, lastError);
    }

    private record Attempt(boolean ok, boolean timedOut, String finalText, String error) {
    }

    private Attempt attempt(AgentRun run, AgentSpec spec, NodeExec exec, String cmd,
                            String userText, Path workdir, List<Path> dirs, String disallowedTools) {
        boolean promptOnStdin = userText.length() > LocalClaudeExecutor.MAX_INLINE_PROMPT_CHARS;
        // Written next to the worker's own MCP config, and passed as a path — inline JSON does not
        // survive ProcessBuilder on Windows. See LocalClaudeExecutor.writePluginSettings.
        Path settingsFile = pluginRegistry == null ? null
                : LocalClaudeExecutor.writeSettingsFile(run, workdir,
                        pluginRegistry.settingsJsonFor(spec.plugins));
        List<String> args = buildWorkerArgs(cmd, run, spec, workdir, dirs,
                UUID.randomUUID().toString(), userText, promptOnStdin, disallowedTools, settingsFile);

        Process proc;
        try {
            proc = starter.start(args, workdir);
        } catch (IOException e) {
            return new Attempt(false, false, null, "failed to start claude: " + e.getMessage());
        }
        run.workerProcesses.put(spec.nodeId, proc);
        // Stop can race the spawn: stopWorkers() sweeps the map, and a process created after the
        // sweep but registered here would be missed by it — an orphan working for a stopped run
        // until its own timeout. Registering first and re-checking makes the pair safe in both
        // orders: either the sweep sees the process, or this check sees the stop.
        if ("TERMINATED".equals(run.status)) {
            proc.destroy();
        }
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
        if (outcome.ok()) {
            markPassed(exec);
        } else {
            markFailed(exec, outcome.error());
        }
        return outcome;
    }

    /**
     * Settles a node's box. Every step here — planner, worker, verifier, merge — ends by stamping
     * the same three fields, and a no-op on a null box is what lets each caller stay one line: a
     * plan-born step may have no canvas node to record against.
     */
    private static void markFailed(NodeExec exec, String error) {
        if (exec == null) return;
        exec.status = "failed";
        exec.error = error;
        exec.endedAt = System.currentTimeMillis();
    }

    private static void markPassed(NodeExec exec) {
        if (exec == null) return;
        exec.status = "passed";
        exec.endedAt = System.currentTimeMillis();
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
        preRunSubflows.inject(spec, run, m -> run.emit(RunEvent.of("system", m)));
        boolean facade = resolveFacade(run, spec);

        StringBuilder md = new StringBuilder();
        md.append("You are ").append(spec.name).append(", one of several independent workers in a "
                + "larger flow. Do only the task you are given, in your own workspace, and end "
                + "with a plain report of what you found or produced — another step merges the "
                + "workers' reports, so write yours to be read next to the others.\n");
        // Only when something is actually withheld. Telling a worker its writes might be
        // simulated when they are not is the same defect in reverse: it reports a real change as
        // a proposal, and the next run does it again.
        if (facade && restricts(run.workerFacadeProfiles.get(spec.nodeId))) {
            md.append("""

                    Your MCP tools go through a controlled facade. Some write actions may be
                    blocked or simulated (the result will say "DRY RUN"). A simulated action did
                    NOT happen: report it as a proposed action for someone with write permission
                    to confirm — never claim it was done.
                    """);
        }
        appendSystemPrompt(spec, md);
        LocalClaudeExecutor.appendContextFolderNote(spec, md);
        Files.writeString(workdir.resolve("CLAUDE.md"), md.toString());

        materialiseSkills(run, spec, workdir);

        // With a facade: exactly one server — this backend, scoped to this worker, authenticated
        // by a token only this worker's process is given. Without one: empty and strict, which
        // states "no servers" in both places rather than leaving one to be inferred.
        if (facade) {
            writeSingleServerMcpConfig(workdir, "concentus-facade",
                    runEndpoint(run, "workers/" + spec.nodeId + "/tools"),
                    run.workerToolTokens.get(spec.nodeId));
        } else {
            writeEmptyMcpConfig(workdir);
        }
    }

    /**
     * This worker's own assigned skills, written into its own workspace.
     *
     * <p>Per worker, not per flow: a worker is its own process with its own directory, so unlike
     * the shared session it can have exactly the skills its agent was given. Workers used to get
     * none at all while the Skill tool stayed available — so an assigned skill did nothing and the
     * machine's personal ones were reachable anyway, which is the opposite of what the picker says.
     */
    private void materialiseSkills(AgentRun run, AgentSpec spec, Path workdir) {
        if (skillStore == null || skillService == null || spec.skills == null) return;
        List<com.concentus.model.SkillDef> defs = spec.skills.stream()
                .filter(sk -> "custom".equals(sk.type) && sk.id != null)
                .map(sk -> sk.id)
                .distinct()
                .map(skillStore::get)
                .flatMap(java.util.Optional::stream)
                .toList();
        if (defs.isEmpty()) return;
        try {
            skillService.materialise(workdir, defs);
            run.emit(RunEvent.of("system", defs.size() + " skill(s) installed for this worker: "
                    + defs.stream().map(com.concentus.model.SkillDef::name)
                            .collect(java.util.stream.Collectors.joining(", ")) + ".",
                    spec.name, spec.nodeId));
        } catch (IOException e) {
            run.emit(RunEvent.of("system", "Skills could not be installed: " + e.getMessage(),
                    spec.name, spec.nodeId));
        }
    }

    /** No servers at all, said in the file rather than by leaving it out — see {@link #prepareWorkspace}. */
    private void writeEmptyMcpConfig(Path workdir) throws IOException {
        var root = mapper.createObjectNode();
        root.putObject("mcpServers");
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
        var chosen = spec.facadeProfileId == null || spec.facadeProfileId.isBlank()
                ? java.util.Optional.<com.concentus.model.FacadeProfile>empty()
                : profiles.get(spec.facadeProfileId);

        com.concentus.model.FacadeProfile profile;
        if (chosen.isEmpty()) {
            // No profile means the servers this agent is wired to, unfiltered — the same reach it
            // has as a sub-agent of a shared session. It used to mean NO tools at all, which
            // protected nothing the wiring did not already protect: a worker is only ever offered
            // the servers drawn into ITS node, so "nothing" and "what is wired" differ by the
            // whole feature, not by a safety margin. What it did buy was a run that reads no data,
            // reports the account as unreachable, and bills for the attempt.
            profile = PASS_THROUGH;
            String missing = spec.facadeProfileId == null || spec.facadeProfileId.isBlank()
                    ? "no facade profile"
                    : "a facade profile that no longer exists";
            run.emit(RunEvent.of("system", "Worker '" + spec.name + "' has " + missing
                    + ", so it reaches the " + spec.mcpServers.size() + " MCP server(s) wired to "
                    + "it with nothing filtered, writes included. Assign a profile "
                    + "(Resources → Facades) to narrow that to an allowlist, read-only, or "
                    + "simulated writes.", spec.name, spec.nodeId));
        } else {
            profile = chosen.get();
            run.emit(RunEvent.of("system", "Worker '" + spec.name + "' runs behind facade profile '"
                    + profile.name() + "'" + (profile.readOnly() ? " (read-only)" : "")
                    + (profile.dryRunEnabled() && !profile.readOnly()
                            ? " (writes are dry-run)" : "") + ".", spec.name, spec.nodeId));
        }
        run.workerFacadeProfiles.put(spec.nodeId, profile);
        run.workerToolTokens.put(spec.nodeId, UUID.randomUUID().toString());
        return true;
    }

    /** Whether a profile withholds anything at all — an allowlist, read-only, or simulated writes. */
    private static boolean restricts(com.concentus.model.FacadeProfile profile) {
        return profile != null && (profile.readOnly() || profile.dryRunEnabled()
                || !profile.toolsOrEmpty().isEmpty());
    }

    /**
     * The profile a worker gets when its node names none: everything its own MCP nodes offer.
     *
     * <p>Every field is the widest reading, and {@code dryRun} is false <b>explicitly</b> — the
     * record treats null as true, and a silent dry run would be the same failure as an empty tool
     * list wearing a different hat: the worker reports work it never did.
     *
     * <p>Calls still go through the facade endpoint, so the enforcement point is unchanged and
     * assigning a profile later narrows a worker that is already running the right way.
     */
    private static final com.concentus.model.FacadeProfile PASS_THROUGH =
            new com.concentus.model.FacadeProfile(null, "unrestricted",
                    "No profile assigned: the servers wired to this worker, unfiltered.",
                    List.of(), false, Boolean.FALSE);

    /** Folder-safe, unique per agent: the compiler already made {@code cliName} both. */
    private static String workerFolder(AgentSpec spec) {
        return spec.cliName != null ? spec.cliName : LocalClaudeExecutor.sanitize(spec.name);
    }

    /** A step's own directory under this run's local workspace. Absolute, so no cwd re-resolves it. */
    private Path runWorkspace(AgentRun run, String... parts) {
        Path dir = Path.of(dataDir, "local", run.id);
        for (String part : parts) {
            dir = dir.resolve(part);
        }
        return dir.toAbsolutePath().normalize();
    }

    /** This backend's per-run endpoint for a step's own tool server. */
    private String runEndpoint(AgentRun run, String suffix) {
        return "http://127.0.0.1:" + serverPort + "/api/runs/" + run.id + "/" + suffix;
    }

    /** Appends an agent's own instructions, when the node carries any. */
    private static void appendSystemPrompt(AgentSpec spec, StringBuilder md) {
        if (spec.systemPrompt != null && !spec.systemPrompt.isBlank()) {
            md.append('\n').append(spec.systemPrompt).append('\n');
        }
    }

    /** The folders a step may read, with every rejection reported on its own box. */
    private List<Path> contextFoldersFor(AgentRun run, AgentSpec spec) {
        return contextFolders.resolve(spec.contextFolders, (path, reason) ->
                run.emit(RunEvent.of("system", "Context folder ignored — " + path + ": " + reason,
                        spec.name, spec.nodeId)));
    }

    /**
     * Writes a step's MCP config carrying exactly one server: this backend, at {@code endpoint},
     * authenticated by a token only that step's process is given.
     *
     * <p>The single-server shape is what confines a step to the one endpoint it may talk to — the
     * planner to plan_submit, the verifier to verdict_submit — so neither can act on the world
     * while it is deciding what should happen to it.
     */
    private void writeSingleServerMcpConfig(Path workdir, String serverName, String endpoint,
                                            String token) throws IOException {
        var root = mapper.createObjectNode();
        var server = root.putObject("mcpServers").putObject(serverName);
        server.put("type", "http");
        server.put("url", endpoint);
        server.putObject("headers")
                .put(com.concentus.web.RunToolsController.TOKEN_HEADER, token);
        Files.writeString(workdir.resolve(LocalClaudeExecutor.MCP_CONFIG_FILE),
                mapper.writeValueAsString(root));
    }

    // Package-private for the arg-shape test, like LocalClaudeExecutor.buildArgs: the ordering is
    // load-bearing and silent when wrong.
    List<String> buildWorkerArgs(String cmd, AgentRun run, AgentSpec spec, Path workdir,
                                 List<Path> contextDirs, String sessionId, String userText,
                                 boolean promptOnStdin, String disallowedTools, Path settingsFile) {
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
        // Workers are separate processes, so plugin selection here is truly per-agent — this
        // worker's own list, not the flow-wide union the shared session gets. An empty list is a
        // selection too: it disables every installed plugin for this worker.
        if (settingsFile != null) {
            a.add("--settings");
            a.add(settingsFile.toString());
        }
        a.add("--mcp-config");
        a.add(workdir.resolve(LocalClaudeExecutor.MCP_CONFIG_FILE).toString());
        a.add("--strict-mcp-config");
        // Always at least Task: a worker that could open its own fan-out would turn a bounded N
        // processes into an unbounded tree. Workers also lose Bash; the merge step keeps it.
        // Plus Skill for anyone who was assigned none — the tool reaches the machine's personal
        // skills, so leaving it on would hand a worker skills its agent never chose.
        a.add("--disallowedTools");
        a.add(spec.skills == null || spec.skills.isEmpty()
                ? disallowedTools + "," + LocalClaudeExecutor.SKILL_TOOL
                : disallowedTools);
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
                    LocalStreamEventHandler.applyContext(exec,
                            LocalStreamEventHandler.promptOf(usage), LocalStreamEventHandler.contextOf(usage));
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
                    // Through the synchronized accrual: N workers report concurrently, and a
                    // bare `volatile +=` here lost updates on CI's slower runners.
                    run.accrueUsage(usage.path("input_tokens").asLong(0),
                            usage.path("output_tokens").asLong(0),
                            usage.path("cache_read_input_tokens").asLong(0),
                            usage.path("cache_creation_input_tokens").asLong(0));
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
     * What each worker concluded, failures included by name, on the coordinator's box — it is
     * the one box every flow has, and the report is the answer to the instruction it received.
     * When a merge node exists this is the merge's input record, not the run's last word.
     */
    private void writeCombinedReport(AgentRun run, NodeExec coordExec, List<Outcome> outcomes,
                                     long failed) {
        String report = combinedReport(outcomes, failed);
        AgentSpec coord = run.compiled.coordinator();
        if (coordExec != null) {
            coordExec.appendOutput(report);
            coordExec.status = failed == outcomes.size() ? "failed" : "passed";
            coordExec.endedAt = System.currentTimeMillis();
        }
        run.emit(RunEvent.of("agent_message", report, coord.name, coord.nodeId));
    }

    private static String combinedReport(List<Outcome> outcomes, long failed) {
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
        return md.toString();
    }

    // ---------------------------------------------------------------- verifier step

    /**
     * The adversarial verification step: one more {@code claude} process, after every worker has
     * finished and before the merge, whose objective is the workers' inverse — not "find the
     * strongest answer" but "find the reason this one should be rejected". Worker and verifier
     * sharing an objective is how plausible-but-wrong output sails through; the opposition is
     * the point.
     *
     * <p>It runs read-only (the planner's denylist) and its only MCP server is the verdict
     * endpoint, so judging is all it can do — but it reads the workers' real workspaces via
     * {@code --add-dir}, so "judge" can mean "check what they actually produced" and not just
     * "grade what they claimed". Its verdict has teeth: a rejected output never reaches the
     * merge. Only outputs that finished are judged — a worker that already failed has nothing
     * left to kill.
     *
     * <p>Returns the accepted verdict, or null after failing the run: a verifier that errors or
     * never submits leaves the outputs UNVERIFIED, and passing unverified output along as if it
     * had been judged is the one thing this step must never do.
     */
    private WorkVerdict runVerifier(AgentRun run, AgentSpec verifier, String cmd, String userText,
                                    List<Outcome> outcomes) {
        NodeExec exec = run.nodeExec(verifier.nodeId, "agent", verifier.name);
        Path workdir = runWorkspace(run, "verifier");
        Path workersRoot = runWorkspace(run, "workers");

        run.submittedVerdict = null; // last turn's judgment must never cover this turn's outputs
        run.verdictExpected.clear();
        List<Outcome> judged = outcomes.stream().filter(Outcome::ok).toList();
        for (Outcome o : judged) run.verdictExpected.add(o.spec().nodeId);

        String prompt = verifierPrompt(userText, judged, workersRoot);
        if (exec != null) {
            exec.appendInput(prompt);
            exec.status = "running";
        }
        run.emit(RunEvent.of("system", "Verification: an adversarial verifier now tries to "
                + "reject each of the " + judged.size() + " surviving output(s). A rejected "
                + "output is withheld from the merge.", verifier.name, verifier.nodeId));

        try {
            prepareVerifierWorkspace(run, verifier, workdir);
        } catch (IOException e) {
            markVerifierFailed(run, exec, "verifier workspace could not be prepared: " + e.getMessage());
            return null;
        }

        List<Path> dirs = new ArrayList<>();
        if (Files.isDirectory(workersRoot)) dirs.add(workersRoot);
        dirs.addAll(contextFoldersFor(run, verifier));

        Outcome outcome = execute(run, verifier, exec, cmd, prompt, workdir, dirs, PLANNER_READ_ONLY);
        if ("TERMINATED".equals(run.status)) return null;
        if (!outcome.ok()) {
            markVerifierFailed(run, exec, outcome.error());
            return null;
        }
        WorkVerdict verdict = run.submittedVerdict;
        if (verdict == null) {
            markVerifierFailed(run, exec, "finished without submitting a verdict (verdict_submit "
                    + "was never accepted)"
                    + (outcome.finalText() == null || outcome.finalText().isBlank()
                            ? "" : ". Its final message: " + outcome.finalText()));
            return null;
        }
        markPassed(exec);
        return verdict;
    }

    /**
     * Applies the verdict: marks every judged worker's box, and replaces a rejected worker's
     * outcome with an explicit kill — the merge is told the slice is a gap and why, and never
     * sees the rejected content. Withholding rather than annotating, deliberately: content
     * handed to a reasoning step gets reasoned about, however sternly it is labelled.
     */
    private static List<Outcome> applyVerdict(AgentRun run, WorkVerdict verdict,
                                              List<Outcome> outcomes) {
        List<Outcome> out = new ArrayList<>(outcomes.size());
        for (Outcome o : outcomes) {
            WorkVerdict.Item item = o.ok() ? verdict.of(o.spec().nodeId) : null;
            NodeExec exec = item == null ? null : run.nodeExec(o.spec().nodeId, "agent", o.spec().name);
            if (item != null && exec != null) {
                exec.verdict = item.rejected() ? "rejected" : "accepted";
                exec.verdictReason = item.rejected() ? item.reason() : null;
            }
            if (item != null && item.rejected()) {
                run.emit(RunEvent.of("system", "Output of '" + o.spec().name
                        + "' REJECTED by the verifier: " + item.reason(),
                        o.spec().name, o.spec().nodeId));
                out.add(new Outcome(o.spec(), false, null,
                        "output rejected by the verifier: " + item.reason()));
            } else {
                out.add(o);
            }
        }
        return out;
    }

    /**
     * The cost router: an output the verifier refused gets one more attempt, on the stronger
     * model its agent nominated.
     *
     * <p>The point is to make "cheap first" safe rather than hopeful. A cheap model doing the
     * work is only a saving if someone checks it, so escalation is driven by the one signal that
     * means "this answer is actually wrong" — a verifier REJECTION. A process failure is not that
     * signal (it says nothing about the model) and neither is a worker nobody judged, which is why
     * this runs only here, after a verdict.
     *
     * <p>Exactly once per worker, and the re-run is judged like any other output: the second
     * verdict is what decides whether it merges. Nothing unverified reaches the merge, which is
     * the invariant the verifier exists to hold.
     */
    private List<Outcome> escalateRejected(AgentRun run, AgentSpec verifier, String cmd,
                                           String userText, List<Outcome> judged) {
        List<Outcome> toEscalate = judged.stream()
                .filter(o -> !o.ok() && !o.spec().fallbackModelId.isBlank())
                .filter(o -> rejectedByVerifier(run, o))
                .toList();
        if (toEscalate.isEmpty()) return judged;

        List<Outcome> retried = new ArrayList<>(toEscalate.size());
        java.util.Map<String, String> notes = new java.util.LinkedHashMap<>();
        for (Outcome o : toEscalate) {
            AgentSpec spec = o.spec();
            String cheap = spec.model.id;
            NodeExec exec = run.nodeExec(spec.nodeId, "agent", spec.name);
            run.emit(RunEvent.of("system", "Output rejected on " + cheap + " — retrying '"
                    + spec.name + "' on " + spec.fallbackModelId + ".", spec.name, spec.nodeId));
            notes.put(spec.nodeId,
                    "Retried on " + spec.fallbackModelId + " after rejection on " + cheap + ".");
            // The spec is this run's own compiled copy, so pointing it at the stronger model is
            // contained to this run.
            spec.model.id = spec.fallbackModelId;
            if (exec != null) {
                exec.retries++;
                // Priced at the model that produced the output that ships. Both attempts' tokens
                // land on this one node, so this OVERSTATES the first (cheap) attempt rather than
                // understating the bill — the safe direction for a number about money. Said out
                // loud because there is no per-attempt cost record to be exact with.
                exec.model = spec.fallbackModelId;
            }
            retried.add(runWorker(run, spec, cmd, userText));
            if ("TERMINATED".equals(run.status)) return judged;
        }

        List<Outcome> ok = retried.stream().filter(Outcome::ok).toList();
        if (!ok.isEmpty()) {
            WorkVerdict second = runVerifier(run, verifier, cmd, userText, ok);
            if ("ERROR".equals(run.status) || "TERMINATED".equals(run.status)) return judged;
            retried = applyVerdict(run, second, retried);
        }
        // After the second verdict, never before: an acceptance clears the box's reason, and the
        // note is the trail of what actually happened to this worker.
        notes.forEach((nodeId, note) -> {
            NodeExec exec = run.nodeExec(nodeId, "agent", nodeId);
            if (exec == null) return;
            exec.verdictReason = exec.verdictReason == null || exec.verdictReason.isBlank()
                    ? note : exec.verdictReason + " — " + note;
        });
        return replace(judged, retried);
    }

    /** Whether the verdict this run recorded on that worker's box was a rejection. */
    private static boolean rejectedByVerifier(AgentRun run, Outcome o) {
        NodeExec exec = run.nodeExec(o.spec().nodeId, "agent", o.spec().name);
        return exec != null && "rejected".equals(exec.verdict);
    }

    /** The original list with each re-run worker's outcome swapped in, order preserved. */
    private static List<Outcome> replace(List<Outcome> original, List<Outcome> updated) {
        List<Outcome> out = new ArrayList<>(original.size());
        for (Outcome o : original) {
            out.add(updated.stream()
                    .filter(u -> u.spec().nodeId.equals(o.spec().nodeId))
                    .findFirst().orElse(o));
        }
        return out;
    }

    private void markVerifierFailed(AgentRun run, NodeExec exec, String error) {
        markFailed(exec, error);
        if (!"TERMINATED".equals(run.status)) {
            fail(run, "The verification step failed: " + error + " The workers' combined report "
                    + "above still stands, but it is UNVERIFIED — the run stops rather than "
                    + "passing it along as judged.");
        }
    }

    private void prepareVerifierWorkspace(AgentRun run, AgentSpec verifier, Path workdir)
            throws IOException {
        Files.createDirectories(workdir);
        if (run.verdictToken == null) run.verdictToken = UUID.randomUUID().toString();
        if (!run.workersPrepared.add("verifier:" + verifier.nodeId)) return;

        ragInjector.inject(verifier, run, m -> run.emit(RunEvent.of("system", m)));
        preRunSubflows.inject(verifier, run, m -> run.emit(RunEvent.of("system", m)));

        StringBuilder md = new StringBuilder();
        md.append("""
                You are the adversarial verifier of a fan-out flow: several independent workers
                each produced an output, and your one job is to try to REJECT each one — find
                the reason it is wrong, incomplete, unverified, or off-task. You are not here to
                improve or summarize anything; the workers argued FOR their answers, you argue
                against. An output you genuinely cannot fault is accepted; everything else is
                rejected with the concrete reason. Reject on substance, never on style.

                You cannot edit files or run commands. You CAN read the workers' real
                workspaces — judge what they produced, not just what they claimed. When every
                worker is judged, submit ALL verdicts in one verdict_submit call (a rejected
                output is withheld from the merge step), then finish with a one-line summary.
                """);
        appendSystemPrompt(verifier, md);
        LocalClaudeExecutor.appendContextFolderNote(verifier, md);
        Files.writeString(workdir.resolve("CLAUDE.md"), md.toString());

        // The verifier's whole MCP world is the verdict endpoint — judging is all it can do.
        writeSingleServerMcpConfig(workdir, "concentus-verdict",
                runEndpoint(run, "verdict"), run.verdictToken);
    }

    /** The verifier's input: the goal, each surviving output, and where the real files sit. */
    private static String verifierPrompt(String userText, List<Outcome> judged, Path workersRoot) {
        StringBuilder p = new StringBuilder();
        p.append("# Task the workers were given\n\n").append(userText).append("\n\n");
        p.append("# Outputs to judge\n");
        for (Outcome o : judged) {
            p.append("\n## ").append(o.spec().name)
                    .append(" (id: ").append(o.spec().nodeId).append(")\n");
            p.append(o.finalText() == null || o.finalText().isBlank()
                    ? "(finished without a final message)" : o.finalText()).append('\n');
        }
        p.append("\nTheir full workspaces (files they wrote, one folder per worker) are under: ")
                .append(workersRoot).append("\n");
        p.append("\nJudge every output above and submit one verdict per listed id via "
                + "verdict_submit — accept or reject, with the reason for each rejection.\n");
        return p.toString();
    }

    // ---------------------------------------------------------------- merge step

    /**
     * The merge step: one more {@code claude} process, after every worker has finished, whose
     * job is to reconcile the workers' reports into the run's actual answer — and to run the
     * checks the workers deliberately could not: workers have no Bash (a fan-out of N unattended
     * processes each running shell commands is N times the blast radius), the merge does,
     * because verifying the combined result is exactly one process's job.
     *
     * <p>It reads the workers' workspaces read-only via {@code --add-dir}, so "merge" can mean
     * "diff what they produced" and not just "paraphrase what they said".
     */
    private void runMerge(AgentRun run, AgentSpec merger, String cmd, String userText,
                          List<Outcome> outcomes) {
        NodeExec exec = run.nodeExec(merger.nodeId, "agent", merger.name);
        Path workdir = runWorkspace(run, "merge");
        Path workersRoot = runWorkspace(run, "workers");

        String prompt = mergePrompt(userText, outcomes, workersRoot);
        if (exec != null) {
            exec.appendInput(prompt);
            exec.status = "running";
        }
        run.emit(RunEvent.of("system", "Merge: reconciling " + outcomes.size()
                + " worker report(s)" + (merger.systemPrompt == null || merger.systemPrompt.isBlank()
                        ? "" : " under this flow's merge instructions") + ".",
                merger.name, merger.nodeId));

        try {
            prepareMergeWorkspace(run, merger, workdir);
        } catch (IOException e) {
            markMergeFailed(run, exec, "merge workspace could not be prepared: " + e.getMessage());
            return;
        }

        List<Path> dirs = new ArrayList<>();
        if (Files.isDirectory(workersRoot)) dirs.add(workersRoot);
        dirs.addAll(contextFoldersFor(run, merger));

        // Bash stays available — running the tests is the point — but delegation does not.
        Outcome outcome = execute(run, merger, exec, cmd, prompt, workdir, dirs, "Task");
        if (outcome.ok()) {
            markPassed(exec);
        } else {
            markMergeFailed(run, exec, outcome.error());
        }
    }

    private void markMergeFailed(AgentRun run, NodeExec exec, String error) {
        markFailed(exec, error);
        if (!"TERMINATED".equals(run.status)) {
            fail(run, "The merge step failed: " + error
                    + " The workers' combined report above still stands.");
        }
    }

    private void prepareMergeWorkspace(AgentRun run, AgentSpec merger, Path workdir)
            throws IOException {
        Files.createDirectories(workdir);
        if (!run.workersPrepared.add("merge:" + merger.nodeId)) return;

        ragInjector.inject(merger, run, m -> run.emit(RunEvent.of("system", m)));
        preRunSubflows.inject(merger, run, m -> run.emit(RunEvent.of("system", m)));

        StringBuilder md = new StringBuilder();
        md.append("""
                You are the merge step of a fan-out flow: several independent workers ran the
                same overall task, each on its own slice, and their reports are in your prompt.
                Your job is to reconcile them into one answer — verify, deduplicate, resolve
                contradictions, and say plainly what failed and what is missing. You may run
                commands (tests, diffs) to verify claims; the workers could not, so unverified
                claims are yours to check, not to repeat.
                """);
        appendSystemPrompt(merger, md);
        LocalClaudeExecutor.appendContextFolderNote(merger, md);
        Files.writeString(workdir.resolve("CLAUDE.md"), md.toString());

        // Empty and strict, like a worker without a facade: the merge reconciles and verifies on
        // disk; if it ever needs MCP reach, that is a facade profile decision, not a default.
        writeEmptyMcpConfig(workdir);
    }

    /** The merge's input: the goal, each worker's outcome, and where their real files sit. */
    private static String mergePrompt(String userText, List<Outcome> outcomes, Path workersRoot) {
        StringBuilder p = new StringBuilder();
        p.append("# Task the workers were given\n\n").append(userText).append("\n\n");
        p.append("# Worker outcomes\n");
        for (Outcome o : outcomes) {
            p.append("\n## ").append(o.spec().name)
                    .append(o.ok() ? "" : " — FAILED").append('\n');
            if (o.ok()) {
                p.append(o.finalText() == null || o.finalText().isBlank()
                        ? "(finished without a final message)" : o.finalText()).append('\n');
            } else {
                p.append("Failed: ").append(o.error()).append('\n');
            }
        }
        p.append("\nTheir full workspaces (files they wrote, one folder per worker) are under: ")
                .append(workersRoot).append("\n");
        p.append("\nProduce the final merged result now. Account for every worker above — "
                + "including the failed ones, whose slices are gaps to name, not to invent.\n");
        return p.toString();
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
