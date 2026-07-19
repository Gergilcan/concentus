package com.concentus.web;

import com.concentus.model.CommandRequest;
import com.concentus.model.FlowGraph;
import com.concentus.model.NodeExecReport;
import com.concentus.model.RunDetail;
import com.concentus.model.RunSummary;
import com.concentus.service.AgentRun;
import com.concentus.service.RunService;
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

    public RunController(RunService runService) {
        this.runService = runService;
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
        var nodes = run.nodeExecList();
        if (run.pricing != null) {
            for (var n : nodes) {
                n.estimatedCostUsd = run.pricing.costUsd(
                        n.model, n.inputTokens, n.cacheReadTokens, n.cacheWriteTokens, n.outputTokens);
            }
        }
        return new NodeExecReport(nodes, run.totalInputTokens, run.totalOutputTokens,
                run.estimatedCostUsd());
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
}
