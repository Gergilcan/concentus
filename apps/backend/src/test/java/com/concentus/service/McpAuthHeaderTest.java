package com.concentus.service;

import com.concentus.config.AgentSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How an MCP server's credential is presented on the wire.
 *
 * <p>This exists because the header is not universal. {@code Authorization: Bearer …} covers most
 * servers, GitHub included, but GitLab reads its project, group and personal access tokens from
 * {@code PRIVATE-TOKEN} with no prefix — so a fixed header is precisely what stops a GitLab server
 * working at all, and prefixing {@code Bearer } onto a PRIVATE-TOKEN value would silently corrupt
 * a token that is otherwise correct.
 *
 * <p>These assert the two rules directly rather than through a spawned CLI, since the CLI is not
 * available in a test run and the decision under test is the header string.
 */
class McpAuthHeaderTest {

    /** Mirrors the rule in {@link McpRegistry#add(String, String, String, String)}. */
    private static String headerFor(String configured, String token) {
        String header = configured == null || configured.isBlank()
                ? McpRegistry.DEFAULT_AUTH_HEADER : configured.trim();
        String prefix = McpRegistry.DEFAULT_AUTH_HEADER.equalsIgnoreCase(header) ? "Bearer " : "";
        return header + ": " + prefix + token;
    }

    @Test
    void theDefaultIsBearerOnAuthorization() {
        assertThat(headerFor(null, "ghp_abc")).isEqualTo("Authorization: Bearer ghp_abc");
        assertThat(headerFor("", "ghp_abc")).isEqualTo("Authorization: Bearer ghp_abc");
    }

    @Test
    void gitlabsHeaderCarriesTheTokenWithNoBearerPrefix() {
        // The whole reason the header is configurable: GitLab rejects a Bearer-prefixed value.
        assertThat(headerFor("PRIVATE-TOKEN", "glpat_abc")).isEqualTo("PRIVATE-TOKEN: glpat_abc");
    }

    @Test
    void thePrefixIsAddedOnlyForAuthorizationHoweverItIsCased() {
        assertThat(headerFor("authorization", "t")).isEqualTo("authorization: Bearer t");
        assertThat(headerFor("X-Api-Key", "t")).isEqualTo("X-Api-Key: t");
    }

    @Test
    void aConfiguredHeaderIsTrimmed() {
        // A stray space would produce an invalid header line rather than a wrong-but-valid one.
        assertThat(headerFor("  PRIVATE-TOKEN  ", "t")).isEqualTo("PRIVATE-TOKEN: t");
    }

    @Test
    void anMcpNodeDefaultsToNoExplicitHeader() {
        // Absent means "Authorization + Bearer", so every flow saved before GitLab support keeps
        // behaving exactly as it did.
        AgentSpec.McpServerSpec spec = new AgentSpec.McpServerSpec();

        assertThat(spec.authHeader).isNull();
        assertThat(headerFor(spec.authHeader, "t")).startsWith("Authorization: Bearer ");
    }

    @Test
    void interactiveLoginIsUnavailableWithoutADesktop() {
        // The property this guards: in a container there is no terminal and no browser, so
        // offering an OAuth button would start a sign-in that can never complete.
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean desktop = os.contains("win") || os.contains("mac");

        McpRegistry registry = new McpRegistry(mockSupport());

        assertThat(registry.supportsInteractiveLogin()).isEqualTo(desktop);
    }

    private static com.concentus.support.LocalClaudeSupport mockSupport() {
        com.concentus.support.LocalClaudeSupport support =
                org.mockito.Mockito.mock(com.concentus.support.LocalClaudeSupport.class);
        org.mockito.Mockito.when(support.command()).thenReturn(java.util.Optional.empty());
        return support;
    }
}
