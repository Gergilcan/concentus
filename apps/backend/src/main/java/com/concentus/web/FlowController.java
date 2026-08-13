package com.concentus.web;

import com.concentus.auth.ConcentusUserDetails;
import com.concentus.auth.OrgContext;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowMemoryView;
import com.concentus.model.FlowVersionInfo;
import com.concentus.model.GoldenStatus;
import com.concentus.service.AgentRun;
import com.concentus.service.GoldenStatusService;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FlowController.class);

    /** How many notes the memory view returns. The UI paginates; agents read far fewer. */
    private static final int MEMORY_VIEW_LIMIT = 100;

    private final FlowStore store;
    private final RunService runService;
    private final ScheduleService scheduler;
    private final MailTriggerService mailTriggers;
    private final FlowVersionStore versions;
    private final FlowMemoryStore memory;
    private final OrgContext orgContext;
    private final com.concentus.service.FlowGenerator generator;
    private final GoldenStatusService goldenStatus;

    public FlowController(FlowStore store, RunService runService, ScheduleService scheduler,
                          MailTriggerService mailTriggers, FlowVersionStore versions,
                          FlowMemoryStore memory, OrgContext orgContext,
                          com.concentus.service.FlowGenerator generator,
                          GoldenStatusService goldenStatus) {
        this.store = store;
        this.runService = runService;
        this.scheduler = scheduler;
        this.mailTriggers = mailTriggers;
        this.versions = versions;
        this.memory = memory;
        this.orgContext = orgContext;
        this.generator = generator;
        this.goldenStatus = goldenStatus;
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
        // Read before writing: whether THIS save changed the graph is the only honest trigger for
        // an automatic golden check. Re-saving an unchanged flow (a tag, a rename, a second click)
        // must not spend an agent run.
        FlowGraph before = flow.id() == null ? null : store.get(flow.id()).orElse(null);
        FlowGraph saved = store.save(flow);
        versions.snapshot(saved, currentAuthor());  // keep a restorable revision of every save
        rescheduleTriggers();
        autoRunGoldenCheck(before, saved);
        return saved;
    }

    /**
     * Replays the golden reference against the flow that was just saved — but only for a flow
     * that asked for it, and only when the save actually changed the graph.
     *
     * <p>Best-effort by design: a check that cannot start (no CLI, budget reached, nothing to
     * replay) must not fail the SAVE. The user's edit is the thing they asked for; the check is
     * the favour, and a favour that eats the request is a bug.
     */
    private void autoRunGoldenCheck(FlowGraph before, FlowGraph saved) {
        AgentRun golden = goldenStatus.autoCheckAfterSave(before, saved).orElse(null);
        if (golden == null) return;
        try {
            runService.startGoldenCheck(saved, golden.id);
        } catch (RuntimeException e) {
            log.warn("Automatic golden check for flow {} did not start: {}", saved.id(), e.getMessage());
        }
    }

    /**
     * Which flows have a golden reference, and whether the flow has changed since it ran.
     *
     * <p>One call for the whole dashboard rather than one per card: the answer is derived from
     * runs already in memory, and a card-by-card endpoint would turn a page render into N requests.
     */
    @GetMapping("/golden-status")
    public List<GoldenStatus> goldenStatus() {
        return goldenStatus.all(store.list());
    }

    /**
     * A flow designed from a sentence, returned WITHOUT being saved.
     *
     * <p>Not saving is the point: the answer arrives on the canvas as a draft with no id, and the
     * user's own save is what makes it exist. A generator that wrote to the store would turn a
     * misunderstood sentence into a flow someone has to go and delete.
     */
    @PostMapping("/generate")
    public FlowGraph generate(@RequestBody GenerateRequest request) {
        return generator.generate(request == null ? null : request.description());
    }

    /** What to automate, in the user's own words. */
    public record GenerateRequest(String description) {
    }

    /** Revision history for a flow (newest first). */
    @GetMapping("/{id}/versions")
    public List<FlowVersionInfo> versions(@PathVariable String id) {
        return versions.list(id);
    }

    /**
     * One earlier revision, as it was, without touching the saved flow — what the Versions tab
     * previews on the canvas. Read-only by virtue of changing nothing: the preview lives in the
     * editor, and saving it is the user restoring it by hand.
     */
    @GetMapping("/{id}/versions/{version}")
    public FlowGraph version(@PathVariable String id, @PathVariable int version) {
        return versions.get(id, version)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such version"))
                .withId(id);
    }

    /** Restores an earlier revision as the current flow (and snapshots it as a new version). */
    @PostMapping("/{id}/versions/{version}/restore")
    public FlowGraph restore(@PathVariable String id, @PathVariable int version) {
        FlowGraph old = versions.get(id, version)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such version"));
        FlowGraph saved = store.save(old.withId(id));
        // Credited to whoever pressed Restore, not to whoever authored the revision being
        // restored: this row records a save that just happened, and the original stays in history
        // with its own author.
        versions.snapshot(saved, currentAuthor());
        rescheduleTriggers();
        return saved;
    }

    /**
     * Who to credit for a save: the signed-in email, or {@code "local"} when authentication is
     * off (single-user desktop install — there is exactly one person and no account to name).
     * Null when auth is on but the request carried no principal, which leaves the revision
     * unsigned rather than attributing it to someone who did not save it.
     */
    private String currentAuthor() {
        return orgContext.currentUser()
                .map(ConcentusUserDetails::email)
                .orElseGet(() -> orgContext.authEnabled() ? null : "local");
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
