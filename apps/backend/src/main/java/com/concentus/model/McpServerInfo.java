package com.concentus.model;

/** An MCP server configured in the local Claude Code CLI (`claude mcp list`). */
public record McpServerInfo(String name, String url, String status) {
}
