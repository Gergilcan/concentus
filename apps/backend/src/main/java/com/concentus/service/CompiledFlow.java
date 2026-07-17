package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.config.AgentSpec.RepoSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A flow reduced to executable specs: one coordinator plus its sub-agent roster. */
public record CompiledFlow(AgentSpec coordinator, List<AgentSpec> subAgents) {

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
