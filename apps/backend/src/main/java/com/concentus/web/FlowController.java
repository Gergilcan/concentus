package com.concentus.web;

import com.concentus.model.FlowGraph;
import com.concentus.model.FlowMemoryView;
import com.concentus.model.FlowVersionInfo;
import com.concentus.model.RunSummary;
import com.concentus.service.RunService;
import com.concentus.mail.MailTriggerService;
import com.concentus.service.ScheduleService;
import com.concentus.store.FlowMemoryStore;
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

    /** How many notes the memory view returns. The UI paginates; agents read far fewer. */
    private static final int MEMORY_VIEW_LIMIT = 100;

    private final FlowStore store;
    private final RunService runService;
    private final ScheduleService scheduler;
    private final MailTriggerService mailTriggers;
    private final FlowVersionStore versions;
    private final FlowMemoryStore memory;

    public FlowController(FlowStore store, RunService runService, ScheduleService scheduler,
                          MailTriggerService mailTriggers, FlowVersionStore versions,
                          FlowMemoryStore memory) {
        this.store = store;
        this.runService = runService;
        this.scheduler = scheduler;
        this.mailTriggers = mailTriggers;
        this.versions = versions;
        this.memory = memory;
    }

    @GetMapping
    public List<FlowGraph> list() {
        return store.list();
    }

    @GetMapping("/{id}")
    public FlowGraph get(@PathVariable String id) {
        return requireFlow(id);
    }

    @PostMapping
    public FlowGraph save(@RequestBody FlowGraph flow) {
        FlowGraph saved = store.save(flow);
        versions.snapshot(saved);  // keep a restorable revision of every save
        rescheduleTriggers();
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
        rescheduleTriggers();
        return saved;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        store.delete(id);
        rescheduleTriggers();
    }

    /** The notes this flow's agents have left for their future runs, newest first. */
    @GetMapping("/{id}/memory")
    public FlowMemoryView memory(@PathVariable String id) {
        return new FlowMemoryView(memory.isAvailable(), memory.count(id),
                memory.latest(id, MEMORY_VIEW_LIMIT));
    }

    /** Wipes the flow's memory. The user's reset button — agents have no tool that does this. */
    @DeleteMapping("/{id}/memory")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearMemory(@PathVariable String id) {
        memory.clear(id);
    }

    @PostMapping("/{id}/run")
    public RunSummary run(@PathVariable String id) {
        return runService.start(requireFlow(id));
    }

    private FlowGraph requireFlow(String id) {
        return store.get(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such flow"));
    }

    /**
     * Both schedulers, after any write: they pick up new, changed and removed cron and mail
     * triggers — including a flow that was merely paused, which is a schedule change too.
     */
    private void rescheduleTriggers() {
        scheduler.reschedule();
        mailTriggers.reschedule();
    }
}
