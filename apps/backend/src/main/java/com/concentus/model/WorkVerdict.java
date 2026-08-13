package com.concentus.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The judgment an adversarial verifier submits over a fan-out's worker outputs: one verdict per
 * worker, accept or reject with a reason. Arrives through the {@code verdict_submit} tool and is
 * validated here — the rules live next to the shape so the tool's error messages and the
 * contract cannot drift, exactly like {@link WorkPlan}.
 *
 * <p>The verifier's power is real, not decorative: a rejected output is withheld from the merge
 * step. Which is why the validation insists on <b>every</b> worker being judged — a verifier
 * that could skip the awkward ones would be a rubber stamp with extra steps.
 *
 * @param summary one line on the overall judgment, echoed into the run's console
 * @param items   one verdict per surviving worker
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkVerdict(String summary, List<Item> items) {

    /**
     * One worker's verdict.
     *
     * @param id      the worker's node id, exactly as the prompt listed it
     * @param verdict {@code accept} or {@code reject} — nothing in between, because "mostly
     *                fine" is what the merge step is for
     * @param reason  why. Required on a rejection: a kill without a stated reason is
     *                indistinguishable from a hallucinated one
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String id, String verdict, String reason) {

        public boolean rejected() {
            return "reject".equalsIgnoreCase(spelling());
        }

        public boolean accepted() {
            return "accept".equalsIgnoreCase(spelling());
        }

        /**
         * The verdict word as written, tolerant of the padding a model adds. Not bean-named on
         * purpose: a {@code get}/{@code is} method here would join the serialized JSON.
         */
        private String spelling() {
            return verdict == null ? "" : verdict.trim();
        }
    }

    public List<Item> itemsOrEmpty() {
        return items == null ? List.of() : items;
    }

    /** The verdict for one worker id, or null when the submission never judged it. */
    public Item of(String workerId) {
        for (Item item : itemsOrEmpty()) {
            if (item.id() != null && item.id().trim().equals(workerId)) return item;
        }
        return null;
    }

    public long rejectedCount() {
        return itemsOrEmpty().stream().filter(Item::rejected).count();
    }

    /**
     * Everything wrong with this verdict against the workers actually awaiting judgment, in one
     * pass — the submitter gets the whole list and fixes it once.
     */
    public List<String> problems(Collection<String> expectedIds) {
        List<String> out = new ArrayList<>();
        List<Item> list = itemsOrEmpty();
        if (list.isEmpty()) {
            out.add("The verdict has no items — every worker listed in your prompt must be judged.");
            return out;
        }

        Map<String, Item> seen = new LinkedHashMap<>();
        for (Item item : list) {
            String id = item.id() == null ? "" : item.id().trim();
            if (id.isEmpty()) {
                out.add("Every verdict needs the worker id it judges.");
                continue;
            }
            if (!expectedIds.contains(id)) {
                out.add("'" + id + "' is not a worker awaiting judgment. Judge exactly: "
                        + String.join(", ", expectedIds) + ".");
                continue;
            }
            if (seen.putIfAbsent(id, item) != null) {
                out.add("Worker '" + id + "' is judged more than once — one verdict each.");
                continue;
            }
            if (!item.accepted() && !item.rejected()) {
                out.add("Worker '" + id + "': verdict must be 'accept' or 'reject', not '"
                        + (item.verdict() == null ? "" : item.verdict()) + "'.");
            }
            if (item.rejected() && (item.reason() == null || item.reason().isBlank())) {
                out.add("Worker '" + id + "' is rejected without a reason — a kill must say why.");
            }
        }
        for (String expected : expectedIds) {
            if (!seen.containsKey(expected)) {
                out.add("Worker '" + expected + "' was not judged — every worker gets a verdict, "
                        + "accept or reject.");
            }
        }
        return out;
    }
}
