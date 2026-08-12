package com.concentus.web;

import com.concentus.auth.OrgContext;
import com.concentus.llm.ChatTypes;
import com.concentus.llm.McpClient;
import com.concentus.llm.McpOAuthStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists what an MCP server can actually do, so a node picks from a list instead of guessing names.
 *
 * <p>Typing tool names by hand does not survive contact with a real server: Holded's exposes 338,
 * and a name that is nearly right silently selects nothing. The designer needs the same list the
 * run will see.
 */
@RestController
@RequestMapping("/api/mcp")
public class McpToolsController {

    private final McpOAuthStore oauthStore;
    private final OrgContext orgContext;
    private final ObjectMapper mapper;

    public McpToolsController(McpOAuthStore oauthStore, OrgContext orgContext, ObjectMapper mapper) {
        this.oauthStore = oauthStore;
        this.orgContext = orgContext;
        this.mapper = mapper;
    }

    /** One tool as the picker shows it. The schema is not sent — the list would be enormous. */
    public record ToolInfo(String name, String description) {
    }

    /**
     * @param credentialId optional stored token; when absent the deployment's OAuth grant is used,
     *                     exactly as a run does — so the list here matches what the run will get
     */
    @GetMapping("/tools")
    public Map<String, Object> tools(@RequestParam String url,
                                     @RequestParam(required = false) String credentialId) {
        orgContext.requireAdmin();
        String mcpUrl = url == null ? "" : url.trim();
        Map<String, Object> out = new LinkedHashMap<>();
        if (mcpUrl.isBlank()) {
            out.put("ok", false);
            out.put("error", "This node has no URL yet.");
            return out;
        }

        // The OAuth grant first: it exists only because someone deliberately signed this server in,
        // and the store vouches for it (a stale one comes back empty). The node's static token is
        // the fallback — the old order let a leftover credential shadow a working grant, and an
        // OAuth-only server (Holded) answered the pasted token with a 401 nobody could explain.
        String token = oauthStore.accessToken(orgContext.defaultOrganizationId(), mcpUrl).orElse(null);
        if ((token == null || token.isBlank()) && credentialId != null && !credentialId.isBlank()) {
            token = com.concentus.config.AgentSpec.resolveCredentialForLookup(credentialId);
        }

        try (McpClient client = new McpClient("picker", mcpUrl, token, mapper)) {
            List<ToolInfo> tools = new ArrayList<>();
            for (ChatTypes.ToolSpec t : client.listTools()) {
                tools.add(new ToolInfo(t.name(), shorten(t.description())));
            }
            out.put("ok", true);
            out.put("tools", tools);
            return out;
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? "The server could not be read." : e.getMessage();
            out.put("ok", false);
            // A 401 is a state the UI can FIX (start the app's own OAuth sign-in), not just
            // report. Named explicitly so the dialog offers the button instead of a dead error.
            // The claude CLI's grant ("Connected in Claude Code") cannot help here — the CLI
            // keeps its authorizations to itself; this endpoint connects directly.
            if (msg.contains(" 401") || msg.toLowerCase(java.util.Locale.ROOT).contains("unauthorized")) {
                out.put("needsAuth", true);
                out.put("error", token == null
                        ? "This server requires a sign-in."
                        : "The server rejected the stored authorization — sign in again.");
            } else {
                out.put("error", msg);
            }
            return out;
        }
    }

    /** One line per tool in a list of hundreds; the full text belongs in the prompt, not here. */
    private static String shorten(String description) {
        if (description == null) return "";
        String first = description.strip();
        int stop = first.indexOf('\n');
        if (stop > 0) first = first.substring(0, stop);
        return first.length() <= 140 ? first : first.substring(0, 140) + "…";
    }
}
