package com.concentus.web;

import com.concentus.model.CommandRequest;
import com.concentus.model.FlowGraph;
import com.concentus.model.NodeExecReport;
import com.concentus.model.RunComparison;
import com.concentus.model.RunDetail;
import com.concentus.model.RunSummary;
import com.concentus.service.AgentRun;
import com.concentus.service.RunService;
import com.concentus.store.FlowStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Runs: list, detail (with buffered output), start ad-hoc, send command, stop. */
@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunService runService;
    private final FlowStore flows;

    public RunController(RunService runService, FlowStore flows) {
        this.runService = runService;
        this.flows = flows;
    }

    @GetMapping
    public List<RunSummary> list() {
        return runService.list();
    }

    @GetMapping("/{id}")
    public RunDetail get(@PathVariable String id) {
        AgentRun run = runService.get(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such run"));
        return new RunDetail(run.toSummary(), run.bufferedEvents());
    }

    /**
     * The exact flow this run executed (snapshot taken at launch). Lets the UI put the right
     * blocks on the canvas when you open an execution — including ad-hoc runs of unsaved flows,
     * and runs whose flow has since been edited or deleted.
     */
    @GetMapping("/{id}/flow")
    public FlowGraph flow(@PathVariable String id) {
        AgentRun run = runService.get(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such run"));
        return runService.flowOf(run)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "This run has no stored flow snapshot."));
    }

    /** Per-node execution state (Input/Output, status, per-box tokens) + run token totals. */
    @GetMapping("/{id}/nodes")
    public NodeExecReport nodes(@PathVariable String id) {
        AgentRun run = runService.get(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such run"));
        // Cost is filled in at read time rather than stored, so a pricing change applies to
        // existing runs instead of freezing whatever the rates happened to be when they ran.
        // Graph metrics likewise: derived from the node records on every read, never accrued.
        return new NodeExecReport(run.pricedNodeExecList(), run.totalInputTokens,
                run.totalOutputTokens, run.estimatedCostUsd(), run.graphMetrics());
    }

    /** Launch an ad-hoc (unsaved) flow. */
    @PostMapping
    public RunSummary start(@RequestBody FlowGraph flow) {
        return runService.start(flow);
    }

    @PostMapping("/{id}/commands")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void command(@PathVariable String id, @RequestBody CommandRequest req) {
        if (req == null || req.text() == null || req.text().isBlank()) {
            throw new IllegalArgumentException("Command text is required.");
        }
        runService.sendCommand(id, req.text());
    }

    @PostMapping("/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@PathVariable String id) {
        runService.approve(id);
    }

    @PostMapping("/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@PathVariable String id) {
        runService.reject(id);
    }

    @PostMapping("/{id}/stop")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stop(@PathVariable String id) {
        runService.stop(id);
    }

    /** Re-runs this execution's flow with the same initial input, as a new execution. */
    @PostMapping("/{id}/retry")
    public RunSummary retry(@PathVariable String id) {
        return runService.retry(id);
    }

    /** Marks (or unmarks) this run as its flow's golden reference. One per flow. */
    @PostMapping("/{id}/golden")
    public RunSummary setGolden(@PathVariable String id, @RequestBody GoldenRequest req) {
        return runService.setGolden(id, req != null && req.golden());
    }

    public record GoldenRequest(boolean golden) {
    }

    /**
     * Replays the golden run's first input against the flow as it is saved NOW, as a new run.
     *
     * <p>Unlike retry (same input, same flow snapshot — reproduce), this is same input, current
     * flow — see what your edits changed before trusting them. Which is also why it refuses when
     * the flow no longer exists: with nothing current to run against, a "golden check" could only
     * repeat the past and claim it tested the present.
     */
    @PostMapping("/{id}/golden/rerun")
    public RunSummary goldenRerun(@PathVariable String id) {
        AgentRun golden = runService.get(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such run"));
        if (golden.flowId == null || golden.flowId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This run belongs to no saved flow, so there is no current flow to test.");
        }
        FlowGraph current = flows.get(golden.flowId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "The flow this run belongs to no longer exists."));
        return runService.startGoldenCheck(current, id);
    }

    /**
     * The golden reference and a candidate run, side by side: headline numbers, per-node steps,
     * and each run's final answer. Cost is priced at read time like {@code /nodes}, so both sides
     * are priced by the same current table rather than whatever rates each run saw.
     */
    @GetMapping("/{id}/compare/{otherId}")
    public RunComparison compare(@PathVariable String id, @PathVariable String otherId) {
        return new RunComparison(side(id), side(otherId));
    }

    private RunComparison.Side side(String id) {
        AgentRun run = runService.get(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such run: " + id));
        return new RunComparison.Side(run.toSummary(), run.pricedNodeExecList(), run.finalOutput());
    }
}
