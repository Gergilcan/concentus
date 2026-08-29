package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.model.FlowGraph;
import com.concentus.model.RunSummary;
import com.concentus.store.FlowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * One flow running another: the guards, the wait, and the answer.
 *
 * <p>This is the first thing in the product that starts work without a person asking for it, so it
 * is also the first that could start work forever. Two flows pointing at each other draw a loop
 * that is invisible on either canvas — each looks like a perfectly ordinary graph — and the only
 * place with enough context to see it is here, where the chain of ancestors travels with the run.
 *
 * <p>What a child does NOT inherit is as deliberate as what it does. Its own budget, because a
 * ceiling a parent can spend through is not a ceiling. Its own permission mode, because a flow in
 * plan mode must not act through a child that has bypass. What it does inherit is the chain, which
 * is what makes the guards possible at all.
 */
@Service
public class SubflowService {

    private static final Logger log = LoggerFactory.getLogger(SubflowService.class);

    /** How often the wait re-reads the child's status. Cheap: it is an in-memory lookup. */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    private final FlowStore flows;
    /**
     * Lazily, because {@link RunService} reaches back here to fire a flow's hand-offs when a run
     * ends. A constructor-injected pair would be a cycle Spring refuses to build.
     */
    private final Supplier<RunService> runs;
    private final int maxDepth;

    // @Autowired is load-bearing: the test constructor below is a second one, and Spring faced
    // with two picks neither — "No default constructor found", three times in this repo already.
    @Autowired
    public SubflowService(FlowStore flows, ObjectProvider<RunService> runs,
                          @Value("${subflow.max-depth:3}") int maxDepth) {
        this(flows, runs::getObject, maxDepth);
    }

    /** For tests: the supplier without Spring's provider. */
    SubflowService(FlowStore flows, Supplier<RunService> runs, int maxDepth) {
        this.flows = flows;
        this.runs = runs;
        this.maxDepth = maxDepth;
    }

    /**
     * @param started false means nothing was launched — {@code error} says why, in words meant for
     *                whoever reads the run log
     * @param output  the child's closing answer, present only when the caller waited for it
     */
    public record Result(boolean started, String runId, String status, String output, String error) {

        static Result refused(String error) {
            return new Result(false, null, null, null, error);
        }
    }

    /**
     * Starts {@code spec}'s flow with {@code prompt}, waiting for its answer when asked to.
     *
     * @param timeout how long to wait before handing back "still running" instead of blocking
     *                further — the caller is usually an agent's tool call, and a tool that never
     *                returns is worse than one that reports an unfinished job
     */
    public Result run(AgentRun parent, AgentSpec.SubflowSpec spec, String prompt, Duration timeout) {
        List<String> chain = chainOf(parent);

        if (chain.size() >= maxDepth) {
            return Result.refused("Sub-flows are already " + chain.size() + " deep, which is as "
                    + "far as they go. Chain: " + String.join(" → ", chain) + ".");
        }
        if (chain.contains(spec.flowId)) {
            return Result.refused("That flow is already running further up this chain, so running "
                    + "it again would loop forever. Chain: " + String.join(" → ", chain) + ".");
        }

        Optional<FlowGraph> child = flows.getIn(parent.organizationId, spec.flowId);
        if (child.isEmpty()) {
            return Result.refused("The flow '" + spec.label + "' no longer exists (id "
                    + spec.flowId + "). Point the node at another one.");
        }

        RunSummary started;
        try {
            // Its own budget, its own permission mode, its own everything except the chain.
            started = runs.get().startSubflow(child.get(), prompt, chain);
        } catch (RuntimeException e) {
            // Typically the child's own budget ceiling. Passed through verbatim: the child's
            // reason is the true one, and rewording it here would hide which flow refused.
            return Result.refused(e.getMessage() == null ? "The flow could not be started." : e.getMessage());
        }

        log.info("Run {} started sub-flow '{}' as run {} (chain: {})",
                parent.id, spec.label, started.id(), String.join(" → ", chain));

        if (!spec.waitForResult) {
            return new Result(true, started.id(), started.status(), null, null);
        }
        return await(started.id(), timeout);
    }

    /** The default wait, for callers with no opinion. */
    public Result run(AgentRun parent, AgentSpec.SubflowSpec spec, String prompt) {
        return run(parent, spec, prompt, Duration.ofMinutes(10));
    }

    /**
     * Fires the flow nodes drawn terminal, once the run they belong to has finished.
     *
     * <p>Only after COMPLETED, and that is the whole rule. A hand-off exists because the first
     * flow's answer is the second one's reason: chaining after a failure would pass on an answer
     * that does not exist, and chaining after a stop would act on work somebody deliberately
     * halted. Both are said in the run's own log rather than passed over in silence.
     *
     * <p>Never waits, whatever the node says. The waiting flag belongs to the tool call, where an
     * agent asked a question and needs the answer; here the run is over and there is nobody left
     * to hand a result to.
     */
    public void handOffAfter(AgentRun run) {
        handOffAfter(run, null);
    }

    /**
     * As above, with the run's own graph so the condition and for-each nodes drawn on the way to
     * each hand-off can be applied. A null graph means no gates — every hand-off fires once, which
     * is what a flow drawn before gates existed does.
     */
    public void handOffAfter(AgentRun run, com.concentus.model.FlowGraph graph) {
        handOff(run, graph, null);
    }

    /**
     * The recovery branches of ONE block, the moment it settled — a worker that just failed, the
     * verifier that just gave its final word — without waiting for the rest of the run.
     *
     * <p>The mail saying "the Ads worker was rejected" is worth more before the merge than after
     * it; and a run with no error branch drawn loses nothing, because nothing fires here that
     * {@link #handOffAfter} would not have fired later. What fired is remembered on the run, so
     * the end of the run does not start it a second time.
     */
    public void handOffMidRun(AgentRun run, com.concentus.model.FlowGraph graph, String settledNodeId) {
        if (graph == null || settledNodeId == null) return;
        handOff(run, graph, settledNodeId);
    }

    private void handOff(AgentRun run, com.concentus.model.FlowGraph graph, String onlyFrom) {
        if (run.compiled == null || run.compiled.afterFlows().isEmpty()) return;
        boolean finalCall = onlyFrom == null;
        if (finalCall && run.handOffsFired) return;

        // A failed run used to start nothing at all, which is what made a failure something you
        // found out about afterwards. Now the wires say which is which, and WHOSE: a branch on
        // the main output runs when the flow worked; one on a block's error output runs when
        // THAT block failed — a worker that crashed while the others carried the run to
        // completion still fires its own branch; one on the verifier's rejected output runs when
        // the verifier's final word on any worker was a rejection.
        // Mid-run the run is still going, so nothing here reads as "the run failed": only the
        // settled block's own branches are considered, on that block's own facts. At the end, a
        // failure the other family of hand-off (mail) already handled — and turned green on the
        // way past — is still a failure the wires here were drawn for.
        boolean failed = finalCall && (!"COMPLETED".equals(run.status) || run.failureHandled);
        // A failure nobody pinned on a block — "every worker failed", a budget — belongs to the
        // coordinator, the block that stands for the run.
        boolean unattributed = failed && run.failedNodeLabel() == null;
        List<AgentSpec.SubflowSpec> toRun = new ArrayList<>();
        java.util.Map<String, String> payloads = new java.util.LinkedHashMap<>();
        boolean recoveryFired = false;
        for (AgentSpec.SubflowSpec drawn : run.compiled.afterFlows()) {
            if (run.firedHandOffs.contains(drawn.nodeId)) continue;
            FlowGates.Origin origin = graph == null
                    ? new FlowGates.Origin(null, null)
                    : FlowGates.originOf(graph, drawn.nodeId);
            if (!finalCall && (origin.onMain() || !onlyFrom.equals(origin.sourceId()))) continue;
            String payload = null;
            if (origin.onMain()) {
                // A run that completed saying nothing still hands off: the branch was drawn to
                // run when this one finishes, and it did.
                if (!failed) payload = run.finalOutput() == null ? "" : run.finalOutput();
            } else if (origin.is(com.concentus.model.FlowEdge.ERROR)) {
                boolean blockFailed = run.nodeFailed(origin.sourceId())
                        || (unattributed && isCoordinator(run, graph, origin.sourceId()));
                if (blockFailed) payload = BranchPayloads.errorOf(run, origin.sourceId());
            } else if (origin.is(com.concentus.model.FlowEdge.REJECTED)) {
                if (run.anyRejected()) payload = BranchPayloads.verificationReport(run);
            }
            if (payload == null) continue;
            toRun.add(drawn);
            payloads.put(drawn.nodeId, payload);
            if (!origin.onMain()) recoveryFired = true;
        }

        if (toRun.isEmpty()) {
            if (failed && !run.recoveryFired && !run.failureHandled) {
                String where = run.failedNodeLabel();
                run.emit(com.concentus.model.RunEvent.of("system", (where == null
                        ? "This run did not complete, and nothing is wired to its coordinator's error output"
                        : "'" + where + "' failed and nothing is wired to its error output")
                        + ", so none of its " + run.compiled.afterFlows().size()
                        + " hand-off(s) were started."));
            }
            if (finalCall) settle(run, failed);
            return;
        }

        if (finalCall) run.handOffsFired = true;
        if (recoveryFired) run.recoveryFired = true;
        for (AgentSpec.SubflowSpec drawn : toRun) {
            run.firedHandOffs.add(drawn.nodeId);
            String output = payloads.get(drawn.nodeId);
            FlowGates.Decision decision = graph == null
                    ? new FlowGates.Decision(List.of(output == null ? "" : output), null)
                    : FlowGates.decide(graph, drawn.nodeId, output);
            // Said in the log whichever way it went. A branch that silently did not fire is
            // indistinguishable from a branch nobody drew, and the flow looks like it worked.
            if (decision.reason() != null) {
                run.emit(com.concentus.model.RunEvent.of("system",
                        "Hand-off '" + drawn.label + "': " + decision.reason()));
            }
            if (!decision.fires()) continue;

            for (String prompt : decision.prompts()) {
                AgentSpec.SubflowSpec handOff = new AgentSpec.SubflowSpec();
                handOff.nodeId = drawn.nodeId;
                handOff.label = drawn.label;
                handOff.flowId = drawn.flowId;
                handOff.waitForResult = false;
                Result result = run(run, handOff, prompt);
                run.emit(com.concentus.model.RunEvent.of("system", result.started()
                        ? "Hand-off '" + drawn.label + "' started as run " + result.runId() + "."
                        : "Hand-off '" + drawn.label + "' was not started: " + result.error()));
                // One refusal ends the branch: the reason a hand-off cannot start (depth, a
                // missing flow, a budget) is the same for the next item, so continuing would only
                // write the same line twenty-five times.
                if (!result.started()) break;
            }
        }

        if (finalCall) settle(run, failed);
    }

    /**
     * Handled, and therefore not a failure any more. Somebody drew what should happen when this
     * goes wrong and it happened; leaving the run red would report an unattended failure and put
     * a flow in the drifted column for working as designed. A rejected branch counts: a run that
     * failed because the verifier rejected everything, with a branch drawn for exactly that, did
     * what it was drawn to do. A branch that fired mid-run counts the same — the run remembers,
     * and so does the mail hand-off, which reads the same flag.
     */
    private static void settle(AgentRun run, boolean failed) {
        if (failed && run.recoveryFired) {
            run.failureHandled = true;
            if ("COMPLETED".equals(run.status)) return;
            run.status = "COMPLETED";
            run.emit(com.concentus.model.RunEvent.of("system",
                    "The failure was handled by the branch wired to the failing block's second "
                            + "output, so this run is reported as completed. The failure itself is above."));
        }
    }

    /**
     * Whether a block is the run's coordinator — by the compiled spec, or by the role drawn on the
     * canvas, because a run built by a test or an older path may have compiled without ids.
     */
    private static boolean isCoordinator(AgentRun run, com.concentus.model.FlowGraph graph, String nodeId) {
        if (nodeId == null) return false;
        if (run.compiled != null && run.compiled.coordinator() != null
                && nodeId.equals(run.compiled.coordinator().nodeId)) {
            return true;
        }
        if (graph == null) return false;
        for (com.concentus.model.FlowNode n : graph.nodesOrEmpty()) {
            if (nodeId.equals(n.id())) return "coordinator".equalsIgnoreCase(n.role());
        }
        return false;
    }

    /**
     * The ancestors of a run, oldest first, with the run's own flow appended.
     *
     * <p>Built from the parent rather than stored globally, so a run started by hand carries an
     * empty chain and a run started by a sub-flow carries everything above it.
     */
    private static List<String> chainOf(AgentRun parent) {
        List<String> chain = new ArrayList<>(parent.flowChain == null ? List.of() : parent.flowChain);
        if (parent.flowId != null && !parent.flowId.isBlank() && !chain.contains(parent.flowId)) {
            chain.add(parent.flowId);
        }
        return chain;
    }

    /**
     * Waits for a run to reach a terminal state.
     *
     * <p>On timeout the result is honest rather than false: the child is still running, its id is
     * returned, and the caller decides what to do. Reporting an unfinished job as finished is the
     * one outcome that would make an agent write a confident report about work that never happened.
     */
    private Result await(String runId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            AgentRun run = runs.get().get(runId).orElse(null);
            if (run == null) {
                return new Result(true, runId, "GONE", null,
                        "The run disappeared before it finished — the backend may have restarted.");
            }
            if (isTerminal(run.status)) {
                String output = run.finalOutput();
                return new Result(true, runId, run.status, output,
                        "ERROR".equals(run.status) ? run.error : null);
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result(true, runId, "INTERRUPTED", null, "The wait was interrupted.");
            }
        }
        return new Result(true, runId, "RUNNING", null,
                "The flow is still running after " + timeout.toMinutes() + " minutes. It carries "
                        + "on by itself; check run " + runId + " for the result.");
    }

    /** Mirrors RunService's own classification: anything not in flight. */
    private static boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "ERROR".equals(status) || "TERMINATED".equals(status);
    }
}
