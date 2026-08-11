package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.config.AgentSpec.McpServerSpec;
import com.concentus.config.AgentSpec.ModelSpec;
import com.concentus.config.AgentSpec.RepoSpec;
import com.concentus.config.AgentSpec.KnowledgeSourceSpec;
import com.concentus.config.AgentSpec.SqlSourceSpec;
import com.concentus.model.FlowEdge;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import com.concentus.support.Variables;
import org.springframework.stereotype.Component;

import static com.concentus.support.MapValues.bool;
import static com.concentus.support.MapValues.lng;
import static com.concentus.support.MapValues.str;
import static com.concentus.support.MapValues.strList;
import static com.concentus.support.MapValues.strMap;

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
        return compile(flow, Map.of(), null);
    }

    /**
     * Compiles with {@code {{NAME}}} substitution over every prompt-shaped field: system prompts,
     * routing descriptions, and the merge instructions. Values come merged already (organization
     * under the flow's own — see VariableStore.merged); names nothing defines stay verbatim in
     * the prompt and land in {@code unresolved}, so the run can say so instead of an agent
     * quietly receiving a hole.
     */
    public CompiledFlow compile(FlowGraph flow, Map<String, String> variables, Set<String> unresolved) {
        CompiledFlow compiled = compileRaw(flow);
        if (!variables.isEmpty() || unresolved != null) {
            List<AgentSpec> all = new ArrayList<>(compiled.allAgents());
            if (compiled.merger() != null) all.add(compiled.merger());
            if (compiled.verifier() != null) all.add(compiled.verifier());
            for (AgentSpec spec : all) {
                spec.systemPrompt = Variables.substitute(spec.systemPrompt, variables, unresolved);
                spec.description = Variables.substitute(spec.description, variables, unresolved);
            }
        }
        return compiled;
    }

    private CompiledFlow compileRaw(FlowGraph flow) {
        List<FlowNode> agents = byType(flow, "agent");
        List<FlowNode> mcps = byType(flow, "mcp");
        List<FlowNode> repos = byType(flow, "repo");
        List<FlowNode> sqls = byType(flow, "sql");
        List<FlowNode> knowledges = byType(flow, "knowledge");
        List<FlowNode> apis = byType(flow, "api");

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

        AgentSpec coordinator = buildAgentSpec(coordinatorNode, flow, mcps, repos, sqls, knowledges, apis);

        // Every agent reachable from the coordinator through agent-to-agent edges — not just the
        // directly linked ones. A reviewer wired behind an engineer exists to review that
        // engineer's work; it was previously dropped from the run entirely.
        Delegation tree = delegationTree(coordinatorNode, agents, flow.edgesOrEmpty());
        List<AgentSpec> subAgents = new ArrayList<>();
        for (FlowNode n : tree.ordered()) {
            subAgents.add(buildAgentSpec(n, flow, mcps, repos, sqls, knowledges, apis));
        }

        assignCliNames(coordinator, subAgents);
        assignRosters(coordinator, subAgents, coordinatorNode, tree);

        // The merge step of a fan-out flow. Same data shape as an agent node, so the same spec
        // builder serves; at most one, because "the merge" must be a single answer.
        List<FlowNode> merges = byType(flow, "merge");
        if (merges.size() > 1) {
            throw new IllegalArgumentException("Flow has more than one merge node — keep exactly one.");
        }
        AgentSpec merger = merges.isEmpty()
                ? null
                : buildAgentSpec(merges.get(0), flow, mcps, repos, sqls, knowledges, apis);

        // The adversarial verifier of a fan-out flow, cut from the same cloth as the merge: at
        // most one, because a worker's output must get ONE verdict, not a committee's.
        List<FlowNode> verifiers = byType(flow, "verifier");
        if (verifiers.size() > 1) {
            throw new IllegalArgumentException(
                    "Flow has more than one verifier node — keep exactly one.");
        }
        AgentSpec verifier = verifiers.isEmpty()
                ? null
                : buildAgentSpec(verifiers.get(0), flow, mcps, repos, sqls, knowledges, apis);

        return new CompiledFlow(coordinator, subAgents, merger, verifier);
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
                                     List<FlowNode> mcps, List<FlowNode> repos, List<FlowNode> sqls,
                                     List<FlowNode> knowledges, List<FlowNode> apis) {
        Map<String, Object> d = node.dataOrEmpty();
        AgentSpec s = new AgentSpec();
        s.mode = flow.modeOrDefault();
        s.nodeId = node.id();
        s.name = str(d, "name", node.id());
        s.tools = strList(d, "tools");
        for (String skillId : strList(d, "skillIds")) {
            AgentSpec.SkillSpec skill = new AgentSpec.SkillSpec();
            skill.type = "custom";
            skill.id = skillId;
            s.skills.add(skill);
        }
        s.description = str(d, "description", "");
        s.systemPrompt = str(d, "systemPrompt", "");
        // Only the coordinator's value is ever read, but it is carried for every agent so the
        // compiler doesn't need to know which node wins — validate() normalizes typos away.
        s.execution = str(d, "execution", "");
        s.facadeProfileId = str(d, "facadeProfileId", "");
        s.coordinatorAccess = str(d, "coordinatorAccess", "");
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
            spec.credentialId = str(md, "credentialId", null);
            spec.authHeader = str(md, "authHeader", null);
            // Which of the server's tools this agent gets. A large server would otherwise put
            // hundreds of JSON schemas in the prompt.
            spec.tools = strList(md, "tools");
            // The stdio transport: the node carries the command its README told the user to put
            // in mcp.json, and the run's config launches it.
            spec.command = str(md, "command", null);
            spec.args = strList(md, "args");
            spec.env = strMap(md, "env");
            return spec;
        });
        collectConnected(flow, node, repos, s.repositories, repo -> {
            Map<String, Object> rd = repo.dataOrEmpty();
            RepoSpec spec = new RepoSpec();
            spec.provider = str(rd, "provider", "github");
            spec.url = str(rd, "url", "");
            spec.credentialId = str(rd, "credentialId", null);
            spec.mountPath = str(rd, "mountPath", null);
            spec.branch = str(rd, "branch", null);
            // A node in group mode names an organization instead of a URL; which repositories that
            // means is resolved when the run starts, not here — the compiler stays offline.
            spec.group = str(rd, "group", null);
            spec.baseUrl = str(rd, "baseUrl", null);
            spec.only = strList(rd, "only");
            spec.includeArchived = bool(rd, "includeArchived", false);
            return spec;
        });
        collectConnected(flow, node, sqls, s.ragSources, sql -> {
            Map<String, Object> qd = sql.dataOrEmpty();
            SqlSourceSpec spec = new SqlSourceSpec();
            spec.nodeId = sql.id();
            spec.label = str(qd, "label", sql.id());
            spec.jdbcUrl = str(qd, "jdbcUrl", "");
            spec.username = str(qd, "username", null);
            spec.credentialId = str(qd, "credentialId", null);
            spec.query = str(qd, "query", "");
            spec.maxRows = (int) lng(qd, "maxRows", 50);
            return spec;
        });
        collectConnected(flow, node, knowledges, s.knowledgeSources, kb -> {
            Map<String, Object> kd = kb.dataOrEmpty();
            KnowledgeSourceSpec spec = new KnowledgeSourceSpec();
            spec.nodeId = kb.id();
            spec.baseId = str(kd, "baseId", "");
            spec.label = str(kd, "label", kb.id());
            spec.topK = (int) lng(kd, "topK", 5);
            return spec;
        });
        collectConnected(flow, node, apis, s.apiSources, api -> {
            Map<String, Object> ad = api.dataOrEmpty();
            AgentSpec.ApiSourceSpec spec = new AgentSpec.ApiSourceSpec();
            spec.nodeId = api.id();
            spec.label = str(ad, "label", api.id());
            spec.specUrl = str(ad, "specUrl", "");
            spec.specInline = str(ad, "specInline", "");
            spec.baseUrl = str(ad, "baseUrl", "");
            spec.credentialId = str(ad, "credentialId", "");
            spec.authHeader = str(ad, "authHeader", "");
            // Explicitly chosen operations only. An empty list exposes nothing — for the one node
            // type that reaches out and changes external systems, allow is opt-in per operation.
            spec.ops = strList(ad, "ops");
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
