package com.concentus.web;

import com.concentus.model.FlowEvalCase;
import com.concentus.model.FlowEvalResult;
import com.concentus.model.FlowGraph;
import com.concentus.service.EvalRunService;
import com.concentus.store.EvalDatasetStore;
import com.concentus.store.EvalResultStore;
import com.concentus.store.FlowStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * A flow's evaluation dataset and the evaluations run against it.
 *
 * <p>Everything is addressed under the flow, and every case and result is checked against that
 * flow before it is returned or removed — an id alone would let one flow's URL read or delete
 * another flow's cases, which the shared resources table cannot prevent by itself.
 */
@RestController
@RequestMapping("/api/flows/{flowId}/evals")
public class EvalController {

    /** Long enough to describe a case; short enough that a whole prompt pasted as a name is refused. */
    private static final int NAME_MAX = 120;

    private final FlowStore flows;
    private final EvalDatasetStore dataset;
    private final EvalResultStore results;
    private final EvalRunService evaluations;

    public EvalController(FlowStore flows, EvalDatasetStore dataset, EvalResultStore results,
                          EvalRunService evaluations) {
        this.flows = flows;
        this.dataset = dataset;
        this.results = results;
        this.evaluations = evaluations;
    }

    @GetMapping("/cases")
    public List<FlowEvalCase> cases(@PathVariable String flowId) {
        requireFlow(flowId);
        return dataset.listForFlow(flowId);
    }

    /**
     * Creates or updates a case. The flow id comes from the path, never from the body, and the
     * creation time of an existing case survives an edit — it is what keeps the list in the order
     * it was written.
     */
    @PostMapping("/cases")
    public FlowEvalCase save(@PathVariable String flowId, @RequestBody FlowEvalCase c) {
        requireFlow(flowId);
        if (c == null || c.name() == null || c.name().isBlank()) {
            throw new IllegalArgumentException("A case needs a name.");
        }
        if (c.name().length() > NAME_MAX) {
            throw new IllegalArgumentException("A case name is at most " + NAME_MAX + " characters.");
        }
        if (c.input() == null || c.input().isBlank()) {
            throw new IllegalArgumentException("A case needs an input — it is what the flow runs with.");
        }
        if (c.expected() == null || c.expected().isBlank()) {
            throw new IllegalArgumentException(
                    "A case needs an expectation — without one it can neither pass nor fail.");
        }
        if (c.judge() == null || !FlowEvalCase.JUDGES.contains(c.judge())) {
            throw new IllegalArgumentException("The judge must be one of: contains, regex, exact, llm.");
        }
        long createdAt = System.currentTimeMillis();
        if (c.id() != null && !c.id().isBlank()) {
            FlowEvalCase existing = dataset.get(c.id()).orElse(null);
            if (existing != null) {
                if (!flowId.equals(existing.flowId())) throw notFound("No such case");
                createdAt = existing.createdAt();
            }
        }
        return dataset.save(new FlowEvalCase(c.id(), flowId, c.name().strip(), c.input(),
                c.expected(), c.judge(), createdAt));
    }

    @DeleteMapping("/cases/{caseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String flowId, @PathVariable String caseId) {
        FlowEvalCase existing = dataset.get(caseId).orElseThrow(() -> notFound("No such case"));
        if (!flowId.equals(existing.flowId())) throw notFound("No such case");
        dataset.delete(caseId);
    }

    /** Starts an evaluation of the flow as saved now and returns it running. Poll {@code /results/{id}}. */
    @PostMapping("/run")
    public FlowEvalResult run(@PathVariable String flowId) {
        return evaluations.start(requireFlow(flowId));
    }

    @GetMapping("/results")
    public List<FlowEvalResult> results(@PathVariable String flowId) {
        requireFlow(flowId);
        return results.listForFlow(flowId);
    }

    @GetMapping("/results/{id}")
    public FlowEvalResult result(@PathVariable String flowId, @PathVariable String id) {
        return results.get(id)
                .filter(r -> flowId.equals(r.flowId()))
                .orElseThrow(() -> notFound("No such evaluation"));
    }

    private FlowGraph requireFlow(String id) {
        return flows.get(id).orElseThrow(() -> notFound("No such flow"));
    }

    private static ResponseStatusException notFound(String what) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, what);
    }
}
