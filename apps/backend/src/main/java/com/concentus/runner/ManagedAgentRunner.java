package com.concentus.runner;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.beta.agents.AgentCreateParams;
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
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsUserMessageEventParams;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsStreamSessionEvents;
import com.anthropic.models.beta.sessions.events.EventSendParams;
import com.concentus.config.AgentSpec;
import com.concentus.config.AgentSpec.McpServerSpec;
import com.concentus.config.AgentSpec.RepoSpec;
import com.concentus.config.AgentSpec.SkillSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Managed Agents runner: Anthropic hosts the agent loop and a per-session sandbox.
 * The YAML becomes an agent config (model / system / skills / MCP), plus a session
 * that mounts GitHub repositories.
 *
 * <p>NOTE: This creates the agent + environment on every run for demo simplicity.
 * In production these are persistent, versioned resources — create them once (e.g.
 * via the {@code ant} CLI from checked-in YAML) and reference the stored IDs here.
 * See the README.
 *
 * <p>Prompt caching, compaction and context management are automatic in this mode,
 * so the spec's {@code cache} / {@code context} blocks are not applied here.
 */
final class ManagedAgentRunner implements AgentRunner {

    private final AgentSpec spec;
    private final AnthropicClient client;

    ManagedAgentRunner(AgentSpec spec, AnthropicClient client) {
        this.spec = spec;
        this.client = client;
    }

    @Override
    public void run(String userPrompt) {
        if (!"unrestricted".equals(spec.environment.networking)) {
            System.err.println("[warn] environment.networking='" + spec.environment.networking
                    + "' — this demo wires 'unrestricted' only; falling back. "
                    + "For 'limited', build a limited-network params object with allowedHosts/allowMcpServers.");
        }

        var environment = client.beta().environments().create(EnvironmentCreateParams.builder()
                .name(spec.environment.name)
                .config(BetaCloudConfigParams.builder()
                        .networking(BetaUnrestrictedNetwork.builder().build())
                        .build())
                .build());

        var agent = client.beta().agents().create(buildAgentParams());

        var sessionBuilder = SessionCreateParams.builder()
                .agent(BetaManagedAgentsAgentParams.builder()
                        .type(BetaManagedAgentsAgentParams.Type.AGENT)
                        .id(agent.id())
                        .version(agent.version())
                        .build())
                .environmentId(environment.id())
                .title(spec.name);

        // Native repo mounts (github only; gitlab is reached through its MCP server).
        for (RepoSpec repo : spec.repositories) {
            if (repo.provider() != AgentSpec.RepoProvider.GITHUB) {
                System.err.println("[info] repo " + repo.url + " is provider=" + repo.provider
                        + " -> not natively mounted; use its MCP server.");
                continue;
            }
            String token = repo.resolveToken();
            if (token == null) {
                System.err.println("[warn] github repo " + repo.url + " has no token ("
                        + repo.tokenEnv + " unset) -> skipping mount.");
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

        System.out.println("[managed] agent=" + agent.id() + " session=" + session.id());
        System.out.println("[managed] trace: https://platform.claude.com/workspaces/default/sessions/"
                + session.id());
        System.out.println("----------------------------------------------------------------");

        streamTurn(session.id(), userPrompt);
    }

    private AgentCreateParams buildAgentParams() {
        var builder = AgentCreateParams.builder()
                .name(spec.name)
                .model(BetaManagedAgentsModel.of(spec.model.id))
                .system(spec.systemPrompt)
                .addTool(BetaManagedAgentsAgentToolset20260401Params.builder()
                        .type(BetaManagedAgentsAgentToolset20260401Params.Type.AGENT_TOOLSET_20260401)
                        .build());

        // MCP servers + one mcp_toolset tool per server (github + gitlab, per config).
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
            if (mcp.resolveToken() != null) {
                System.err.println("[info] MCP server '" + mcp.name + "' has a token; managed-mode MCP auth "
                        + "requires a vault (see README). Declared without auth for now.");
            }
        }

        // Skills injected as raw JSON to stay tolerant of SDK version differences.
        if (!spec.skills.isEmpty()) {
            List<Object> skills = new ArrayList<>();
            for (SkillSpec s : spec.skills) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", s.type);
                m.put("skill_id", s.id);
                if (s.version != null && !s.version.isBlank()) m.put("version", s.version);
                skills.add(m);
            }
            builder.putAdditionalBodyProperty("skills", JsonValue.from(skills));
        }

        return builder.build();
    }

    private void streamTurn(String sessionId, String userPrompt) {
        try (var stream = client.beta().sessions().events().streamStreaming(sessionId)) {
            client.beta().sessions().events().send(sessionId, EventSendParams.builder()
                    .addEvent(BetaManagedAgentsUserMessageEventParams.builder()
                            .type(BetaManagedAgentsUserMessageEventParams.Type.USER_MESSAGE)
                            .addTextContent(userPrompt)
                            .build())
                    .build());

            for (BetaManagedAgentsStreamSessionEvents event :
                    (Iterable<BetaManagedAgentsStreamSessionEvents>) stream.stream()::iterator) {
                if (event.isAgentMessage()) {
                    event.asAgentMessage().content().forEach(block -> System.out.print(block.text()));
                } else if (event.isAgentToolUse()) {
                    System.out.println("\n[tool: " + event.asAgentToolUse().name() + "]");
                } else if (event.isSessionStatusIdle()) {
                    System.out.println("\n[idle]");
                    break;
                } else if (event.isSessionError()) {
                    System.out.println("\n[session error]");
                    break;
                }
            }
        }
    }
}
