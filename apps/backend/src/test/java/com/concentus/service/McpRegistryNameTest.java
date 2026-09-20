package com.concentus.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WIR-6: the MCP server name reaches a spawned terminal, so it must never be able to carry a
 * shell/batch payload. These cases pin the charset rule at the boundary.
 */
class McpRegistryNameTest {

    /**
     * The bug this maps around: {@code claude mcp add} refuses a name with a space, so a block
     * called "Ryze Google Ads" was never registered at all — and the panel said "Not yet in Claude
     * Code" rather than what the CLI actually answered.
     */
    @Test
    @DisplayName("a block's label becomes a name the CLI accepts")
    void mapsDisplayNamesOntoTheCliCharset() {
        // A space becomes an underscore, and only a space does: the interface turns it back and
        // shows the block's own name again.
        assertEquals("Ryze_Google_Ads", McpRegistry.cliName("Ryze Google Ads"));
        assertEquals("claude-ai_Google_Drive", McpRegistry.cliName("claude.ai Google Drive"));
        // Already legal: left exactly as it is, so an existing registration keeps answering.
        assertEquals("Linear", McpRegistry.cliName("Linear"));
        assertEquals("my-server_2", McpRegistry.cliName("my-server_2"));
        // Runs collapse and the edges are trimmed, so the name reads like a name: one gap, one
        // separator, rather than a hyphen left standing where a bracket used to be.
        assertEquals("Ads_write", McpRegistry.cliName("  Ads (write)  "));
        assertEquals("Google_Ads_lectura", McpRegistry.cliName("Google Ads (lectura)"));
        // Nothing usable left: a name the CLI takes beats an error nobody can act on.
        assertEquals("mcp", McpRegistry.cliName("🚀"));
        assertEquals("mcp", McpRegistry.cliName(""));
    }

    @Test
    @DisplayName("whatever it maps to is safe to put in a terminal")
    void mappedNamesAreAlwaysSafe() {
        for (String hostile : new String[] {"a & whoami", "a `whoami`", "%PATH%", "a\nwhoami", "🚀"}) {
            assertTrue(McpRegistry.isSafeName(McpRegistry.cliName(hostile)),
                    () -> "should be safe after mapping: " + hostile);
        }
    }

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
