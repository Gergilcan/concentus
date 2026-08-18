package com.concentus.service;

import com.concentus.auth.OrgContext;
import com.concentus.config.AgentSpec.McpServerSpec;
import com.concentus.llm.McpOAuthStore;
import com.concentus.web.RunMcpProxyController;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * One MCP server, in the shape the {@code claude} CLI's config file wants it.
 *
 * <p>Its own class because the answer is no longer a translation of fields: it decides whether the
 * CLI talks to the server directly or through this backend, and that decision is worth testing
 * without a run, a workspace and a CLI on the machine.
 *
 * <p>Auth, in order: the node's stored credential; else an OAuth grant Concentus holds for that
 * URL; else no header, which leaves room for a grant the CLI itself holds from {@code claude mcp
 * login}.
 */
@Component
public class CliMcpServers {

    /** Header the per-run token travels in — the same one the run's other MCP endpoints use. */
    public static final String RUN_TOKEN_HEADER = RunMcpProxyController.TOKEN_HEADER;

    private final McpOAuthStore oauth;
    private final OrgContext orgContext;
    private final ObjectMapper mapper;
    private final int serverPort;

    public CliMcpServers(McpOAuthStore oauth, OrgContext orgContext, ObjectMapper mapper,
                         @Value("${server.port:8734}") int serverPort) {
        this.oauth = oauth;
        this.orgContext = orgContext;
        this.mapper = mapper;
        this.serverPort = serverPort;
    }

    /**
     * @param runToolToken the run's tool token, which the proxy route requires; when it is absent
     *                     an OAuth server is configured the old way rather than pointed at an
     *                     endpoint that would refuse every call
     */
    public ObjectNode node(McpServerSpec m, String runId, String runToolToken) {
        var server = mapper.createObjectNode();

        // The stdio transport: the CLI launches the process itself, per run — the same
        // command/args/env shape the server's README says to put in mcp.json. Env values of the
        // form credential:<id> were resolved just now, so tokens live encrypted in the credential
        // store rather than in the flow.
        if (m.isStdio()) {
            server.put("type", "stdio");
            server.put("command", m.command);
            var args = server.putArray("args");
            for (String arg : m.args) args.add(arg);
            Map<String, String> env = m.resolveEnv();
            if (!env.isEmpty()) {
                var envNode = server.putObject("env");
                env.forEach(envNode::put);
            }
            return server;
        }

        server.put("type", "http");

        String credential = m.resolveToken();
        if (credential != null && !credential.isBlank()) {
            // A token someone pasted is as good as it will ever be, so it goes in the file and the
            // CLI talks to the server directly. Routing it through the proxy would add a hop with
            // nothing to gain: there is no refresh token behind it to renew.
            server.put("url", m.url);
            String header = m.authHeader == null || m.authHeader.isBlank()
                    ? McpRegistry.DEFAULT_AUTH_HEADER : m.authHeader.trim();
            String value = McpRegistry.DEFAULT_AUTH_HEADER.equalsIgnoreCase(header)
                    ? "Bearer " + credential : credential;
            server.putObject("headers").put(header, value);
            return server;
        }

        String granted = oauth.accessToken(orgContext.defaultOrganizationId(), m.url).orElse(null);
        if (granted == null || granted.isBlank()) {
            // Nothing to present. Deliberately no header rather than an empty one, so a grant the
            // CLI holds itself still gets its chance.
            server.put("url", m.url);
            return server;
        }

        if (runToolToken == null || runToolToken.isBlank()) {
            server.put("url", m.url);
            server.putObject("headers").put(McpRegistry.DEFAULT_AUTH_HEADER, "Bearer " + granted);
            return server;
        }

        // The whole point: the access token stays here. The CLI is given a local endpoint and the
        // run's own token, and the grant is resolved per request — so a fifteen-minute token no
        // longer decides how long a run may last.
        server.put("url", "http://127.0.0.1:" + serverPort + RunMcpProxyController.PATH
                .replace("{runId}", encode(runId)) + "/" + encode(m.name));
        server.putObject("headers").put(RUN_TOKEN_HEADER, runToolToken);
        return server;
    }

    /**
     * Percent-encodes one path segment. {@code URLEncoder} is form encoding, where a space becomes
     * {@code +} — which a path reads as a literal plus, so a server named "Google Ads" would be
     * looked up under a name that does not exist.
     */
    private static String encode(String segment) {
        return URLEncoder.encode(segment == null ? "" : segment, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
