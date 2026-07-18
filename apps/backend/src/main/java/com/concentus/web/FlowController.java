package com.concentus.web;

import com.concentus.model.FlowGraph;
import com.concentus.model.FlowVersionInfo;
import com.concentus.model.RunSummary;
import com.concentus.service.RunService;
import com.concentus.service.ScheduleService;
import com.concentus.store.FlowStore;
import com.concentus.store.FlowVersionStore;
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

/** CRUD for saved flows, plus launching a saved flow. */
@RestController
@RequestMapping("/api/flows")
public class FlowController {

    private final FlowStore store;
    private final RunService runService;
    private final ScheduleService scheduler;
    private final FlowVersionStore versions;

    public FlowController(FlowStore store, RunService runService, ScheduleService scheduler,
                          FlowVersionStore versions) {
        this.store = store;
        this.runService = runService;
        this.scheduler = scheduler;
        this.versions = versions;
    }

    @GetMapping
    public List<FlowGraph> list() {
        return store.list();
    }

    @GetMapping("/{id}")
    public FlowGraph get(@PathVariable String id) {
        return store.get(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such flow"));
    }

    @PostMapping
    public FlowGraph save(@RequestBody FlowGraph flow) {
        FlowGraph saved = store.save(flow);
        versions.snapshot(saved);  // keep a restorable revision of every save
        scheduler.reschedule();    // pick up new/changed cron triggers (and pauses)
        return saved;
    }

    /** Revision history for a flow (newest first). */
    @GetMapping("/{id}/versions")
    public List<FlowVersionInfo> versions(@PathVariable String id) {
        return versions.list(id);
    }

    /** Restores an earlier revision as the current flow (and snapshots it as a new version). */
    @PostMapping("/{id}/versions/{version}/restore")
    public FlowGraph restore(@PathVariable String id, @PathVariable int version) {
        FlowGraph old = versions.get(id, version)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such version"));
        FlowGraph saved = store.save(old.withId(id));
        versions.snapshot(saved);
        scheduler.reschedule();
        return saved;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        store.delete(id);
        scheduler.reschedule();
    }

    @PostMapping("/{id}/run")
    public RunSummary run(@PathVariable String id) {
        FlowGraph flow = store.get(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such flow"));
        return runService.start(flow);
    }
}
