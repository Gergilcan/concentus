package com.concentus.web;

import com.concentus.model.CommandRequest;
import com.concentus.model.FlowGraph;
import com.concentus.model.NodeExecReport;
import com.concentus.model.RunComparison;
import com.concentus.model.RunDetail;
import com.concentus.model.RunPatch;
import com.concentus.model.RunSummary;
import com.concentus.service.AgentRun;
import com.concentus.service.RunDiffService;
import com.concentus.service.RunService;
import com.concentus.store.FlowStore;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final RunDiffService diffs;
    private final com.concentus.auth.OrgContext orgContext;

    public RunController(RunService runService, FlowStore flows, RunDiffService diffs,
                         com.concentus.auth.OrgContext orgContext) {
        this.runService = runService;
        this.flows = flows;
        this.diffs = diffs;
        this.orgContext = orgContext;
    }

    /** The caller's organization's runs. Runs live in one registry for the deployment; this is where it is partitioned. */
    @GetMapping
    public List<RunSummary> list() {
        String mine = orgContext.requireOrganizationId();
        return runService.list().stream()
                .filter(summary -> runService.get(summary.id())
                        .map(run -> mine.equals(run.organizationId)).orElse(false))
                .toList();
    }

    @GetMapping("/{id}")
    public RunDetail get(@PathVariable String id) {
        AgentRun run = requireRun(id);
        return new RunDetail(run.toSummary(), run.bufferedEvents());
    }

    /**
     * The exact flow this run executed (snapshot taken at launch). Lets the UI put the right
     * blocks on the canvas when you open an execution — including ad-hoc runs of unsaved flows,
     * and runs whose flow has since been edited or deleted.
     */
    @GetMapping("/{id}/flow")
    public FlowGraph flow(@PathVariable String id) {
        AgentRun run = requireRun(id);
        return runService.flowOf(run)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "This run has no stored flow snapshot."));
    }

    /**
     * Where this run's path would diverge, walked against the flow as it is saved TODAY.
     *
     * <p>Golden runs compare outputs; debugging needs to compare decisions. The recorded per-block
     * outputs are walked through the current graph's gates without running anything — see
     * {@link com.concentus.service.RunReplay}.
     */
    @GetMapping("/{id}/replay")
    public com.concentus.service.RunReplay.ReplayReport replay(@PathVariable String id) {
        AgentRun run = requireRun(id);
        com.concentus.model.FlowGraph then = runService.flowOf(run)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "This run has no stored flow snapshot."));
        if (run.flowId == null || run.flowId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This run was launched ad hoc — there is no saved flow to replay it against.");
        }
        com.concentus.model.FlowGraph now = flows.get(run.flowId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "The flow this run executed no longer exists."));
        return com.concentus.service.RunReplay.compare(run, then, now);
    }

    /** Per-node execution state (Input/Output, status, per-box tokens) + run token totals. */
    @GetMapping("/{id}/nodes")
    public NodeExecReport nodes(@PathVariable String id) {
        AgentRun run = requireRun(id);
        // Cost is filled in at read time rather than stored, so a pricing change applies to
        // existing runs instead of freezing whatever the rates happened to be when they ran.
        // Graph metrics likewise: derived from the node records on every read, never accrued.
        return new NodeExecReport(run.pricedNodeExecList(), run.totalInputTokens,
                run.totalOutputTokens, run.estimatedCostUsd(), run.graphMetrics());
    }

    /**
     * What the agents did to the repositories: one diff per checkout, read from the working tree
     * now while it exists, from the run's own record when it no longer does. Read-only — the
     * merge step is what pushes; this is what a person reads before trusting that push.
     */
    @GetMapping("/{id}/diffs")
    public List<RunPatch> diffs(@PathVariable String id) {
        return diffs.diffsOf(requireRun(id));
    }

    /** One checkout's diff as a file, for {@code git apply} or a reviewer's own tools. */
    @GetMapping("/{id}/diffs/{nodeId}/{folder}.patch")
    public ResponseEntity<String> patchFile(@PathVariable String id, @PathVariable String nodeId,
                                            @PathVariable String folder) {
        RunPatch patch = diffs.diffOf(requireRun(id), nodeId, folder)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "This run made no such checkout."));
        if (patch.patch() == null) {
            // No file to give, and which kind of none it is: the note says gone or capped;
            // no note means the checkout is there and genuinely unchanged.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    patch.note() != null ? patch.note() : "Nothing changed in this checkout.");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/x-patch;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + RunDiffService.fileNameOf(patch) + "\"")
                .body(patch.patch());
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

    /** Starts this execution again keeping what passed: reused workers, the rest run. */
    @PostMapping("/{id}/resume")
    public RunSummary resume(@PathVariable String id) {
        return runService.resume(id);
    }

    /**
     * Runs ONE block of this execution again, as a new execution, with the input it received —
     * editable first, and optionally carrying the agents it delegates to.
     *
     * <p>Separate from retry because the unit differs: retry repeats a flow, this repeats a block.
     * The expensive thing about tuning a prompt was never the block, it was the flow in front of it.
     */
    @PostMapping("/{id}/nodes/{nodeId}/rerun")
    public RunSummary rerunBlock(@PathVariable String id, @PathVariable String nodeId,
                                 @RequestBody(required = false) BlockRerunRequest req) {
        return runService.rerunBlock(id, nodeId,
                req == null ? null : req.input(),
                req != null && req.downstream(),
                req == null ? null : req.model());
    }

    /**
     * @param input      what to run the block with; null or blank means the input it recorded
     * @param downstream also run the agents this block delegates to
     * @param model      run the block on this model instead of its own; null or blank keeps it.
     *                   Only the block itself moves — the agents it delegates to keep theirs, so
     *                   the comparison is one box against itself rather than two flows
     */
    public record BlockRerunRequest(String input, boolean downstream, String model) {
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
        AgentRun golden = requireRun(id);
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
        // Names the id, unlike requireRun: a comparison has two of them, and "No such run" alone
        // would not say which side is missing.
        AgentRun run = runService.get(id).filter(this::mine)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such run: " + id));
        return new RunComparison.Side(run.toSummary(), run.pricedNodeExecList(), run.finalOutput());
    }

    /**
     * Another organization's run answers exactly as a run that does not exist. The id is not a
     * secret, but it must not be a key either.
     */
    private AgentRun requireRun(String id) {
        return runService.get(id).filter(this::mine)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such run"));
    }

    private boolean mine(AgentRun run) {
        return orgContext.requireOrganizationId().equals(run.organizationId);
    }
}
