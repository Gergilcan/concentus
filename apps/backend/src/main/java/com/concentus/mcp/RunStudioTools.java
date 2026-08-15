package com.concentus.mcp;

import com.concentus.model.CommandRequest;
import com.concentus.model.FlowGraph;
import com.concentus.model.NodeExec;
import com.concentus.model.NodeExecReport;
import com.concentus.model.RunDetail;
import com.concentus.model.RunEvent;
import com.concentus.model.RunSummary;
import com.concentus.service.AgentRun;
import com.concentus.service.RunService;
import com.concentus.web.FlowController;
import com.concentus.web.RunController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Launching runs and steering them while they execute.
 *
 * <p>Everything here is asynchronous underneath: starting a run returns as soon as the run exists,
 * long before it has done anything. So the tools are written for a caller that will come back —
 * {@code run_flow} says what to poll and with what, and every status the model might mistake for
 * "finished" is spelled out. The one exception is the readiness wait in {@link #awaitReady}, which
 * exists because the alternative is worse: a model that starts a manual flow and immediately sends
 * its first instruction hits a run that is still compiling, gets told to wait, and has no way to
 * wait except by calling something else.
 */
// Fixes this group's place in tools/list — then running what was designed.
@Order(2)
@Component
public class RunStudioTools implements StudioToolset {

    /** Events per {@code get_run} call by default, and the most it will ever return. */
    private static final int EVENTS_DEFAULT = 100;
    private static final int EVENTS_MAX = 500;
    /** Characters of a node's input/output kept in {@code get_run_nodes}. */
    private static final int NODE_TEXT_MAX = 2000;
    /** How long {@code send_command} waits for a starting run before giving up, in milliseconds. */
    private static final long READY_TIMEOUT_MS = 20_000;
    private static final long READY_POLL_MS = 250;

    private final RunController runs;
    private final FlowController flows;
    private final RunService runService;
    private final ObjectMapper mapper;

    public RunStudioTools(RunController runs, FlowController flows, RunService runService,
                          ObjectMapper mapper) {
        this.runs = runs;
        this.flows = flows;
        this.runService = runService;
        this.mapper = mapper;
    }

    @Override
    public List<StudioTool> tools() {
        return List.of(
                new StudioTool("run_flow",
                        "Starts an execution. Returns immediately with a run id — the run has NOT "
                                + "finished, and usually has not started working yet. Poll get_run to "
                                + "follow it. A flow whose trigger is 'manual' waits for a first "
                                + "instruction: pass prompt here, or send it later with send_command.",
                        Schema.of(mapper)
                                .str("flowId", "Id of a saved flow to run. Give this OR flow.")
                                .object("flow", "An unsaved graph to run ad-hoc. Give this OR flowId.", false)
                                .str("prompt", "First instruction, sent once the run is ready. Omit to "
                                        + "leave the run idle.")
                                .build(),
                        this::runFlow),

                new StudioTool("list_runs",
                        "Recent executions with their status, trigger, token usage and cost.",
                        Schema.of(mapper)
                                .str("flowId", "Only executions of this flow. Omit for all of them.")
                                .number("limit", "How many, newest first. Default 25.")
                                .build(),
                        this::listRuns),

                new StudioTool("get_run",
                        "An execution's status and its console output, oldest line last. This is how you "
                                + "find out what an agent actually did — and what went wrong when a run "
                                + "failed. Status RUNNING or STARTING means come back later; IDLE means "
                                + "it is waiting for an instruction; AWAITING_APPROVAL means a human (or "
                                + "approve_run) has to answer before it continues.",
                        Schema.of(mapper)
                                .requiredStr("runId", "Id of the execution, from run_flow or list_runs.")
                                .number("limit", "How many of the most recent lines. Default "
                                        + EVENTS_DEFAULT + ", maximum " + EVENTS_MAX + ".")
                                .str("agent", "Only lines produced by this agent, by display name.")
                                .build(),
                        this::getRun),

                new StudioTool("get_run_nodes",
                        "Per-block execution detail: what each agent was asked, what it handed back, its "
                                + "status, tokens and cost. Better than get_run when you want one "
                                + "sub-agent's result rather than the whole console.",
                        Schema.of(mapper)
                                .requiredStr("runId", "Id of the execution.")
                                .str("node", "Only this block, by node id or label. Omit for all of them.")
                                .build(),
                        this::getRunNodes),

                new StudioTool("get_run_flow",
                        "The exact graph an execution ran, snapshotted at launch. Use it when a run "
                                + "failed and the flow has been edited since — this is what actually ran, "
                                + "which get_flow no longer is.",
                        Schema.of(mapper)
                                .requiredStr("runId", "Id of the execution.")
                                .build(),
                        this::getRunFlow),

                new StudioTool("send_command",
                        "Sends an instruction to a running execution — the same box a person types into. "
                                + "Each command is another turn in the same session, so the agent keeps "
                                + "everything it already knows. Waits for a starting run to become ready "
                                + "before sending.",
                        Schema.of(mapper)
                                .requiredStr("runId", "Id of the execution.")
                                .requiredStr("text", "What to tell the agent.")
                                .build(),
                        this::sendCommand),

                new StudioTool("stop_run",
                        "Stops a running execution. A stopped run counts as neither a success nor a "
                                + "failure and is excluded from the flow's success rate.",
                        Schema.of(mapper)
                                .requiredStr("runId", "Id of the execution to stop.")
                                .build(),
                        this::stopRun),

                new StudioTool("approve_run",
                        "Approves the plan of an execution stopped at AWAITING_APPROVAL, letting the same "
                                + "session resume with permission to act. Read the plan with get_run "
                                + "first, and do not approve on the user's behalf unless they asked you to.",
                        Schema.of(mapper)
                                .requiredStr("runId", "Id of the execution awaiting approval.")
                                .build(),
                        this::approveRun),

                new StudioTool("reject_run",
                        "Rejects the plan of an execution stopped at AWAITING_APPROVAL. The run ends "
                                + "without acting.",
                        Schema.of(mapper)
                                .requiredStr("runId", "Id of the execution awaiting approval.")
                                .build(),
                        this::rejectRun),

                new StudioTool("retry_run",
                        "Re-runs an execution's flow with the same first instruction, as a NEW execution. "
                                + "The original is left untouched.",
                        Schema.of(mapper)
                                .requiredStr("runId", "Id of the execution to replay.")
                                .build(),
                        this::retryRun));
    }

    // ----------------------------------------------------------------- launch

    private StudioTool.Result runFlow(JsonNode args) {
        String flowId = Args.str(args, "flowId", null);
        JsonNode inline = args.path("flow");
        if (flowId == null && !inline.isObject()) {
            throw new IllegalArgumentException("Give either 'flowId' (a saved flow) or 'flow' (an ad-hoc graph).");
        }
        RunSummary started = flowId != null ? flows.run(flowId) : runs.start(read(inline));

        StringBuilder out = new StringBuilder("Started execution " + started.id()
                + " of '" + started.flowName() + "' (" + started.mode() + ").\n");
        String prompt = Args.str(args, "prompt", null);
        if (prompt != null) {
            Ready ready = awaitReady(started.id());
            if (!ready.ok()) {
                out.append("\nThe run started but your first instruction was NOT sent: ")
                        .append(ready.detail())
                        .append("\nCall get_run to see why, then send_command when it is ready.");
                return StudioTool.Result.failed(out.toString());
            }
            runs.command(started.id(), new CommandRequest(prompt));
            out.append("First instruction sent.\n");
        }
        out.append("\nIt is running now — nothing has been produced yet. Poll get_run with runId=")
                .append(started.id()).append(" until its status is COMPLETED or ERROR.");
        return StudioTool.Result.ok(out.toString());
    }

    /**
     * Whether a run has finished starting up, waited for rather than asked once.
     *
     * <p>A run is created in STARTING and reaches IDLE/RUNNING only after its workspace is
     * prepared — repositories cloned, MCP config written — which is seconds, not milliseconds.
     * Bounded so a run that dies during startup answers rather than hanging the tool call.
     */
    private Ready awaitReady(String runId) {
        long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
        while (true) {
            AgentRun run = runService.get(runId).orElse(null);
            if (run == null) return new Ready(false, "the execution no longer exists.");
            String status = run.status == null ? "" : run.status;
            switch (status) {
                case "ERROR" -> {
                    return new Ready(false, "it failed while starting: "
                            + (run.error == null ? "no reason recorded" : run.error));
                }
                case "TERMINATED" -> {
                    return new Ready(false, "it was stopped before it was ready.");
                }
                case "STARTING" -> {
                    if (System.currentTimeMillis() >= deadline) {
                        return new Ready(false, "it is still starting after "
                                + (READY_TIMEOUT_MS / 1000) + " seconds.");
                    }
                    try {
                        Thread.sleep(READY_POLL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return new Ready(false, "the wait was interrupted.");
                    }
                }
                default -> {
                    return new Ready(true, status);
                }
            }
        }
    }

    private record Ready(boolean ok, String detail) {
    }

    // ---------------------------------------------------------------- reading

    private StudioTool.Result listRuns(JsonNode args) {
        String flowId = Args.str(args, "flowId", null);
        int limit = Args.count(args, "limit", 25, 200);
        List<RunSummary> matching = runs.list().stream()
                .filter(r -> flowId == null || flowId.equals(r.flowId()))
                .limit(limit)
                .toList();
        return StudioTool.Result.ok(Answers.lines("execution(s)",
                flowId == null ? "No executions yet."
                        : "No executions of flow '" + flowId + "' yet.",
                matching, this::describe));
    }

    private String describe(RunSummary run) {
        StringBuilder line = new StringBuilder(run.id() + "  " + run.status());
        line.append("  ").append(Answers.when(run.createdAt()));
        line.append("  ").append(run.flowName() == null ? "(ad-hoc)" : run.flowName());
        line.append("  [").append(run.trigger() == null ? "manual" : run.trigger());
        if (run.golden()) line.append(", GOLDEN");
        line.append(", ").append(run.totalInputTokens() + run.totalOutputTokens()).append(" tokens");
        if (run.estimatedCostUsd() > 0) {
            line.append(String.format(Locale.ROOT, ", $%.4f", run.estimatedCostUsd()));
        }
        line.append(']');
        if (run.error() != null && !run.error().isBlank()) line.append("  error: ").append(run.error());
        return line.toString();
    }

    private StudioTool.Result getRun(JsonNode args) {
        String runId = Args.require(args, "runId");
        int limit = Args.count(args, "limit", EVENTS_DEFAULT, EVENTS_MAX);
        String agent = Args.str(args, "agent", null);

        RunDetail detail = runs.get(runId);
        RunSummary run = detail.run();
        List<RunEvent> events = detail.events().stream()
                .filter(e -> agent == null || agent.equalsIgnoreCase(e.agent()))
                .toList();
        // The newest lines, still in chronological order: a tail is what says how a run is going,
        // and reversing it to fit the limit would make a model read the story backwards.
        int from = Math.max(0, events.size() - limit);
        List<RunEvent> tail = events.subList(from, events.size());

        StringBuilder out = new StringBuilder(describe(run));
        out.append('\n');
        if (tail.isEmpty()) {
            out.append(agent == null
                    ? "\nNo output yet."
                    : "\nNo output from agent '" + agent + "' yet.");
        } else {
            if (from > 0) out.append("\n… ").append(from).append(" earlier line(s) not shown.\n");
            for (RunEvent e : tail) {
                out.append('\n').append('[').append(e.type()).append(']');
                if (e.agent() != null && !e.agent().isBlank()) out.append(' ').append(e.agent()).append(':');
                out.append(' ').append(e.text());
            }
        }
        return StudioTool.Result.ok(out.toString());
    }

    private StudioTool.Result getRunNodes(JsonNode args) {
        String runId = Args.require(args, "runId");
        String wanted = Args.str(args, "node", null);
        NodeExecReport report = runs.nodes(runId);
        // NodeExec is a mutable class of public fields, not a record — it is written to while a
        // run executes. Read it as fields; do not reach for accessors.
        List<NodeExec> nodes = report.nodes().stream()
                .filter(n -> wanted == null || wanted.equalsIgnoreCase(n.nodeId)
                        || wanted.equalsIgnoreCase(n.label))
                .toList();
        if (nodes.isEmpty()) {
            return StudioTool.Result.ok(wanted == null
                    ? "This execution has produced no per-block detail yet."
                    : "No block matched '" + wanted + "' in this execution.");
        }

        StringBuilder out = new StringBuilder(String.format(Locale.ROOT,
                "%d block(s). Run totals: %d in / %d out tokens, $%.4f.",
                nodes.size(), report.totalInputTokens(), report.totalOutputTokens(),
                report.totalCostUsd()));
        for (NodeExec n : nodes) {
            out.append("\n\n── ").append(n.label).append("  (").append(n.kind)
                    .append(", node ").append(n.nodeId).append(")\n");
            out.append("status: ").append(n.status);
            if (n.model != null && !n.model.isBlank()) out.append("  model: ").append(n.model);
            out.append(String.format(Locale.ROOT, "  tokens: %d in / %d out", n.inputTokens, n.outputTokens));
            if (n.verdict != null) {
                out.append("  verdict: ").append(n.verdict);
                if (n.verdictReason != null && !n.verdictReason.isBlank()) {
                    out.append(" (").append(n.verdictReason).append(')');
                }
            }
            if (n.error != null && !n.error.isBlank()) out.append("\nerror: ").append(n.error);
            if (n.input != null && !n.input.isBlank()) {
                out.append("\ninput:\n").append(Answers.clip(n.input, NODE_TEXT_MAX));
            }
            if (n.output != null && !n.output.isBlank()) {
                out.append("\noutput:\n").append(Answers.clip(n.output, NODE_TEXT_MAX));
            }
        }
        return StudioTool.Result.ok(out.toString());
    }

    private StudioTool.Result getRunFlow(JsonNode args) {
        return StudioTool.Result.ok(Answers.json(mapper, runs.flow(Args.require(args, "runId"))));
    }

    // ---------------------------------------------------------------- driving

    private StudioTool.Result sendCommand(JsonNode args) {
        String runId = Args.require(args, "runId");
        String text = Args.require(args, "text");
        Ready ready = awaitReady(runId);
        if (!ready.ok()) {
            return StudioTool.Result.failed("Nothing was sent: " + ready.detail());
        }
        runs.command(runId, new CommandRequest(text));
        return StudioTool.Result.ok("Sent. The agent is working on it — poll get_run with runId="
                + runId + " for the answer.");
    }

    private StudioTool.Result stopRun(JsonNode args) {
        String runId = Args.require(args, "runId");
        runs.stop(runId);
        return StudioTool.Result.ok("Stopped execution " + runId + ".");
    }

    private StudioTool.Result approveRun(JsonNode args) {
        String runId = Args.require(args, "runId");
        runs.approve(runId);
        return StudioTool.Result.ok("Approved. Execution " + runId
                + " is resuming with permission to act — poll get_run to follow it.");
    }

    private StudioTool.Result rejectRun(JsonNode args) {
        String runId = Args.require(args, "runId");
        runs.reject(runId);
        return StudioTool.Result.ok("Rejected. Execution " + runId + " ends without acting.");
    }

    private StudioTool.Result retryRun(JsonNode args) {
        RunSummary retried = runs.retry(Args.require(args, "runId"));
        return StudioTool.Result.ok("Replaying as a new execution " + retried.id()
                + ". Poll get_run with that id.");
    }

    private FlowGraph read(JsonNode node) {
        try {
            return mapper.treeToValue(node, FlowGraph.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("That is not a valid flow graph: " + e.getMessage()
                    + " Call flow_schema for the format.");
        }
    }
}
