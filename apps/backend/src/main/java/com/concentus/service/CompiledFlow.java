package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.config.AgentSpec.RepoSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A flow reduced to executable specs: one coordinator plus its sub-agent roster, and — for
 * fan-out flows — an optional adversarial verifier and an optional merge step.
 */
public record CompiledFlow(AgentSpec coordinator, List<AgentSpec> subAgents, AgentSpec merger,
                           AgentSpec verifier) {

    /** The shape every caller had before the merge node existed: no merger, no verifier. */
    public CompiledFlow(AgentSpec coordinator, List<AgentSpec> subAgents) {
        this(coordinator, subAgents, null, null);
    }

    /** The shape every caller had before the verifier node existed: no verifier. */
    public CompiledFlow(AgentSpec coordinator, List<AgentSpec> subAgents, AgentSpec merger) {
        this(coordinator, subAgents, merger, null);
    }

    /**
     * Whether this flow runs its sub-agents as independent {@code claude} processes (fan-out)
     * instead of as Claude Code subagents inside the coordinator's session. Read from the
     * coordinator because that is the node whose job the setting changes.
     */
    public boolean fanout() {
        return "fanout".equalsIgnoreCase(coordinator.execution);
    }

    /**
     * Coordinator first, then sub-agents — the walk six call sites each wrote by hand, some
     * de-duplicating differently from others while iterating the same collection.
     */
    public List<AgentSpec> allAgents() {
        List<AgentSpec> all = new ArrayList<>(1 + subAgents.size());
        all.add(coordinator);
        all.addAll(subAgents);
        return all;
    }

    /**
     * Display name for an agent node id, or null when no agent has it. The scan both stream
     * handlers kept privately — which meant cloud and local runs could label the same node
     * differently.
     */
    public String agentName(String nodeId) {
        if (nodeId == null) return null;
        if (nodeId.equals(coordinator.nodeId)) return coordinator.name;
        for (AgentSpec s : subAgents) {
            if (nodeId.equals(s.nodeId)) return s.name;
        }
        return null;
    }

    /** All repository mounts across coordinator + sub-agents, de-duplicated by URL. */
    public List<RepoSpec> allRepos() {
        Map<String, RepoSpec> byUrl = new LinkedHashMap<>();
        collect(coordinator, byUrl);
        for (AgentSpec s : subAgents) collect(s, byUrl);
        return new ArrayList<>(byUrl.values());
    }

    private static void collect(AgentSpec spec, Map<String, RepoSpec> byUrl) {
        for (RepoSpec r : spec.repositories) {
            byUrl.putIfAbsent(r.url, r);
        }
    }
}
