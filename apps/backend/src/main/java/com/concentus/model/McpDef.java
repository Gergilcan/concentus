package com.concentus.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Map;

/**
 * A reusable MCP server definition, selectable when configuring an MCP node.
 *
 * <p>Two transports, told apart by which fields are filled. A remote server has a {@code url}
 * (HTTP/SSE, optionally authenticated by a stored credential). A local one has a {@code command}
 * — the npx/python invocation its README says to put in {@code mcp.json} — with {@code args} and
 * {@code env}. An env VALUE of the form {@code credential:<id>} is resolved from the credential
 * store when the run's config is written, so tokens can stay encrypted instead of sitting in this
 * record; any other value passes through as typed.
 */
public record McpDef(String id, String name, String url, String credentialId, String authHeader,
                     String command, List<String> args, Map<String, String> env, String groupId) {

    // A static factory rather than a convenience constructor, and no @JsonCreator anywhere —
    // both deliberately. Jackson's record support binds the canonical constructor by itself; a
    // second constructor derails that introspection, and annotating the canonical one switches
    // to a mode with its own demands. Either way the store silently read every row as
    // unreadable and came back empty. A factory is invisible to Jackson. The one secondary
    // constructor below is taken out of the running with @JsonIgnore, the way FlowGraph does it.

    /** The pre-groups shape: a server the whole organization sees. */
    @JsonIgnore
    public McpDef(String id, String name, String url, String credentialId, String authHeader,
                  String command, List<String> args, Map<String, String> env) {
        this(id, name, url, credentialId, authHeader, command, args, env, null);
    }

    /** The original URL-server shape; the stdio fields default to none. */
    public static McpDef http(String id, String name, String url, String credentialId, String authHeader) {
        return new McpDef(id, name, url, credentialId, authHeader, null, null, null, null);
    }

    // Ignored, not stored: Jackson otherwise writes this derived getter as a "stdio" property,
    // which a strict mapper then refuses on the way back in — every stored row read as
    // unreadable, an empty registry, and nothing anywhere saying why.
    @JsonIgnore
    public boolean isStdio() {
        return command != null && !command.isBlank();
    }

    public McpDef withId(String newId) {
        return new McpDef(newId, name, url, credentialId, authHeader, command, args, env, groupId);
    }
}
