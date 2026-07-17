package com.concentus.web;

import com.concentus.model.CommandRequest;
import com.concentus.model.FlowGraph;
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
}
