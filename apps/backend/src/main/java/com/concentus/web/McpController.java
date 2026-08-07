package com.concentus.web;

import com.concentus.config.AgentSpec.McpServerSpec;
import com.concentus.model.McpServerInfo;
import com.concentus.service.McpRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Lists and registers MCP servers in the local Claude Code CLI. */
@RestController
@RequestMapping("/api/mcp")
public class McpController {

    private final McpRegistry registry;

    public McpController(McpRegistry registry) {
        this.registry = registry;
    }

    /** MCP servers already configured in Claude Code (for the "select existing" dropdown). */
    @GetMapping("/servers")
    public List<McpServerInfo> servers() {
        return registry.list();
    }

    /**
     * What this host can do about MCP authentication, so the UI offers the route that works.
     *
     * <p>{@code interactiveLogin} is about the {@code claude mcp login} path only: that one needs a
     * terminal and a browser on the host, which a container has neither of. It being false no
     * longer means "you cannot use an OAuth server here" — Concentus obtains its own grant through
     * the browser you are already using (see {@code McpOAuthController}), and that works headlessly.
     *
     * <p>The distinction matters because the advice used to be "use an access token instead", and
     * for an OAuth-protected server that is not merely inconvenient — it cannot work. Holded's MCP
     * answers an unauthenticated request with an RFC 9728 challenge and refuses static tokens
     * outright, so following that advice means an afternoon spent looking for a token that does
     * not exist.
     */
    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        boolean interactive = registry.supportsInteractiveLogin();
        return Map.of(
                "interactiveLogin", interactive,
                "hint", interactive ? ""
                        : "The claude CLI's own sign-in needs a desktop, which this deployment does "
                          + "not have — but that is only one of two routes. For a server that uses "
                          + "OAuth (Holded, Atlassian, Linear), press \"Sign in to this server\" on "
                          + "the MCP node: Concentus gets its own authorization through this "
                          + "browser, which works headlessly. A server that accepts a static token "
                          + "needs neither — store it under Resources → Credentials and select it "
                          + "on the node.");
    }

    /** Registers an MCP server into Claude Code (user scope), with its stored credential. */
    @PostMapping("/servers")
    public Map<String, String> add(@RequestBody McpServerSpec spec) {
        if (spec == null || spec.name == null || spec.name.isBlank()
                || spec.url == null || spec.url.isBlank()) {
            throw new IllegalArgumentException("name and url are required.");
        }
        String status = registry.add(spec.name, spec.url, spec.resolveToken(), spec.authHeader);
        return Map.of("name", spec.name, "status", status);
    }

    /**
     * Launches a terminal window to run the interactive OAuth sign-in for a server.
     *
     * <p>The name reaches a spawned console, so it is validated here first: letters, digits,
     * space, dot, dash and underscore only (1–64 chars). That admits every real server name
     * ("Linear", "claude.ai Google Drive") while excluding the shell/batch metacharacters an
     * injected command would need. Anything else is rejected with 400.
     */
    @PostMapping("/servers/login")
    public Map<String, String> login(@RequestBody Map<String, String> body) {
        String name = requireName(body);
        if (!McpRegistry.isSafeName(name)) {
            throw new IllegalArgumentException(
                    "name may only contain letters, digits, spaces, dots, dashes or underscores (max 64).");
        }
        return Map.of("name", name, "status", registry.login(name));
    }

    private static String requireName(Map<String, String> body) {
        String name = body == null ? null : body.get("name");
        if (name != null) name = name.trim();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required.");
        }
        return name;
    }

    /** Removes a server from the Claude Code list (used to clear duplicates/broken entries). */
    @PostMapping("/servers/remove")
    public Map<String, String> remove(@RequestBody Map<String, String> body) {
        // `remove` passes the name to the CLI as a discrete argv entry (never through a shell),
        // so it isn't charset-restricted — an already-registered server must stay removable.
        String name = requireName(body);
        return Map.of("name", name, "status", registry.remove(name));
    }
}

