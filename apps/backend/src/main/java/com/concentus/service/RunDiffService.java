package com.concentus.service;

import com.concentus.git.GitWorkspace;
import com.concentus.model.RunPatch;
import com.concentus.runners.RunnerRegistry;
import com.concentus.store.RunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The diff of what a run's agents did to the repositories — every checkout, read now.
 *
 * <p>This is the screen that decides whether the pull request in the report gets trusted, so it
 * reads the working tree on every request while the directory is still there rather than serving
 * whatever was last recorded: a shared session can take another command after "completing", and
 * a merge step commits after the workers finished. What it reads, it records on the run, so the
 * database's copy is always the latest look and survives the directory being cleaned up.
 *
 * <p>When the directory is gone — a restart older than the run's workspace, a cleanup — the
 * recorded patch is served with a note saying so. A patch that was never recorded before the
 * directory went is a note without a patch: the honest answer is "unknown", not "no changes".
 *
 * <p>A checkout on a runner is re-read through the runner while it is connected, and served as
 * last read with a note while it is not: the directory is on another machine, and "gone" is not
 * something this one can tell from "not answering right now".
 */
@Service
public class RunDiffService {

    private static final Logger log = LoggerFactory.getLogger(RunDiffService.class);

    static final String GONE_NOTE = "The checkout directory no longer exists; this is the change "
            + "as it was last read while the run was in flight.";
    static final String NEVER_READ_NOTE = "The checkout directory no longer exists, and no change "
            + "was read from it before it went.";

    private final GitWorkspace git;
    private final RunStore runStore;
    private final RunnerRegistry runners;

    @Autowired
    public RunDiffService(GitWorkspace git, RunStore runStore, RunnerRegistry runners) {
        this.git = git;
        this.runStore = runStore;
        this.runners = runners;
    }

    /** Without runners — what the tests that predate them build. */
    public RunDiffService(GitWorkspace git, RunStore runStore) {
        this(git, runStore, null);
    }

    /** Every checkout of the run with its current diff, coordinator first, then the workers. */
    public List<RunPatch> diffsOf(AgentRun run) {
        List<RunPatch> out = new ArrayList<>();
        boolean changed = false;
        for (RunPatch stored : run.patchList()) {
            RunPatch now = refresh(stored);
            out.add(now);
            // Only a read that changed something is worth a write: a run whose directory is
            // gone, or whose diff is what it was, would otherwise be re-persisted on every visit
            // to the screen. The first read counts even when it found nothing — "read, and
            // unchanged" is a different fact from "never read".
            boolean fresh = now.takenAt() != stored.takenAt();
            if (fresh && (stored.takenAt() == 0 || !Objects.equals(now.patch(), stored.patch()))) {
                run.recordPatch(now);
                changed = true;
            }
        }
        if (changed) runStore.markDirty(run);
        return out;
    }

    /** One checkout's diff, when the run made that checkout. */
    public Optional<RunPatch> diffOf(AgentRun run, String nodeId, String folder) {
        return diffsOf(run).stream()
                .filter(p -> p.nodeId().equals(nodeId) && p.folder().equals(folder))
                .findFirst();
    }

    /** The file name a downloaded patch gets: who made it, to which checkout. */
    public static String fileNameOf(RunPatch patch) {
        String who = patch.label() == null || patch.label().isBlank() ? patch.nodeId() : patch.label();
        return LocalClaudeExecutor.sanitize(who) + "--" + patch.folder() + ".patch";
    }

    private RunPatch refresh(RunPatch stored) {
        if (stored.directory() != null && stored.directory().startsWith(RunPatch.RUNNER_DIRECTORY_PREFIX)) {
            return refreshRemote(stored);
        }
        Path dir = stored.directory() == null ? null : Path.of(stored.directory());
        // `.git` rather than the directory alone: an empty folder left behind by a partial cleanup
        // is not a checkout, and git would say so less clearly.
        if (dir == null || !Files.exists(dir.resolve(".git"))) {
            if (stored.patch() != null) return stored.noting(GONE_NOTE);
            return stored.note() != null ? stored : stored.noting(NEVER_READ_NOTE);
        }
        try {
            return stored.taken(git.patchSince(dir, stored.base()), System.currentTimeMillis());
        } catch (IOException e) {
            log.warn("diff of {} for {}: {}", dir, stored.nodeId(), e.getMessage());
            // The last good read stays; what failed is said next to it rather than replacing it.
            return stored.noting("Could not read the checkout now: " + e.getMessage());
        }
    }

    /** {@code runner:<id>:<path>} — asked of the runner, which answers while it is connected. */
    private RunPatch refreshRemote(RunPatch stored) {
        String rest = stored.directory().substring(RunPatch.RUNNER_DIRECTORY_PREFIX.length());
        int colon = rest.indexOf(':');
        if (colon <= 0 || runners == null) {
            return stored.note() != null ? stored : stored.noting("This checkout is on a runner; it cannot be re-read here.");
        }
        String runnerId = rest.substring(0, colon);
        String directory = rest.substring(colon + 1);
        try {
            return stored.taken(runners.patchSince(runnerId, directory, stored.base()), System.currentTimeMillis());
        } catch (IOException e) {
            log.debug("diff of {} on runner {}: {}", directory, runnerId, e.getMessage());
            return stored.noting(e.getMessage());
        }
    }
}
