package com.concentus.service;

import com.concentus.integration.UntrustedContent;
import com.concentus.model.FlowGraph;
import com.concentus.model.FolderWatchState;
import com.concentus.model.RunSummary;
import com.concentus.model.TriggerSpec;
import com.concentus.store.FlowStore;
import com.concentus.store.FolderWatchStateStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Stream;

/**
 * Starts runs when files appear or change in a host folder a flow watches.
 *
 * <p>The folder counterpart of {@code MailTriggerService}: registered from the saved flows on
 * startup and rebuilt whenever a flow is saved or deleted, on the same scheduler mechanism.
 *
 * <p>Polling with a listing, not {@code java.nio.file.WatchService}. The native watcher is what
 * one reaches for first, and it is wrong for the folders people actually point this at: it does
 * not work on network shares at all, it reports a rename on Windows as a delete and a create, and
 * it delivers a burst of events for a single file being copied in. A listing every few seconds
 * with last-modified times sees the same folder the person sees, wherever it is mounted.
 *
 * <p>Debounced, because the interesting change is rarely one file. Someone drops a batch of PDFs,
 * or a scanner writes a file in several chunks; firing on the first tick that saw something would
 * start a run on half a batch, and then another on the rest. Changes are held until the folder has
 * been quiet for the node's debounce window, and become one run with the whole list.
 *
 * <p>The folder is checked against {@code local.context-roots} on every poll, the same allowlist
 * that guards agents' context folders — a flow is editable over HTTP, so an unchecked path here
 * would be a way to learn which files exist anywhere on the host.
 */
@Service
public class FolderWatchService {

    private static final Logger log = LoggerFactory.getLogger(FolderWatchService.class);

    /** How deep a listing descends. A watched folder is a drop zone, not a whole drive. */
    static final int MAX_DEPTH = 8;
    /** Past this many files the listing stops; a folder that big is not a drop zone either. */
    static final int MAX_FILES = 20_000;
    /** How many changed paths the prompt lists in full before summarising the rest. */
    static final int MAX_LISTED_PATHS = 200;

    private final FlowStore flows;
    private final RunService runService;
    private final ContextFolderResolver roots;
    private final FolderWatchStateStore states;
    private final Clock clock;
    private final boolean enabled;
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final Map<String, ScheduledFuture<?>> jobs = new HashMap<>();
    /** What each flow's folder looked like at the last poll, and what changed since it last fired. */
    private final Map<String, Watched> watched = new ConcurrentHashMap<>();

    /** One flow's memory between polls. Confined to the scheduler thread that polls that flow. */
    static final class Watched {
        String path = "";
        String glob = "";
        /** Path → last-modified millis, as of the previous poll. */
        Map<String, Long> snapshot = Map.of();
        /** Changed or added since the last run fired, in the order they were noticed. */
        final Set<String> pending = new LinkedHashSet<>();
        /** When something last changed — what the debounce is measured from. */
        long lastChangeAt;
        /** False until the first listing since startup (or since the path changed) is taken. */
        boolean primed;
    }

    @Autowired
    public FolderWatchService(FlowStore flows, RunService runService, ContextFolderResolver roots,
                              FolderWatchStateStore states,
                              @Value("${watch.triggers-enabled:true}") boolean enabled) {
        this(flows, runService, roots, states, enabled, Clock.systemUTC());
    }

    /** With a clock the tests can move, so the debounce is checked without waiting it out. */
    FolderWatchService(FlowStore flows, RunService runService, ContextFolderResolver roots,
                       FolderWatchStateStore states, boolean enabled, Clock clock) {
        this.flows = flows;
        this.runService = runService;
        this.roots = roots;
        this.states = states;
        this.enabled = enabled;
        this.clock = clock;
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("folder-watch-");
        scheduler.initialize();
    }

    /** Cancels every watcher and re-registers from the current set of saved flows. Idempotent. */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void reschedule() {
        jobs.values().forEach(f -> f.cancel(false));
        jobs.clear();
        if (!enabled) {
            log.info("Folder-watch triggers disabled (watch.triggers-enabled=false).");
            return;
        }
        for (FlowGraph flow : flows.listAcrossOrganizations()) {
            if (flow.id() == null) continue;
            TriggerSpec spec = TriggerSpec.from(flow);
            if (!spec.watch()) continue;
            if (!flow.enabledOrDefault()) {
                log.info("Flow '{}' is paused — not watching its folder.", flow.name());
                continue;
            }
            if (spec.watchPath().isBlank()) {
                // A trigger with no folder yet is a work in progress, not a mistake.
                log.info("Flow '{}' has a folder-watch trigger with no folder yet. Not watching.",
                        flow.name());
                continue;
            }
            String reason = roots.containmentReason(spec.watchPath());
            if (reason != null) {
                log.warn("Flow '{}' watches '{}', which cannot be used: {}. Not watching.",
                        flow.name(), spec.watchPath(), reason);
                continue;
            }
            String flowId = flow.id();
            // Polled at the debounce interval: a change noticed on one tick fires on the next
            // one that finds the folder quiet, which is exactly one debounce window later.
            ScheduledFuture<?> job = scheduler.scheduleWithFixedDelay(
                    () -> safely(flowId, () -> poll(flowId)),
                    Duration.ofSeconds(spec.watchDebounceSeconds()));
            jobs.put(flowId, job);
            log.info("Watching {} for flow '{}' every {}s{}.", spec.watchPath(), flow.name(),
                    spec.watchDebounceSeconds(),
                    spec.watchGlob().isBlank() ? "" : " (" + spec.watchGlob() + ")");
        }
    }

    /** One look at one flow's folder. Package-private so tests can drive it deterministically. */
    void poll(String flowId) {
        FlowGraph flow = flows.getAcrossOrganizations(flowId).orElse(null);
        if (flow == null || !flow.enabledOrDefault()) return;
        TriggerSpec spec = TriggerSpec.from(flow);
        if (!spec.watch() || spec.watchPath().isBlank()) return;

        // Re-checked every poll, not once at registration: the allowlist judges the REAL path,
        // and a folder that is created — or replaced by a symlink — after registration must be
        // judged as it is now.
        String reason = roots.rejectionReason(spec.watchPath());
        if (reason != null) {
            if ("not an existing directory".equals(reason)) {
                log.debug("Flow '{}': waiting for {} to exist.", flow.name(), spec.watchPath());
            } else {
                log.warn("Flow '{}': not watching {} — {}.", flow.name(), spec.watchPath(), reason);
            }
            return;
        }
        Path dir = Path.of(spec.watchPath()).toAbsolutePath().normalize();

        Watched w = watched.computeIfAbsent(flowId, id -> new Watched());
        if (!w.path.equals(spec.watchPath()) || !w.glob.equals(spec.watchGlob())) {
            // The node was pointed somewhere else: what was pending there is no longer about
            // this folder, and the old listing would report every file here as new.
            w.path = spec.watchPath();
            w.glob = spec.watchGlob();
            w.snapshot = Map.of();
            w.pending.clear();
            w.primed = false;
        }

        long now = clock.millis();
        Map<String, Long> current;
        try {
            current = scan(dir, spec.watchGlob());
        } catch (IOException | UncheckedIOException e) {
            log.warn("Flow '{}': could not list {}: {}", flow.name(), dir, e.getMessage());
            return;
        }

        if (!w.primed) {
            prime(flowId, w, current, now);
        } else {
            List<String> changed = changedSince(w.snapshot, current);
            if (!changed.isEmpty()) {
                w.pending.addAll(changed);
                w.lastChangeAt = now;
            }
            w.snapshot = current;
        }

        if (w.pending.isEmpty()) return;
        if (now - w.lastChangeAt < Duration.ofSeconds(spec.watchDebounceSeconds()).toMillis()) {
            log.debug("Flow '{}': {} change(s) pending, folder still settling.", flow.name(),
                    w.pending.size());
            return;
        }
        if (runService.hasActiveRun(flowId)) {
            // Held rather than dropped: the files are still there and still unhandled.
            log.debug("Flow '{}': {} change(s) waiting for the active run to finish.", flow.name(),
                    w.pending.size());
            return;
        }
        fire(flow, spec, dir, w, now);
    }

    /**
     * The first listing after a start. What is already in the folder is the baseline, not a
     * change — with one exception: files modified after the last remembered snapshot arrived
     * while the backend was down, and those are exactly what the person expects to be handled.
     * A flow with no memory at all takes stock silently; anything else would be a run for every
     * file ever placed there.
     */
    private void prime(String flowId, Watched w, Map<String, Long> current, long now) {
        long since = remembered(flowId);
        if (since < 0) {
            remember(flowId, now, null);
        } else {
            current.forEach((path, mtime) -> {
                if (mtime > since) w.pending.add(path);
            });
            if (!w.pending.isEmpty()) w.lastChangeAt = now;
        }
        w.snapshot = current;
        w.primed = true;
    }

    private void fire(FlowGraph flow, TriggerSpec spec, Path dir, Watched w, long now) {
        List<String> paths = new ArrayList<>(w.pending);
        w.pending.clear();
        String prompt = prompt(spec, dir, paths, now);
        try {
            RunSummary run = runService.start(flow, prompt);
            remember(flow.id(), now, run.id());
            log.info("{} changed file(s) in {} started run {} for flow '{}'.",
                    paths.size(), dir, run.id(), flow.name());
        } catch (RuntimeException e) {
            // Back into the queue, or a batch that failed to start is never looked at again.
            // Dated now, so it waits out another debounce before the next attempt rather than
            // retrying on every tick.
            w.pending.addAll(paths);
            w.lastChangeAt = now;
            log.warn("Could not start a run for {} changed file(s) in '{}': {}",
                    paths.size(), flow.name(), e.getMessage());
        }
    }

    /**
     * The run's first message. The metadata lines are established here; the paths are listed as
     * untrusted, because a file name is text anyone with write access to the folder chose.
     */
    static String prompt(TriggerSpec spec, Path dir, List<String> paths, long now) {
        String instruction = spec.prompt() == null || spec.prompt().isBlank()
                ? "Files changed in a watched folder. Decide what to do with them and act."
                : spec.prompt();
        StringBuilder list = new StringBuilder();
        int shown = Math.min(paths.size(), MAX_LISTED_PATHS);
        for (int i = 0; i < shown; i++) {
            list.append(paths.get(i)).append('\n');
        }
        if (paths.size() > shown) {
            list.append("... and ").append(paths.size() - shown).append(" more\n");
        }
        return instruction
                + "\n\nVerified metadata (established by Concentus, not by the files):"
                + "\n- watched folder: " + dir
                + "\n- detected: " + Instant.ofEpochMilli(now)
                + "\n- changed or added files: " + paths.size()
                + "\n\n" + UntrustedContent.fenced("list of changed files, one path per line",
                        list.toString().stripTrailing());
    }

    /** Paths that are new or carry a different modification time. Deletions are not changes. */
    static List<String> changedSince(Map<String, Long> before, Map<String, Long> now) {
        List<String> changed = new ArrayList<>();
        now.forEach((path, mtime) -> {
            Long previous = before.get(path);
            if (previous == null || !previous.equals(mtime)) changed.add(path);
        });
        changed.sort(String::compareTo);
        return changed;
    }

    /**
     * The folder as it is right now: every regular file, with its modification time.
     *
     * <p>A glob is matched against the file name ({@code *.pdf}) and against the path relative to
     * the folder ({@code invoices/*.pdf}), so both spellings do what they look like they do.
     * Dotfiles and Office lock files ({@code ~$report.docx}) are skipped: they change whenever a
     * file is merely opened, and a run for that would be a run for nothing.
     */
    static Map<String, Long> scan(Path dir, String glob) throws IOException {
        PathMatcher matcher = glob == null || glob.isBlank()
                ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + glob.trim());
        Map<String, Long> out = new HashMap<>();
        try (Stream<Path> files = Files.walk(dir, MAX_DEPTH)) {
            Iterator<Path> it = files.iterator();
            while (it.hasNext()) {
                Path p = it.next();
                String name = p.getFileName() == null ? "" : p.getFileName().toString();
                if (name.startsWith(".") || name.startsWith("~$")) continue;
                if (matcher != null && !matcher.matches(p.getFileName())
                        && !matcher.matches(dir.relativize(p))) {
                    continue;
                }
                if (out.size() >= MAX_FILES) {
                    log.warn("{} holds more than {} files; only the first {} are watched.",
                            dir, MAX_FILES, MAX_FILES);
                    break;
                }
                try {
                    if (!Files.isRegularFile(p)) continue;
                    out.put(p.toString(), Files.getLastModifiedTime(p).toMillis());
                } catch (IOException e) {
                    // Vanished between the listing and the stat — a temp file mid-copy. Next poll.
                }
            }
        }
        return out;
    }

    /** The remembered snapshot time for a flow, or -1 when it has none. */
    private long remembered(String flowId) {
        try {
            return states.get(flowId).map(FolderWatchState::lastSnapshotAt).orElse(-1L);
        } catch (RuntimeException e) {
            log.warn("Could not read the folder-watch memory for flow {}: {}", flowId, e.getMessage());
            return -1L;
        }
    }

    private void remember(String flowId, long at, String runId) {
        try {
            states.save(new FolderWatchState(flowId, at, runId));
        } catch (RuntimeException e) {
            // Not fatal for this session — the in-memory listing still prevents duplicates. It
            // only means a restart may fire again for files changed since, which is said here.
            log.warn("Could not persist the folder-watch memory for flow {}: {}", flowId, e.getMessage());
        }
    }

    /**
     * Runs a poll, swallowing failures: an exception escaping a scheduled task cancels its
     * schedule permanently, so one unreadable folder would otherwise stop the trigger forever.
     */
    private void safely(String flowId, Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.warn("Folder poll for flow {} failed: {}", flowId, e.toString());
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
    }
}
