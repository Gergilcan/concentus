package com.concentus.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What one agent did to one repository checkout during a run — the diff a person reads before
 * trusting the pull request the report links to.
 *
 * <p>One record per (node, folder): a fan-out worker with two repositories has two, and the merge
 * step, which clones every repository again, has its own set. Registered when the checkout is
 * cloned, with no patch yet; filled in whenever the checkout is read — at a worker's finish, and
 * on every request for the run's diffs while the directory still exists.
 *
 * <p>Persisted with the run, patch text included, because the working tree is scratch space: a
 * cleanup or a reinstall takes it away, and the review must not go with it. {@link #directory}
 * and {@link #base} are what make re-reading possible after a restart — the run's in-memory
 * checkout list does not survive one, but the path and the commit it started from do.
 *
 * <p>One canonical constructor and nothing else that looks like one: Jackson picks the creator
 * for a record by inspection, and a convenience constructor is how a component silently stops
 * being deserialized.
 *
 * @param nodeId    the canvas node (or plan-born worker) that owned the checkout
 * @param label     that node's name, kept here so a restored run can title the diff without
 *                  a compiled flow at hand
 * @param folder    the checkout's directory name inside the node's workspace
 * @param repoUrl   the repository it was cloned from
 * @param directory absolute path of the checkout, null when unknown
 * @param base      the commit the checkout was cloned at; the diff is everything since —
 *                  commits the agent made, edits it did not commit, files it only created.
 *                  Null means HEAD, which is right for a worker (no shell, so no commits)
 * @param patch     the unified diff, {@code --binary} so an added image survives; null when
 *                  nothing changed, or when nothing has been read yet
 * @param stats     files and lines, counted from the patch
 * @param note      why the patch is not what it could be — the directory is gone, the store
 *                  capped it — or null when there is nothing to say
 * @param takenAt   when the patch was last read, epoch millis; 0 before the first read
 */
public record RunPatch(String nodeId, String label, String folder, String repoUrl,
                       String directory, String base, String patch, PatchStats stats,
                       String note, long takenAt) {

    /**
     * How much patch text one run may keep in the database, all checkouts together. Two
     * megabytes is a generated lockfile or a vendored dependency, well past what a person would
     * review line by line; beyond it the stats stay and the text is dropped, with a note.
     */
    public static final int MAX_STORED_BYTES = 2 * 1024 * 1024;

    public static final String CAPPED_NOTE = "Not kept: this run's patches exceed the "
            + (MAX_STORED_BYTES / (1024 * 1024)) + " MB stored per run. The checkout directory, "
            + "while it exists, still has the change.";

    /** A checkout that exists and has not been read yet. */
    public static RunPatch registered(String nodeId, String label, String folder, String repoUrl,
                                      Path directory, String base) {
        return new RunPatch(nodeId, label, folder, repoUrl,
                directory == null ? null : directory.toAbsolutePath().normalize().toString(),
                base, null, PatchStats.NONE, null, 0);
    }

    /** This checkout as just read: the patch (null for no change), its stats, no note. */
    public RunPatch taken(String patch, long at) {
        return new RunPatch(nodeId, label, folder, repoUrl, directory, base, patch,
                PatchStats.of(patch), null, at);
    }

    /** The same patch, with a reason attached. */
    public RunPatch noting(String note) {
        return new RunPatch(nodeId, label, folder, repoUrl, directory, base, patch, stats, note,
                takenAt);
    }

    /** Identity within a run: a node may hold several checkouts, and a checkout belongs to one node. */
    public String key() {
        return nodeId + "/" + folder;
    }

    /**
     * The list as it goes to the database: patches kept in order until the budget is spent, the
     * rest stripped to their stats with {@link #CAPPED_NOTE}. Greedy rather than all-or-nothing so
     * a run with one enormous lockfile diff and three real ones keeps the three.
     */
    public static List<RunPatch> capped(List<RunPatch> all, int maxBytes) {
        List<RunPatch> out = new ArrayList<>(all.size());
        long used = 0;
        for (RunPatch p : all) {
            if (p.patch() == null) {
                out.add(p);
                continue;
            }
            long size = p.patch().length();
            if (used + size <= maxBytes) {
                used += size;
                out.add(p);
            } else {
                out.add(new RunPatch(p.nodeId(), p.label(), p.folder(), p.repoUrl(), p.directory(),
                        p.base(), null, p.stats(), CAPPED_NOTE, p.takenAt()));
            }
        }
        return out;
    }
}
