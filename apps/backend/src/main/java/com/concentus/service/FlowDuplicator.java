package com.concentus.service;

import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A copy of a flow, ready to be changed without touching the original.
 *
 * <p>Copying a flow is how anyone tries a different shape of the same job — a second model, an
 * independent-worker variant, a version with one agent removed. Doing it by hand means re-typing
 * prompts that took an afternoon to write, and doing it by exporting and re-importing means
 * carrying an id that would overwrite the original on the next save.
 *
 * <p>Two things are deliberately NOT copied, and both are about the copy acting on the world
 * before anyone has looked at it.
 */
public final class FlowDuplicator {

    private FlowDuplicator() {
    }

    /**
     * What a copy is called, unless that name is taken too.
     *
     * <p>Parenthesised because the rest of the application already is — a sandbox copy is
     * "<name> (sandbox)" — and a list where one kind of copy is bracketed and another is not reads
     * as two different features.
     */
    static final String SUFFIX = " (copy)";

    /**
     * @param existingNames every flow name already in use, so two copies of the same flow do not
     *                      end up sharing one
     */
    public static FlowGraph copyOf(FlowGraph source, Set<String> existingNames) {
        List<FlowNode> nodes = new ArrayList<>();
        for (FlowNode n : source.nodesOrEmpty()) {
            nodes.add("input".equalsIgnoreCase(n.type()) ? copyOfInput(n) : n);
        }

        // No id: the store mints one on save. Carrying the source's would overwrite the flow this
        // is a copy OF, which is the one outcome a copy must never have.
        //
        // Disabled, and that is the important half: a copy of a flow that fires at 07:00 would
        // fire at 07:00 too, from tomorrow, doing whatever the original does to whatever the
        // original does it to — twice. Nobody duplicating a flow to try something meant that. The
        // trigger is kept, so enabling it is one click once the copy says what it should.
        return new FlowGraph(null, uniqueName(source.name(), existingNames), source.mode(),
                nodes, source.edgesOrEmpty(), Boolean.FALSE, source.tagsOrEmpty(), Boolean.FALSE,
                source.notifyWebhook(), source.budgetUsd(), source.approvalSlackCredentialId(),
                source.approvalSlackChannel(), source.approvalTeamsWebhook(), source.variables(),
                source.folder(), Boolean.FALSE);
    }

    /**
     * The trigger, minus the secret.
     *
     * <p>A webhook secret is issued by the provider for one endpoint. Copied, it would mean two
     * flows claiming the same signature — and whichever the provider is pointed at, the other
     * silently accepts deliveries meant for its twin. The same reasoning the canvas already
     * applies when a node is pasted.
     */
    private static FlowNode copyOfInput(FlowNode input) {
        Map<String, Object> data = new LinkedHashMap<>(input.dataOrEmpty());
        if (data.containsKey("secret")) data.put("secret", "");
        return new FlowNode(input.id(), input.type(), input.role(), data);
    }

    /** "Report" -> "Report (copy)" -> "Report (copy 2)", so a third copy is not a mystery. */
    static String uniqueName(String name, Set<String> existingNames) {
        Set<String> taken = lowercased(existingNames);
        String base = (name == null || name.isBlank() ? "Flow" : name) + SUFFIX;
        if (!taken.contains(base.toLowerCase(Locale.ROOT))) return base;
        // "(copy 2)", not "(copy) 2": the number belongs inside the bracket that says what this is.
        String numbered = base.substring(0, base.length() - 1);
        for (int i = 2; ; i++) {
            String candidate = numbered + " " + i + ")";
            if (!taken.contains(candidate.toLowerCase(Locale.ROOT))) return candidate;
        }
    }

    private static Set<String> lowercased(Set<String> names) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String n : names) {
            if (n != null) out.add(n.toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
