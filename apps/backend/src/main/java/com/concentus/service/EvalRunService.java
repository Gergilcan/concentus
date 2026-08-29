package com.concentus.service;

import com.concentus.model.FlowEvalCase;
import com.concentus.model.FlowEvalCaseResult;
import com.concentus.model.FlowEvalResult;
import com.concentus.model.FlowGraph;
import com.concentus.model.RunEvent;
import com.concentus.model.RunSummary;
import com.concentus.store.EvalDatasetStore;
import com.concentus.store.EvalResultStore;
import com.concentus.store.FlowVersionStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs a flow's evaluation: one execution per case, each judged, all counted against the flow
 * revision they ran.
 *
 * <p>Sequential, on one thread of its own. An evaluation is ten real agent runs, and starting
 * them all at once would take the whole run pool from the flows people are actually operating —
 * the queue then refuses the next webhook. One case at a time is slower and is also the shape a
 * measurement should have: every case runs under the same load as the last.
 *
 * <p>Nothing here executes anything itself. Each case goes through {@link RunService#start} like
 * a person pressing Run, so it is priced, budgeted, persisted and visible in the executions list
 * exactly as any other run — and a failure can be opened and read there.
 */
@Service
public class EvalRunService {

    private static final Logger log = LoggerFactory.getLogger(EvalRunService.class);
    /** How often a case's run is looked at. A second is invisible next to a run that takes minutes. */
    private static final long DEFAULT_POLL_MILLIS = 1_000;
    /** After this a case is failed and its run stopped: an evaluation that never ends measures nothing. */
    private static final long DEFAULT_CASE_TIMEOUT_MILLIS = 30 * 60 * 1_000;
    /** How much of the final answer a result keeps. The whole answer stays on the run. */
    private static final int OUTPUT_EXCERPT_CHARS = 600;
    /** A run in one of these has said its last word; anything else is still going. */
    private static final Set<String> SETTLED = Set.of(
            "COMPLETED", "ERROR", "TERMINATED", "AWAITING_ANSWER", "AWAITING_APPROVAL");

    private final EvalDatasetStore dataset;
    private final EvalResultStore results;
    private final RunService runs;
    private final EvalJudge judge;
    private final FlowVersionStore versions;
    private final long pollMillis;
    private final long caseTimeoutMillis;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "eval-runner");
        t.setDaemon(true);
        return t;
    });

    // @Autowired because a second (test) constructor exists — without it Spring refuses the bean.
    @Autowired
    public EvalRunService(EvalDatasetStore dataset, EvalResultStore results, RunService runs,
                          EvalJudge judge, FlowVersionStore versions) {
        this(dataset, results, runs, judge, versions, DEFAULT_POLL_MILLIS, DEFAULT_CASE_TIMEOUT_MILLIS);
    }

    EvalRunService(EvalDatasetStore dataset, EvalResultStore results, RunService runs,
                   EvalJudge judge, FlowVersionStore versions, long pollMillis, long caseTimeoutMillis) {
        this.dataset = dataset;
        this.results = results;
        this.runs = runs;
        this.judge = judge;
        this.versions = versions;
        this.pollMillis = pollMillis;
        this.caseTimeoutMillis = caseTimeoutMillis;
    }

    @PreDestroy
    public void shutdown() {
        worker.shutdownNow();
    }

    /**
     * Starts an evaluation and returns it at once, {@code running} with no cases judged yet.
     *
     * <p>Returned rather than awaited because the work is minutes long and the caller is an HTTP
     * request: the id is what the UI polls. Stored before the first run starts so a poll can
     * never miss it.
     *
     * @throws IllegalArgumentException when the flow has no cases — a score over nothing is not 0
     */
    public FlowEvalResult start(FlowGraph flow) {
        List<FlowEvalCase> cases = dataset.listForFlow(flow.id());
        if (cases.isEmpty()) {
            throw new IllegalArgumentException(
                    "This flow has no evaluation cases yet. Add at least one before running an evaluation.");
        }
        FlowEvalResult pending = results.save(new FlowEvalResult(null, flow.id(),
                versions.currentVersion(flow.id()), System.currentTimeMillis(), null,
                FlowEvalResult.RUNNING, List.of(), 0, cases.size()));
        worker.submit(() -> {
            try {
                evaluate(pending, flow, cases);
            } catch (RuntimeException e) {
                // The result must not stay "running" forever: an evaluation the UI keeps polling
                // for is worse than one that says it broke.
                log.error("Evaluation {} of flow {} stopped on an unexpected error.", pending.id(), flow.id(), e);
                results.save(new FlowEvalResult(pending.id(), pending.flowId(), pending.flowVersion(),
                        pending.startedAt(), System.currentTimeMillis(), FlowEvalResult.DONE,
                        pending.cases(), pending.passed(), pending.total()));
            }
        });
        return pending;
    }

    /**
     * Runs every case in order, saving the result after each so progress is visible, and marks it
     * done at the end. Package-private so a test can run it on its own thread and read the
     * outcome without a latch.
     */
    void evaluate(FlowEvalResult pending, FlowGraph flow, List<FlowEvalCase> cases) {
        List<FlowEvalCaseResult> judged = new ArrayList<>();
        int passed = 0;
        for (FlowEvalCase c : cases) {
            FlowEvalCaseResult outcome = runCase(flow, c);
            judged.add(outcome);
            if (outcome.passed()) passed++;
            results.save(new FlowEvalResult(pending.id(), pending.flowId(), pending.flowVersion(),
                    pending.startedAt(), null, FlowEvalResult.RUNNING, List.copyOf(judged), passed,
                    cases.size()));
        }
        results.save(new FlowEvalResult(pending.id(), pending.flowId(), pending.flowVersion(),
                pending.startedAt(), System.currentTimeMillis(), FlowEvalResult.DONE,
                List.copyOf(judged), passed, cases.size()));
    }

    /**
     * One case: start its run, wait for the run to settle, judge what it said.
     *
     * <p>A run that could not start — the pool refused it, the budget is spent, the flow does not
     * compile — is a failed case with that reason, not a crashed evaluation. The other nine cases
     * are still worth running, and "8/10, one could not start" is a truer score than no score.
     */
    private FlowEvalCaseResult runCase(FlowGraph flow, FlowEvalCase c) {
        String runId;
        try {
            RunSummary started = runs.start(flow, c.input());
            runId = started.id();
        } catch (RejectedExecutionException e) {
            return failed(c, null, "The run could not start: the run queue is full.");
        } catch (RuntimeException e) {
            return failed(c, null, "The run could not start: " + reason(e));
        }
        runs.get(runId).ifPresent(run -> {
            // Labelled in the executions list for what it is, like a golden check is.
            run.trigger = "eval";
            run.emit(RunEvent.of("system", "Started by an evaluation, case '" + c.name() + "'."));
        });

        AgentRun run = awaitSettled(runId);
        if (run == null) {
            return failed(c, runId, "The run vanished from the registry before it finished.");
        }
        if (!SETTLED.contains(run.status)) {
            try {
                runs.stop(runId);
            } catch (RuntimeException e) {
                log.debug("Could not stop timed-out evaluation run {}: {}", runId, e.getMessage());
            }
            return failed(c, runId, "The run did not finish within " + (caseTimeoutMillis / 60_000)
                    + " minutes and was stopped.");
        }
        switch (run.status) {
            case "ERROR" -> {
                return failed(c, runId, "The run failed: " + (run.error == null ? "no reason recorded" : run.error));
            }
            case "TERMINATED" -> {
                return failed(c, runId, "The run was stopped before it finished.");
            }
            case "AWAITING_APPROVAL" -> {
                return failed(c, runId, "The run stopped to ask for approval. An evaluation runs "
                        + "unattended, so nobody answered.");
            }
            default -> {
                String output = run.finalOutput();
                EvalJudge.Verdict verdict = judge.judge(c, output);
                return new FlowEvalCaseResult(c.id(), c.name(), runId, verdict.passed(), verdict.why(),
                        excerpt(output));
            }
        }
    }

    /**
     * The run once it has settled, or as it stands when the timeout runs out; null when the
     * registry no longer has it. Polling rather than a listener because the run service keeps no
     * completion future to wait on, and a listener on the run would fire on every line.
     */
    private AgentRun awaitSettled(String runId) {
        long deadline = System.currentTimeMillis() + caseTimeoutMillis;
        while (true) {
            AgentRun run = runs.get(runId).orElse(null);
            if (run == null) return null;
            if (SETTLED.contains(run.status) || System.currentTimeMillis() >= deadline) return run;
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return run;
            }
        }
    }

    private static FlowEvalCaseResult failed(FlowEvalCase c, String runId, String why) {
        return new FlowEvalCaseResult(c.id(), c.name(), runId, false, why, null);
    }

    private static String reason(RuntimeException e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String excerpt(String output) {
        if (output == null) return null;
        String one = output.strip();
        return one.length() > OUTPUT_EXCERPT_CHARS ? one.substring(0, OUTPUT_EXCERPT_CHARS) + "…" : one;
    }
}
