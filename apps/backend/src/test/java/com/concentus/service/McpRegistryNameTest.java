package com.concentus.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WIR-6: the MCP server name reaches a spawned terminal, so it must never be able to carry a
 * shell/batch payload. These cases pin the charset rule at the boundary.
 */
class McpRegistryNameTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Linear",
            "github",
            "claude.ai Google Drive",
            "claude.ai Atlassian Rovo",
            "my-server_2.0",
    })
    @DisplayName("real MCP server names are accepted")
    void acceptsLegitimateNames(String name) {
        assertTrue(McpRegistry.isSafeName(name), () -> "should accept: " + name);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Linear\" & calc & \"",   // break out of the quoted batch argument
            "a & whoami",
            "a | whoami",
            "a; whoami",
            "a `whoami`",
            "a $(whoami)",
            "%PATH%",                 // batch variable expansion
            "a\nwhoami",              // second command on a new line
            "a\r\nwhoami",
            "a > out.txt",
            "a\\b",
            "'; rm -rf /",
            "",                       // empty
    })
    @DisplayName("names carrying shell or batch metacharacters are rejected")
    void rejectsInjectionPayloads(String name) {
        assertFalse(McpRegistry.isSafeName(name), () -> "should reject: " + name);
    }

    @Test
    @DisplayName("null and over-long names are rejected; 64 chars is the limit")
    void rejectsNullAndOverlongNames() {
        assertFalse(McpRegistry.isSafeName(null));
        assertFalse(McpRegistry.isSafeName("a".repeat(65)));
        assertTrue(McpRegistry.isSafeName("a".repeat(64)));
    }
}
