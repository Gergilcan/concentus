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
     * @param dependsOn      not supported yet; must be empty. Present in the shape so a planner
     *                       that sends it gets a real answer instead of silent parallelism
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
            if (item.dependsOn() != null && !item.dependsOn().isEmpty()) {
                out.add("Item '" + id + "' declares dependsOn, which is not supported yet: items "
                        + "run in parallel. Fold dependent steps into one item, or leave the "
                        + "second step to the merge.");
            }
            for (String file : item.filesOrEmpty()) {
                if (file == null || file.isBlank()) continue;
                // Normalized so "src\A.java" and "src/A.java" collide as they would on disk.
                String key = file.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
                String owner = fileOwner.putIfAbsent(key, id);
                if (owner != null && !owner.equals(id)) {
                    out.add("Items '" + owner + "' and '" + id + "' both declare the file '"
                            + file.trim() + "'. No two parallel items may touch the same file — "
                            + "repartition the work.");
                }
            }
        }
        return out;
    }
}
