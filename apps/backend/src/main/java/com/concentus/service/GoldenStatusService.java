package com.concentus.service;

import com.concentus.model.FlowEdge;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import com.concentus.model.GoldenStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * "Has this flow changed since the run I trust?"
 *
 * <p>Golden runs already answer "does the flow still do the right thing" — but only if someone
 * remembers to press the button. This turns that into something the dashboard can say by itself:
 * every golden run stores the exact graph it executed, so comparing it with the flow as saved now
 * is the whole check. Nothing is recorded, so nothing can go stale about staleness itself.
 */
@Service
public class GoldenStatusService {

    private static final Logger log = LoggerFactory.getLogger(GoldenStatusService.class);

    private final RunService runs;
    private final ObjectMapper mapper;

    public GoldenStatusService(RunService runs, ObjectMapper mapper) {
        this.runs = runs;
        this.mapper = mapper;
    }

    /** The status of every flow that has a golden reference. Flows without one are simply absent. */
    public List<GoldenStatus> all(List<FlowGraph> flows) {
        List<GoldenStatus> out = new ArrayList<>();
        for (FlowGraph flow : flows) of(flow).ifPresent(out::add);
        return out;
    }

    /** This flow's status, or empty when it has no golden reference to be measured against. */
    public Optional<GoldenStatus> of(FlowGraph flow) {
        if (flow == null || flow.id() == null || flow.id().isBlank()) return Optional.empty();
        AgentRun golden = goldenRunOf(flow.id());
        if (golden == null) return Optional.empty();
        return Optional.of(new GoldenStatus(flow.id(), golden.id, changedSince(golden, flow),
                flow.goldenAutoRunOrDefault()));
    }

    /**
     * The golden run to replay right after a save, or empty when there is nothing to check.
     *
     * <p>Three conditions, and each one is there to stop money being spent by accident: the flow
     * must have asked for automatic checks, the save must have actually changed the graph (a
     * rename, a tag, or a second click on Save is not an edit worth an agent run), and there must
     * be a golden reference to replay.
     */
    public Optional<AgentRun> autoCheckAfterSave(FlowGraph before, FlowGraph saved) {
        if (saved == null || before == null || !saved.goldenAutoRunOrDefault()) return Optional.empty();
        if (signature(before).equals(signature(saved))) return Optional.empty();
        return Optional.ofNullable(goldenRunOf(saved.id()));
    }

    /** The flow's golden run, or null. At most one exists per flow — {@code setGolden} enforces it. */
    public AgentRun goldenRunOf(String flowId) {
        if (flowId == null || flowId.isBlank()) return null;
        for (com.concentus.model.RunSummary summary : runs.list()) {
            if (summary.golden() && flowId.equals(summary.flowId())) {
                return runs.get(summary.id()).orElse(null);
            }
        }
        return null;
    }

    /**
     * Whether the saved graph differs from the one the golden run executed.
     *
     * <p>False when the run kept no snapshot: with nothing to compare against, "changed" would be
     * a guess, and a chip that cried stale on every flow would be ignored within a day.
     */
    private boolean changedSince(AgentRun golden, FlowGraph current) {
        if (golden.flowJson == null || golden.flowJson.isBlank()) return false;
        try {
            FlowGraph executed = mapper.readValue(golden.flowJson, FlowGraph.class);
            return !signature(executed).equals(signature(current));
        } catch (Exception e) {
            log.debug("Golden snapshot for run {} unreadable: {}", golden.id, e.getMessage());
            return false;
        }
    }

    /**
     * What "the same flow" means here: the nodes and edges that decide behaviour.
     *
     * <p>Canvas POSITIONS are excluded, and that is the point of having a signature at all —
     * dragging a box two pixels is not a change worth re-running an agent over, and a naive JSON
     * comparison would say it was. Node ids, types, roles and their remaining data ARE included:
     * a renamed agent, a rewritten prompt or a rewired edge all change what the flow does.
     *
     * <p>Maps are sorted so two identical graphs whose JSON keys arrived in a different order do
     * not read as different — which they would after any round trip through a store.
     */
    static String signature(FlowGraph flow) {
        StringBuilder sb = new StringBuilder();
        List<FlowNode> nodes = new ArrayList<>(flow.nodesOrEmpty());
        nodes.sort((a, b) -> String.valueOf(a.id()).compareTo(String.valueOf(b.id())));
        for (FlowNode node : nodes) {
            sb.append(node.id()).append('|').append(node.type()).append('|').append(node.role())
                    .append('|').append(sorted(node.dataOrEmpty())).append('\n');
        }
        List<FlowEdge> edges = new ArrayList<>(flow.edgesOrEmpty());
        edges.sort((a, b) -> (a.source() + ">" + a.target()).compareTo(b.source() + ">" + b.target()));
        for (FlowEdge edge : edges) {
            sb.append(edge.source()).append('>').append(edge.target()).append('\n');
        }
        return sb.toString();
    }

    /** A node's data, minus its canvas position, with keys in a stable order. */
    private static Map<String, Object> sorted(Map<String, Object> data) {
        Map<String, Object> out = new TreeMap<>();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if ("_pos".equals(e.getKey())) continue;
            out.put(e.getKey(), e.getValue() instanceof Map<?, ?> m ? sortedAny(m) : e.getValue());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sortedAny(Map<?, ?> map) {
        return new TreeMap<>((Map<String, Object>) map);
    }
}
