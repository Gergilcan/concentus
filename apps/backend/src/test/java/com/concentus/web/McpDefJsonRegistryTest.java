package com.concentus.web;

import com.concentus.model.McpDef;
import com.concentus.store.McpDefStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The registry-as-JSON contract: what the editor shows, and what pasting an mcp.json snippet —
 * the way every MCP README ships its install — actually does to the stored definitions.
 */
class McpDefJsonRegistryTest {

    private final McpDefStore store = mock(McpDefStore.class);
    private final McpDefController controller = new McpDefController(store);
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void savedEchoes() {
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void theDocumentShowsBothTransportsInMcpJsonShape() {
        when(store.list()).thenReturn(List.of(
                McpDef.http("1", "Linear", "https://mcp.linear.app/mcp", "cred_1", null),
                new McpDef("2", "google-ads", null, null, null,
                        "npx", List.of("-y", "@googleads/mcp"), Map.of("DEV_TOKEN", "credential:cred_2"))));

        JsonNode doc = controller.registryJson();

        JsonNode linear = doc.path("mcpServers").path("Linear");
        assertThat(linear.path("url").asText()).isEqualTo("https://mcp.linear.app/mcp");
        assertThat(linear.path("credentialId").asText()).isEqualTo("cred_1");
        JsonNode ads = doc.path("mcpServers").path("google-ads");
        assertThat(ads.path("command").asText()).isEqualTo("npx");
        assertThat(ads.path("args").get(1).asText()).isEqualTo("@googleads/mcp");
        assertThat(ads.path("env").path("DEV_TOKEN").asText()).isEqualTo("credential:cred_2");
        assertThat(ads.has("url")).isFalse();
    }

    @Test
    void pastingAReadmeSnippetCreatesTheStdioServer() throws Exception {
        when(store.list()).thenReturn(List.of());

        Map<String, Object> result = controller.saveRegistryJson(json.readTree("""
                {"mcpServers": {"google-ads": {
                    "command": "npx",
                    "args": ["-y", "@googleads/google-ads-mcp"],
                    "env": {"GOOGLE_ADS_DEVELOPER_TOKEN": "credential:cred_9"}
                }}}"""));

        assertThat(result.get("saved")).isEqualTo(1);
        ArgumentCaptor<McpDef> saved = ArgumentCaptor.forClass(McpDef.class);
        verify(store).save(saved.capture());
        assertThat(saved.getValue().name()).isEqualTo("google-ads");
        assertThat(saved.getValue().isStdio()).isTrue();
        assertThat(saved.getValue().args()).containsExactly("-y", "@googleads/google-ads-mcp");
        assertThat(saved.getValue().env())
                .containsEntry("GOOGLE_ADS_DEVELOPER_TOKEN", "credential:cred_9");
    }

    @Test
    void savingMatchesByNameKeepsIdsAndDeletesTheAbsent() throws Exception {
        when(store.list()).thenReturn(List.of(
                McpDef.http("keep", "Linear", "https://old.example", null, null),
                McpDef.http("gone", "Sentry", "https://mcp.sentry.dev/mcp", null, null)));

        Map<String, Object> result = controller.saveRegistryJson(json.readTree("""
                {"mcpServers": {"linear": {"url": "https://mcp.linear.app/mcp"}}}"""));

        assertThat(result.get("deleted")).isEqualTo(1);
        verify(store).delete("gone");
        ArgumentCaptor<McpDef> saved = ArgumentCaptor.forClass(McpDef.class);
        verify(store, atLeastOnce()).save(saved.capture());
        // Case-insensitive name match: the existing definition is updated, not duplicated.
        assertThat(saved.getValue().id()).isEqualTo("keep");
        assertThat(saved.getValue().url()).isEqualTo("https://mcp.linear.app/mcp");
    }

    @Test
    void aPastedLiteralTokenHeaderIsRefusedIntoStorageAndExplained() throws Exception {
        when(store.list()).thenReturn(List.of());

        Map<String, Object> result = controller.saveRegistryJson(json.readTree("""
                {"mcpServers": {"gh": {"url": "https://x", "headers": {"Authorization": "Bearer sekrit"}}}}"""));

        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) result.get("warnings");
        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("Credentials"));
        ArgumentCaptor<McpDef> saved = ArgumentCaptor.forClass(McpDef.class);
        verify(store).save(saved.capture());
        // The token itself is nowhere in the stored definition.
        assertThat(String.valueOf(saved.getValue())).doesNotContain("sekrit");
    }

    @Test
    void aServerWithNeitherUrlNorCommandIsRejectedByName() throws Exception {
        when(store.list()).thenReturn(List.of());
        assertThatThrownBy(() -> controller.saveRegistryJson(json.readTree(
                "{\"mcpServers\": {\"broken\": {\"env\": {\"A\": \"b\"}}}}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("broken");
        verify(store, never()).delete(any());
    }

    @Test
    void theWrapperIsOptionalBecausePeoplePasteTheInnerMapToo() throws Exception {
        when(store.list()).thenReturn(List.of());
        Map<String, Object> result = controller.saveRegistryJson(json.readTree(
                "{\"solo\": {\"url\": \"https://mcp.example\"}}"));
        assertThat(result.get("saved")).isEqualTo(1);
    }
}
