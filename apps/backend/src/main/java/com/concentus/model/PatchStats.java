package com.concentus.model;

/**
 * The headline numbers of a unified diff: how many files it touches and how many lines it adds
 * and removes. Derived from the patch text, never asked of git, so a patch that outlived its
 * checkout — restored from the database after a restart — still has them.
 */
public record PatchStats(int files, int additions, int deletions) {

    public static final PatchStats NONE = new PatchStats(0, 0, 0);

    /**
     * Counts a patch the way {@code git diff --stat} would.
     *
     * <p>A {@code +} or {@code -} line only counts once a hunk has started: before the first
     * {@code @@} of a file the {@code ---}/{@code +++} lines name the old and new path, and a
     * naive prefix test would count them as one deletion and one addition per file. Inside a hunk
     * every such line is content — a removed line that happened to read {@code --foo} is a
     * deletion, and the same test would have skipped it.
     */
    public static PatchStats of(String patch) {
        if (patch == null || patch.isBlank()) return NONE;
        int files = 0;
        int added = 0;
        int removed = 0;
        boolean inHunk = false;
        for (String line : patch.split("\n", -1)) {
            if (line.startsWith("diff --git ")) {
                files++;
                inHunk = false;
            } else if (line.startsWith("@@")) {
                inHunk = true;
            } else if (inHunk && line.startsWith("+")) {
                added++;
            } else if (inHunk && line.startsWith("-")) {
                removed++;
            }
        }
        return new PatchStats(files, added, removed);
    }
}
