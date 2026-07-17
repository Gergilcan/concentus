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

    /** Registers an MCP server into Claude Code (user scope). Token, if any, is read from tokenEnv. */
    @PostMapping("/servers")
    public Map<String, String> add(@RequestBody McpServerSpec spec) {
        if (spec == null || spec.name == null || spec.name.isBlank()
                || spec.url == null || spec.url.isBlank()) {
            throw new IllegalArgumentException("name and url are required.");
        }
        String status = registry.add(spec.name, spec.url, spec.resolveToken());
        return Map.of("name", spec.name, "status", status);
    }

    /** Launches a terminal window to run the interactive OAuth sign-in for a server. */
    @PostMapping("/servers/login")
    public Map<String, String> login(@RequestBody Map<String, String> body) {
        String name = body == null ? null : body.get("name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required.");
        }
        return Map.of("name", name, "status", registry.login(name));
    }

    /** Removes a server from the Claude Code list (used to clear duplicates/broken entries). */
    @PostMapping("/servers/remove")
    public Map<String, String> remove(@RequestBody Map<String, String> body) {
        String name = body == null ? null : body.get("name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required.");
        }
        return Map.of("name", name, "status", registry.remove(name));
    }
}

