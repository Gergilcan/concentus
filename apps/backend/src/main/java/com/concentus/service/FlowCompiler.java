package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.config.AgentSpec.McpServerSpec;
import com.concentus.config.AgentSpec.ModelSpec;
import com.concentus.config.AgentSpec.RepoSpec;
import com.concentus.config.AgentSpec.SqlSourceSpec;
import com.concentus.model.FlowEdge;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import org.springframework.stereotype.Component;

import static com.concentus.support.MapValues.lng;
import static com.concentus.support.MapValues.str;
import static com.concentus.support.MapValues.strList;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Compiles a {@link FlowGraph} into a coordinator + sub-agent {@link AgentSpec}s. */
@Component
public class FlowCompiler {

    public CompiledFlow compile(FlowGraph flow) {
        List<FlowNode> agents = byType(flow, "agent");
        List<FlowNode> mcps = byType(flow, "mcp");
        List<FlowNode> repos = byType(flow, "repo");
        List<FlowNode> sqls = byType(flow, "sql");

        if (agents.isEmpty()) {
            throw new IllegalArgumentException("Flow has no agent nodes.");
        }

        List<FlowNode> coordinators = agents.stream()
                .filter(n -> "coordinator".equalsIgnoreCase(n.role()))
                .toList();

        FlowNode coordinatorNode;
        if (coordinators.size() == 1) {
            coordinatorNode = coordinators.get(0);
        } else if (coordinators.isEmpty() && agents.size() == 1) {
            coordinatorNode = agents.get(0);
        } else if (coordinators.isEmpty()) {
            throw new IllegalArgumentException(
                    "Flow has multiple agents but no coordinator — mark exactly one agent as coordinator.");
        } else {
            throw new IllegalArgumentException("Flow has more than one coordinator — mark exactly one.");
        }

        AgentSpec coordinator = buildAgentSpec(coordinatorNode, flow, mcps, repos, sqls);

        // Every agent reachable from the coordinator through agent-to-agent edges — not just the
        // directly linked ones. A reviewer wired behind an engineer exists to review that
        // engineer's work; it was previously dropped from the run entirely.
        Delegation tree = delegationTree(coordinatorNode, agents, flow.edgesOrEmpty());
        List<AgentSpec> subAgents = new ArrayList<>();
        for (FlowNode n : tree.ordered()) {
            subAgents.add(buildAgentSpec(n, flow, mcps, repos, sqls));
        }

        assignCliNames(coordinator, subAgents);
        assignRosters(coordinator, subAgents, coordinatorNode, tree);
        return new CompiledFlow(coordinator, subAgents);
    }

    /** Agents reachable from the coordinator, plus who delegates to whom. */
    private record Delegation(List<FlowNode> ordered, Map<String, String> delegatorOf) {
    }

    /**
     * Builds the delegation hierarchy by walking out from the coordinator breadth-first.
     *
     * <p>Edges are followed in <b>either</b> direction — wiring has always been direction-agnostic
     * on this canvas, and requiring a particular direction would silently drop flows drawn the
     * other way. Direction is instead derived from the walk: whichever agent first reaches another
     * becomes its delegator. That gives an unambiguous tree from any drawing, and each node is
     * visited once, so a cycle terminates rather than looping forever.
     */
    private static Delegation delegationTree(FlowNode coordinator, List<FlowNode> agents,
                                             List<FlowEdge> edges) {
        Map<String, FlowNode> byId = new LinkedHashMap<>();
        for (FlowNode n : agents) byId.put(n.id(), n);

        List<FlowNode> ordered = new ArrayList<>();
        Map<String, String> delegatorOf = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        seen.add(coordinator.id());
        Deque<String> queue = new ArrayDeque<>();
        queue.add(coordinator.id());

        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (String next : adjacentAgents(current, byId.keySet(), edges)) {
                if (!seen.add(next)) continue;
                FlowNode node = byId.get(next);
                if (node == null) continue;
                ordered.add(node);
                delegatorOf.put(next, current);
                queue.add(next);
            }
        }
        return new Delegation(ordered, delegatorOf);
    }

    /** Agent nodes joined to this one by an edge, in either direction. */
    private static List<String> adjacentAgents(String agentId, Set<String> agentIds, List<FlowEdge> edges) {
        List<String> out = new ArrayList<>();
        for (FlowEdge e : edges) {
            String other = null;
            if (agentId.equals(e.source()) && agentIds.contains(e.target())) other = e.target();
            else if (agentId.equals(e.target()) && agentIds.contains(e.source())) other = e.source();
            if (other != null && !out.contains(other)) out.add(other);
        }
        return out;
    }

    /**
     * Gives every agent a unique CLI name. Duplicates get a numeric suffix rather than being
     * rejected: two "Code Reviewer" nodes reviewing different agents is a reasonable thing to
     * draw, but they cannot share a definition file.
     */
    private static void assignCliNames(AgentSpec coordinator, List<AgentSpec> subAgents) {
        Set<String> taken = new HashSet<>();
        coordinator.cliName = uniqueCliName(coordinator.name, taken);
        for (AgentSpec s : subAgents) {
            s.cliName = uniqueCliName(s.name, taken);
        }
    }

    private static String uniqueCliName(String name, Set<String> taken) {
        String base = LocalClaudeExecutor.sanitize(name);
        if (taken.add(base)) return base;
        for (int i = 2; ; i++) {
            String candidate = base + "-" + i;
            if (taken.add(candidate)) return candidate;
        }
    }

    /** Gives each agent the agents it delegates to, from the hierarchy the walk derived. */
    private static void assignRosters(AgentSpec coordinator, List<AgentSpec> subAgents,
                                      FlowNode coordinatorNode, Delegation tree) {
        Map<String, AgentSpec> byNodeId = new LinkedHashMap<>();
        byNodeId.put(coordinatorNode.id(), coordinator);
        for (AgentSpec s : subAgents) byNodeId.put(s.nodeId, s);

        for (Map.Entry<String, String> entry : tree.delegatorOf().entrySet()) {
            AgentSpec delegator = byNodeId.get(entry.getValue());
            AgentSpec delegate = byNodeId.get(entry.getKey());
            if (delegator == null || delegate == null) continue;
            if (!delegator.delegatesTo.contains(delegate.cliName)) {
                delegator.delegatesTo.add(delegate.cliName);
            }
        }
    }

    private AgentSpec buildAgentSpec(FlowNode node, FlowGraph flow,
                                     List<FlowNode> mcps, List<FlowNode> repos, List<FlowNode> sqls) {
        Map<String, Object> d = node.dataOrEmpty();
        AgentSpec s = new AgentSpec();
        s.mode = flow.modeOrDefault();
        s.nodeId = node.id();
        s.name = str(d, "name", node.id());
        s.description = str(d, "description", "");
        s.systemPrompt = str(d, "systemPrompt", "");
        s.contextFolders = strList(d, "contextFolders");
        s.claudeMdPath = str(d, "claudeMdPath", "");

        s.model = new ModelSpec();
        s.model.id = str(d, "model", "claude-opus-4-8");
        s.model.maxTokens = lng(d, "maxTokens", 16000);
        s.model.effort = str(d, "effort", "high");

        collectConnected(flow, node, mcps, s.mcpServers, mcp -> {
            Map<String, Object> md = mcp.dataOrEmpty();
            McpServerSpec spec = new McpServerSpec();
            spec.nodeId = mcp.id();
            spec.name = str(md, "name", mcp.id());
            spec.url = str(md, "url", "");
            spec.tokenEnv = str(md, "tokenEnv", null);
            return spec;
        });
        collectConnected(flow, node, repos, s.repositories, repo -> {
            Map<String, Object> rd = repo.dataOrEmpty();
            RepoSpec spec = new RepoSpec();
            spec.provider = str(rd, "provider", "github");
            spec.url = str(rd, "url", "");
            spec.tokenEnv = str(rd, "tokenEnv", null);
            spec.mountPath = str(rd, "mountPath", null);
            spec.branch = str(rd, "branch", null);
            return spec;
        });
        collectConnected(flow, node, sqls, s.ragSources, sql -> {
            Map<String, Object> qd = sql.dataOrEmpty();
            SqlSourceSpec spec = new SqlSourceSpec();
            spec.nodeId = sql.id();
            spec.label = str(qd, "label", sql.id());
            spec.jdbcUrl = str(qd, "jdbcUrl", "");
            spec.username = str(qd, "username", null);
            spec.passwordEnv = str(qd, "passwordEnv", null);
            spec.query = str(qd, "query", "");
            spec.maxRows = (int) lng(qd, "maxRows", 50);
            return spec;
        });

        s.validate();
        return s;
    }

    /**
     * Shared iteration/connection-check for the three resource-node loops in {@link
     * #buildAgentSpec}: for each candidate node wired to {@code node}, build a spec via {@code
     * specBuilder} and append it to {@code target}.
     */
    private static <T> void collectConnected(FlowGraph flow, FlowNode node, List<FlowNode> candidates,
                                              List<T> target, Function<FlowNode, T> specBuilder) {
        for (FlowNode candidate : candidates) {
            if (connected(flow.edgesOrEmpty(), node.id(), candidate.id())) {
                target.add(specBuilder.apply(candidate));
            }
        }
    }

    private static List<FlowNode> byType(FlowGraph flow, String type) {
        return flow.nodesOrEmpty().stream().filter(n -> type.equalsIgnoreCase(n.type())).toList();
    }

    private static boolean connected(List<FlowEdge> edges, String a, String b) {
        return edges.stream().anyMatch(e ->
                (a.equals(e.source()) && b.equals(e.target())) || (b.equals(e.source()) && a.equals(e.target())));
    }
}
