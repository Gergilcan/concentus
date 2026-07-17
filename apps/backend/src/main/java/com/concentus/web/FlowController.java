package com.concentus.web;

import com.concentus.model.FlowGraph;
import com.concentus.model.RunSummary;
import com.concentus.service.RunService;
import com.concentus.service.ScheduleService;
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

/** CRUD for saved flows, plus launching a saved flow. */
@RestController
@RequestMapping("/api/flows")
public class FlowController {

    private final FlowStore store;
    private final RunService runService;
    private final ScheduleService scheduler;

    public FlowController(FlowStore store, RunService runService, ScheduleService scheduler) {
        this.store = store;
        this.runService = runService;
        this.scheduler = scheduler;
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
        scheduler.reschedule(); // pick up new/changed cron triggers
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
