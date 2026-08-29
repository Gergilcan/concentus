package com.concentus.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The plan a fan-out coordinator submits: the work items Concentus turns into independent
 * worker processes. Arrives through the {@code plan_submit} tool and is validated here —
 * the rules live next to the shape so the tool's error messages and the contract cannot drift.
 *
 * @param goal  one line naming the overall objective, echoed into the merge step's input
 * @param items the work; each becomes one {@code claude} process
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkPlan(String goal, List<WorkItem> items) {

    /**
     * One unit of work.
     *
     * @param id             short unique handle ("w1", "backend"); becomes the worker's box id
     * @param title          human name for the box; blank falls back to the id
     * @param prompt         the worker's whole instruction — self-contained, because a worker
     *                       sees nothing else of the conversation
     * @param context        the facts THIS item needs, written into the worker's CLAUDE.md.
     *                       Deliberately not the flow's whole context: the constraint is to pass
     *                       each worker only what its slice requires
     * @param files          paths this item intends to touch. Declared so the plan can be
     *                       rejected when two items claim the same file — parallel processes
     *                       writing one file is silent corruption, not a merge conflict
     * @param contextFolders host folders this worker should read; each still has to pass the
     *                       deployment's context-root allowlist like any canvas value
     * @param model          model for this worker; blank inherits the coordinator's
     * @param profile        facade profile by NAME (as shown to the planner); resolved to
     *                       {@link #profileId} server-side
     * @param profileId      resolved profile id — filled by the server, never trusted from input
     * @param dependsOn      ids of the items this one waits for. It starts when they have all
     *                       finished and receives their reports at the end of its prompt; a
     *                       dependency that failed fails it without a launch. Every id must
     *                       name another item, and the graph must not loop
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorkItem(String id, String title, String prompt, List<String> context,
                           List<String> files, List<String> contextFolders,
                           String model, String profile, String profileId,
                           List<String> dependsOn) {

        public String displayName() {
            return title == null || title.isBlank() ? id : title;
        }

        public List<String> contextOrEmpty() {
            return context == null ? List.of() : context;
        }

        public List<String> filesOrEmpty() {
            return files == null ? List.of() : files;
        }

        public List<String> contextFoldersOrEmpty() {
            return contextFolders == null ? List.of() : contextFolders;
        }

        /** The ids this item waits for, trimmed; blanks dropped. */
        public List<String> dependsOnOrEmpty() {
            if (dependsOn == null) return List.of();
            List<String> out = new ArrayList<>();
            for (String d : dependsOn) {
                if (d != null && !d.isBlank()) out.add(d.trim());
            }
            return out;
        }

        /** A copy with the server-resolved profile id. */
        public WorkItem withProfileId(String resolvedId) {
            return new WorkItem(id, title, prompt, context, files, contextFolders,
                    model, profile, resolvedId, dependsOn);
        }
    }

    public List<WorkItem> itemsOrEmpty() {
        return items == null ? List.of() : items;
    }

    /**
     * Everything wrong with this plan, in one pass — the submitter gets the whole list and fixes
     * it once, instead of resubmitting through one error at a time.
     */
    public List<String> problems(int maxItems) {
        List<String> out = new ArrayList<>();
        List<WorkItem> list = itemsOrEmpty();
        if (list.isEmpty()) {
            out.add("The plan has no work items — there is nothing to run.");
            return out;
        }
        if (list.size() > maxItems) {
            out.add("Too many items: " + list.size() + " (the limit is " + maxItems
                    + "). Fold the smallest slices together.");
        }

        Map<String, String> fileOwner = new LinkedHashMap<>();
        List<String> seenIds = new ArrayList<>();
        for (WorkItem item : list) {
            String id = item.id() == null ? "" : item.id().trim();
            if (id.isEmpty()) {
                out.add("Every item needs a short unique id.");
                continue;
            }
            if (seenIds.contains(id)) {
                out.add("Item id '" + id + "' is used more than once — ids must be unique.");
            }
            seenIds.add(id);
            if (item.prompt() == null || item.prompt().isBlank()) {
                out.add("Item '" + id + "' has no prompt — a worker with no instruction does nothing.");
            }
            for (String dep : item.dependsOnOrEmpty()) {
                if (dep.equals(id)) {
                    out.add("Item '" + id + "' depends on itself.");
                } else if (list.stream().noneMatch(o -> o.id() != null && o.id().trim().equals(dep))) {
                    out.add("Item '" + id + "' depends on '" + dep + "', which is not an item of this plan.");
                }
            }
        }
        for (String cycle : cycles(list)) {
            out.add("These items wait for each other in a loop, so none of them could ever start: "
                    + cycle + ". Break the loop.");
        }
        // Two items may touch one file only when they cannot run at the same time — one waits
        // for the other, directly or through others. Everything else is a parallel write.
        for (WorkItem item : list) {
            String id = item.id() == null ? "" : item.id().trim();
            if (id.isEmpty()) continue;
            for (String file : item.filesOrEmpty()) {
                if (file == null || file.isBlank()) continue;
                // Normalized so "src\A.java" and "src/A.java" collide as they would on disk.
                String key = file.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
                String owner = fileOwner.putIfAbsent(key, id);
                if (owner != null && !owner.equals(id) && !ordered(list, owner, id)) {
                    out.add("Items '" + owner + "' and '" + id + "' both declare the file '"
                            + file.trim() + "'. No two parallel items may touch the same file — "
                            + "repartition the work, or make one depend on the other.");
                }
            }
        }
        return out;
    }

    /** Whether one of the two items waits for the other, directly or through others. */
    private static boolean ordered(List<WorkItem> items, String a, String b) {
        return reaches(items, a, b, new java.util.HashSet<>()) || reaches(items, b, a, new java.util.HashSet<>());
    }

    private static boolean reaches(List<WorkItem> items, String from, String to, java.util.Set<String> seen) {
        if (!seen.add(from)) return false;
        for (WorkItem item : items) {
            if (item.id() == null || !item.id().trim().equals(from)) continue;
            for (String dep : item.dependsOnOrEmpty()) {
                if (dep.equals(to) || reaches(items, dep, to, seen)) return true;
            }
        }
        return false;
    }

    /** Every dependency loop, each named once by the ids on it. */
    private static List<String> cycles(List<WorkItem> items) {
        List<String> out = new ArrayList<>();
        java.util.Set<String> reported = new java.util.HashSet<>();
        for (WorkItem item : items) {
            String id = item.id() == null ? "" : item.id().trim();
            if (id.isEmpty() || reported.contains(id)) continue;
            List<String> path = new ArrayList<>();
            if (loopFrom(items, id, id, path, new java.util.HashSet<>())) {
                reported.addAll(path);
                out.add(String.join(" → ", path) + " → " + id);
            }
        }
        return out;
    }

    private static boolean loopFrom(List<WorkItem> items, String start, String at,
                                    List<String> path, java.util.Set<String> seen) {
        if (!seen.add(at)) return false;
        path.add(at);
        for (WorkItem item : items) {
            if (item.id() == null || !item.id().trim().equals(at)) continue;
            for (String dep : item.dependsOnOrEmpty()) {
                if (dep.equals(start)) return true;
                if (loopFrom(items, start, dep, path, seen)) return true;
            }
        }
        path.remove(path.size() - 1);
        return false;
    }
}
