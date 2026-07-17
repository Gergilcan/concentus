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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

        // Only agents LINKED to the coordinator become its sub-agents. This lets one canvas hold
        // several agents where each coordinator delegates to just the ones it's wired to, and each
        // sub-agent is fed its own data via the resource nodes edged to it.
        List<AgentSpec> subAgents = new ArrayList<>();
        for (FlowNode n : agents) {
            if (n == coordinatorNode) continue;
            if (connected(flow.edgesOrEmpty(), coordinatorNode.id(), n.id())) {
                subAgents.add(buildAgentSpec(n, flow, mcps, repos, sqls));
            }
        }
        return new CompiledFlow(coordinator, subAgents);
    }

    private AgentSpec buildAgentSpec(FlowNode node, FlowGraph flow,
                                     List<FlowNode> mcps, List<FlowNode> repos, List<FlowNode> sqls) {
        Map<String, Object> d = node.dataOrEmpty();
        AgentSpec s = new AgentSpec();
        s.mode = flow.modeOrDefault();
        s.name = str(d, "name", node.id());
        s.description = str(d, "description", "");
        s.systemPrompt = str(d, "systemPrompt", "");

        s.model = new ModelSpec();
        s.model.id = str(d, "model", "claude-opus-4-8");
        s.model.maxTokens = lng(d, "maxTokens", 16000);
        s.model.effort = str(d, "effort", "high");

        for (FlowNode mcp : mcps) {
            if (connected(flow.edgesOrEmpty(), node.id(), mcp.id())) {
                Map<String, Object> md = mcp.dataOrEmpty();
                McpServerSpec spec = new McpServerSpec();
                spec.name = str(md, "name", mcp.id());
                spec.url = str(md, "url", "");
                spec.tokenEnv = str(md, "tokenEnv", null);
                s.mcpServers.add(spec);
            }
        }
        for (FlowNode repo : repos) {
            if (connected(flow.edgesOrEmpty(), node.id(), repo.id())) {
                Map<String, Object> rd = repo.dataOrEmpty();
                RepoSpec spec = new RepoSpec();
                spec.provider = str(rd, "provider", "github");
                spec.url = str(rd, "url", "");
                spec.tokenEnv = str(rd, "tokenEnv", null);
                spec.mountPath = str(rd, "mountPath", null);
                spec.branch = str(rd, "branch", null);
                s.repositories.add(spec);
            }
        }
        for (FlowNode sql : sqls) {
            if (connected(flow.edgesOrEmpty(), node.id(), sql.id())) {
                Map<String, Object> qd = sql.dataOrEmpty();
                SqlSourceSpec spec = new SqlSourceSpec();
                spec.label = str(qd, "label", sql.id());
                spec.jdbcUrl = str(qd, "jdbcUrl", "");
                spec.username = str(qd, "username", null);
                spec.passwordEnv = str(qd, "passwordEnv", null);
                spec.query = str(qd, "query", "");
                spec.maxRows = (int) lng(qd, "maxRows", 50);
                s.ragSources.add(spec);
            }
        }

        s.validate();
        return s;
    }

    private static List<FlowNode> byType(FlowGraph flow, String type) {
        return flow.nodesOrEmpty().stream().filter(n -> type.equalsIgnoreCase(n.type())).toList();
    }

    private static boolean connected(List<FlowEdge> edges, String a, String b) {
        return edges.stream().anyMatch(e ->
                (a.equals(e.source()) && b.equals(e.target())) || (b.equals(e.source()) && a.equals(e.target())));
    }

    private static String str(Map<String, Object> d, String key, String fallback) {
        Object v = d.get(key);
        if (v == null) return fallback;
        String s = String.valueOf(v);
        return s.isBlank() ? fallback : s;
    }

    private static long lng(Map<String, Object> d, String key, long fallback) {
        Object v = d.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String str && !str.isBlank()) {
            try { return Long.parseLong(str.trim()); } catch (NumberFormatException ignored) { }
        }
        return fallback;
    }
}
