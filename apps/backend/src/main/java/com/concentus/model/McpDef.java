package com.concentus.model;

/** A reusable MCP server definition, selectable when configuring an MCP node. */
public record McpDef(String id, String name, String url, String credentialId, String authHeader) {

    public McpDef withId(String newId) {
        return new McpDef(newId, name, url, credentialId, authHeader);
    }
}
