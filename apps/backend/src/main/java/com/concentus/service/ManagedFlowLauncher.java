package com.concentus.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.beta.agents.AgentCreateParams;
import com.anthropic.models.beta.agents.BetaManagedAgentsAgent;
import com.anthropic.models.beta.agents.BetaManagedAgentsAgentToolset20260401Params;
import com.anthropic.models.beta.agents.BetaManagedAgentsMcpToolsetParams;
import com.anthropic.models.beta.agents.BetaManagedAgentsModel;
import com.anthropic.models.beta.agents.BetaManagedAgentsUrlMcpServerParams;
import com.anthropic.models.beta.environments.BetaCloudConfigParams;
import com.anthropic.models.beta.environments.BetaUnrestrictedNetwork;
import com.anthropic.models.beta.environments.EnvironmentCreateParams;
import com.anthropic.models.beta.sessions.BetaManagedAgentsAgentParams;
import com.anthropic.models.beta.sessions.BetaManagedAgentsGitHubRepositoryResourceParams;
import com.anthropic.models.beta.sessions.SessionCreateParams;
import com.concentus.config.AgentSpec;
import com.concentus.config.AgentSpec.McpServerSpec;
import com.concentus.config.AgentSpec.RepoSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Provisions a Managed-Agents multi-agent session from a {@link CompiledFlow}:
 * one managed agent per sub-agent, a coordinator agent whose {@code multiagent}
 * roster references them, and a session that mounts the flow's GitHub repos.
 *
 * <p>Creates fresh agents/environment per launch (demo simplicity). In production
 * these are persistent, versioned resources created once.
 */
@Component
public class ManagedFlowLauncher {

    private static final Logger log = LoggerFactory.getLogger(ManagedFlowLauncher.class);

    private final RagContextInjector ragInjector;

    public ManagedFlowLauncher(RagContextInjector ragInjector) {
        this.ragInjector = ragInjector;
    }

    public record LaunchResult(String sessionId, String coordinatorId,
                               List<String> subAgentIds, String environmentId) {
    }

    public LaunchResult launch(AnthropicClient client, CompiledFlow flow, Consumer<String> emit) {
        var environment = client.beta().environments().create(EnvironmentCreateParams.builder()
                .name("flow-env-" + Long.toString(System.currentTimeMillis(), 36))
                .config(BetaCloudConfigParams.builder()
                        .networking(BetaUnrestrictedNetwork.builder().build())
                        .build())
                .build());

        // 1. Create each sub-agent first, collect their IDs for the roster.
        List<String> subIds = new ArrayList<>();
        for (AgentSpec sub : flow.subAgents()) {
            ragInjector.inject(sub, emit);
            BetaManagedAgentsAgent a = client.beta().agents().create(agentParams(sub, null));
            subIds.add(a.id());
        }

        // 2. Create the coordinator referencing the roster.
        ragInjector.inject(flow.coordinator(), emit);
        BetaManagedAgentsAgent coordinator = client.beta().agents().create(agentParams(flow.coordinator(), subIds));

        // 3. Start a session on the coordinator, mounting repos.
        var sessionBuilder = SessionCreateParams.builder()
                .agent(BetaManagedAgentsAgentParams.builder()
                        .type(BetaManagedAgentsAgentParams.Type.AGENT)
                        .id(coordinator.id())
                        .version(coordinator.version())
                        .build())
                .environmentId(environment.id())
                .title(flow.coordinator().name);

        for (RepoSpec repo : flow.allRepos()) {
            if (repo.provider() != AgentSpec.RepoProvider.GITHUB) {
                log.info("repo {} is {} -> not natively mounted; reachable via its MCP server", repo.url, repo.provider);
                continue;
            }
            String token = repo.resolveToken();
            if (token == null) {
                log.warn("github repo {} has no token ({} unset) -> skipping mount", repo.url, repo.tokenEnv);
                continue;
            }
            var res = BetaManagedAgentsGitHubRepositoryResourceParams.builder()
                    .type(BetaManagedAgentsGitHubRepositoryResourceParams.Type.GITHUB_REPOSITORY)
                    .url(repo.url)
                    .authorizationToken(token);
            if (repo.mountPath != null && !repo.mountPath.isBlank()) {
                res.mountPath(repo.mountPath);
            }
            sessionBuilder.addResource(res.build());
        }

        var session = client.beta().sessions().create(sessionBuilder.build());
        return new LaunchResult(session.id(), coordinator.id(), subIds, environment.id());
    }

    private AgentCreateParams agentParams(AgentSpec spec, List<String> rosterIds) {
        var builder = AgentCreateParams.builder()
                .name(spec.name)
                .model(BetaManagedAgentsModel.of(spec.model.id))
                .system(spec.systemPrompt)
                .addTool(BetaManagedAgentsAgentToolset20260401Params.builder()
                        .type(BetaManagedAgentsAgentToolset20260401Params.Type.AGENT_TOOLSET_20260401)
                        .build());

        for (McpServerSpec mcp : spec.mcpServers) {
            builder.addMcpServer(BetaManagedAgentsUrlMcpServerParams.builder()
                    .type(BetaManagedAgentsUrlMcpServerParams.Type.URL)
                    .name(mcp.name)
                    .url(mcp.url)
                    .build());
            builder.addTool(BetaManagedAgentsMcpToolsetParams.builder()
                    .type(BetaManagedAgentsMcpToolsetParams.Type.MCP_TOOLSET)
                    .mcpServerName(mcp.name)
                    .build());
        }

        // Coordinator: attach the sub-agent roster (raw JSON keeps us tolerant of
        // SDK version differences in the multiagent typed builder).
        if (rosterIds != null && !rosterIds.isEmpty()) {
            builder.putAdditionalBodyProperty("multiagent",
                    JsonValue.from(Map.of("type", "coordinator", "agents", List.copyOf(rosterIds))));
        }

        return builder.build();
    }
}
